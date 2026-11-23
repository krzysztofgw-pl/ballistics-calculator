package pl.ballistics.app

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import pl.ballistics.app.data.AmmoProfile
import pl.ballistics.app.data.AppData
import pl.ballistics.app.data.BulletLibrary
import pl.ballistics.app.data.BulletSuggestion
import pl.ballistics.app.data.CloudSync
import pl.ballistics.app.data.SyncState
import pl.ballistics.app.data.Conditions
import pl.ballistics.app.data.Repository
import pl.ballistics.app.data.SavedTarget
import pl.ballistics.app.data.Stealth
import pl.ballistics.app.data.UiSettings
import pl.ballistics.app.data.WeaponProfile
import pl.ballistics.app.data.ChronographFiles
import pl.ballistics.app.data.newId
import pl.ballistics.core.ChronographFormatException
import pl.ballistics.core.ShotSeries

/** A chronograph file being imported: its series, or why it could not be read. */
sealed interface ChronographImportState {
    data object Reading : ChronographImportState
    data class Loaded(val series: List<ShotSeries>) : ChronographImportState
    data class Failed(val reason: ChronographFormatException.Reason) : ChronographImportState
}

class MainViewModel(application: Application) : AndroidViewModel(application) {
    // Before anything that might use the network.
    init {
        Stealth.load(application)
    }

    private val repository = Repository(application)

    private val _data = MutableStateFlow(repository.load())
    val data: StateFlow<AppData> = _data.asStateFlow()

    private val _result = MutableStateFlow<CalcResult?>(null)
    val result: StateFlow<CalcResult?> = _result.asStateFlow()

    private val _chronograph = MutableStateFlow<ChronographImportState?>(null)
    val chronograph: StateFlow<ChronographImportState?> = _chronograph.asStateFlow()

    /** The stealth window, also opened by a long press on the Quick Settings tile. */
    val stealthDialog = MutableStateFlow(false)

    private var job: Job? = null
    private var pushJob: Job? = null

    private val cloud = CloudSync(application, ::onRemoteData).also { c ->
        c.pendingFirstPush = { c.push(_data.value) }
    }
    val syncState: StateFlow<SyncState> = cloud.state

    init {
        recalculate(debounce = false)
        viewModelScope.launch {
            // Each change of stealth mode pauses or resumes sync; the bullet database is fetched when allowed.
            Stealth.state.collect { s ->
                cloud.setPaused(s.on)
                if (!s.on) BulletLibrary.refresh(application)
            }
        }
    }

    private fun update(transform: (AppData) -> AppData) {
        val before = _data.value
        val after = transform(before).let { d ->
            // The range-card target being edited follows what is set on the screens.
            d.copy(targets = d.targets.map { if (it.id == d.activeTargetId) it.from(d.conditions) else it })
        }
        _data.value = after.copy(modifiedAt = System.currentTimeMillis())
        repository.save(_data.value)
        if (tableInputs(before) != tableInputs(after)) recalculate(debounce = true)
        pushJob?.cancel()
        pushJob = viewModelScope.launch {
            delay(1500)
            cloud.push(_data.value)
        }
    }

    /**
     * The first copy after signing in is merged with ours, so nothing on either side is lost.
     * After that, a newer copy arrived from another device; an older one means ours should go up.
     */
    private fun onRemoteData(remote: AppData, first: Boolean) {
        val local = _data.value
        if (first) {
            val merged = local.mergedWithCloud(remote, System.currentTimeMillis())
            _data.value = merged
            repository.save(merged)
            recalculate(debounce = false)
            if (merged !== remote) cloud.push(merged)
            return
        }
        when {
            remote.modifiedAt > local.modifiedAt -> {
                _data.value = remote
                repository.save(remote)
                recalculate(debounce = false)
            }
            remote.modifiedAt < local.modifiedAt -> cloud.push(local)
        }
    }

    suspend fun signIn(activity: android.content.Context): String? = cloud.signIn(activity)

    suspend fun deleteAccount(activity: android.content.Context): String? = cloud.deleteAccount(activity)

    suspend fun suggestBullet(suggestion: BulletSuggestion): String? {
        val app = getApplication<Application>()
        val version = runCatching { app.packageManager.getPackageInfo(app.packageName, 0).versionName }.getOrNull().orEmpty()
        return cloud.suggest(suggestion, version)
    }

    fun signOut() {
        viewModelScope.launch { cloud.signOut() }
    }

    fun updateConditions(transform: (Conditions) -> Conditions) = update { it.copy(conditions = transform(it.conditions)) }

    /** What the table depends on; target settings and the look of the app are solved or applied elsewhere. */
    private fun tableInputs(d: AppData) = listOf(d.weapons, d.ammo, d.activeWeaponId, d.conditions.withoutTarget())

    fun updateUi(transform: (UiSettings) -> UiSettings) = update { it.copy(ui = transform(it.ui)) }

    /** Recalls a range-card target; null leaves the card and keeps the current values. */
    fun selectTarget(id: String?) = update { d ->
        val t = d.targets.find { it.id == id }
        if (t == null) d.copy(activeTargetId = null) else d.copy(activeTargetId = t.id, conditions = d.conditions.withTarget(t))
    }

    /** Saves the current target values as a new range-card target and selects it. */
    fun addTarget(name: String) = update { d ->
        val t = SavedTarget(name = name).from(d.conditions)
        d.copy(targets = d.targets + t, activeTargetId = t.id)
    }

    fun renameTarget(id: String, name: String) = update { d ->
        d.copy(targets = d.targets.map { if (it.id == id) it.copy(name = name) else it })
    }

    fun deleteTarget(id: String) = update { d ->
        d.copy(targets = d.targets.filterNot { it.id == id }, activeTargetId = d.activeTargetId.takeIf { it != id })
    }

    fun selectWeapon(id: String) = update { it.copy(activeWeaponId = id) }

    /** Changes the selected rifle in place: its load or whether its suppressor is on. */
    fun updateActiveWeapon(change: (WeaponProfile) -> WeaponProfile) = update { d ->
        val active = d.activeWeapon ?: return@update d
        d.copy(weapons = d.weapons.map { if (it.id == active.id) change(it) else it })
    }

    fun saveWeapon(weapon: WeaponProfile) = update { d ->
        val exists = d.weapons.any { it.id == weapon.id }
        d.copy(
            weapons = if (exists) d.weapons.map { if (it.id == weapon.id) weapon else it } else d.weapons + weapon,
            activeWeaponId = if (exists) d.activeWeaponId else weapon.id,
        )
    }

    fun deleteWeapon(id: String) = update { d ->
        val weapons = d.weapons.filterNot { it.id == id }
        d.copy(weapons = weapons, activeWeaponId = if (d.activeWeaponId == id) weapons.firstOrNull()?.id else d.activeWeaponId)
    }

    fun saveAmmo(ammo: AmmoProfile) = update { d ->
        val exists = d.ammo.any { it.id == ammo.id }
        d.copy(ammo = if (exists) d.ammo.map { if (it.id == ammo.id) ammo else it } else d.ammo + ammo)
    }

    /**
     * Adds [copy] of the load [from] and puts it on the active rifle; muzzle velocities measured
     * with [from] carry over, since the cartridge is the same.
     */
    fun addAmmoCopy(from: AmmoProfile, copy: AmmoProfile) = update { d ->
        d.copy(
            ammo = d.ammo + copy,
            weapons = d.weapons.map { w ->
                val carried = w.measuredVelocity(from)?.let { w.withMeasuredVelocity(copy, it) } ?: w
                if (w.id == d.activeWeaponId) carried.copy(ammoId = copy.id) else carried
            },
        )
    }

    fun deleteAmmo(id: String) = update { d ->
        d.copy(
            ammo = d.ammo.filterNot { it.id == id },
            weapons = d.weapons.map {
                it.copy(ammoId = it.ammoId.takeIf { a -> a != id }, muzzleVelocities = it.muzzleVelocities - id)
            },
        )
    }

    /** Reads a chronograph file picked or shared by another app; the import dialog shows the result. */
    fun openChronograph(uri: android.net.Uri) = readChronograph { ChronographFiles.load(getApplication<Application>(), uri) }

    /** A chronograph export shared as text rather than as a file. */
    fun openChronographText(text: String) = readChronograph { ChronographFiles.parse(text.encodeToByteArray()) }

    private fun readChronograph(read: () -> List<ShotSeries>) {
        _chronograph.value = ChronographImportState.Reading
        viewModelScope.launch {
            _chronograph.value = withContext(Dispatchers.IO) {
                try {
                    ChronographImportState.Loaded(read())
                } catch (e: ChronographFormatException) {
                    ChronographImportState.Failed(e.reason)
                } catch (e: Exception) {
                    ChronographImportState.Failed(ChronographFormatException.Reason.NOT_RECOGNISED)
                }
            }
        }
    }

    /** Velocities typed in by hand, reviewed like an imported file. */
    fun openTypedSeries(series: ShotSeries) {
        _chronograph.value = ChronographImportState.Loaded(listOf(series))
    }

    fun closeChronograph() {
        _chronograph.value = null
    }

    /**
     * Saves a chronograph series to the load [ammoId]: the average as the velocity measured with the
     * rifle [weaponId] ([forRifle]) or as the load's reference velocity, and the SD to the load.
     * [copyName] puts it in a new copy of the load instead, which the rifle then uses.
     */
    fun applyChronograph(weaponId: String?, ammoId: String, velocity: String, sd: String, forRifle: Boolean, copyName: String?) = update { d ->
        val ammo = d.ammo.find { it.id == ammoId } ?: return@update d
        val base = if (copyName != null) ammo.copy(id = newId(), name = copyName) else ammo
        val saved = base.copy(velocitySd = sd, muzzleVelocity = if (forRifle) base.muzzleVelocity else velocity)
        d.copy(
            ammo = if (copyName != null) d.ammo + saved else d.ammo.map { if (it.id == ammoId) saved else it },
            weapons = d.weapons.map { w ->
                // A copy keeps the velocities measured with the original, as addAmmoCopy does.
                var r = if (copyName != null) w.measuredVelocity(ammo)?.let { w.withMeasuredVelocity(saved, it) } ?: w else w
                if (w.id == weaponId) {
                    if (forRifle) r = r.withMeasuredVelocity(saved, velocity)
                    if (copyName != null) r = r.copy(ammoId = saved.id)
                }
                r
            },
        )
    }

    /** Replaces everything with imported data; returns false if the file is not a valid export. */
    fun importData(text: String): Boolean {
        val imported = Repository.decode(text) ?: return false
        update { imported }
        return true
    }

    fun exportData(): String = Repository.encode(_data.value)

    private fun recalculate(debounce: Boolean) {
        job?.cancel()
        val d = _data.value
        job = viewModelScope.launch {
            if (debounce) delay(250)
            val weapon = d.activeWeapon
            _result.value = withContext(Dispatchers.Default) {
                Calculator.calculate(weapon, weapon?.let { d.ammoFor(it) }, d.conditions)
            }
        }
    }
}
