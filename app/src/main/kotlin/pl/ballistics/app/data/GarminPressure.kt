package pl.ballistics.app.data

import android.content.Context
import android.content.pm.PackageManager
import com.garmin.android.connectiq.ConnectIQ
import com.garmin.android.connectiq.IQApp
import com.garmin.android.connectiq.IQDevice
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * Station pressure from a Garmin watch's barometer, through the Station Pressure Connect IQ app
 * (source in `garmin/`) and Garmin Connect on the phone.
 */
object GarminPressure {
    /** The public store app's id from `garmin/manifest.xml`. */
    private const val APP_ID = "8479255366624599996860607684e2eb"

    /**
     * The id of the earlier beta upload. A beta can't become a public app, so the public one has a new
     * id; watches that still carry the beta keep working. CI builds the beta package with this id.
     */
    private const val BETA_APP_ID = "d8c4bc4d63d94a6cb0071345dc138c56"

    /** An id as a dashed UUID, the form Garmin's samples use; messages are matched on either form. */
    private fun dashed(id: String) =
        "${id.substring(0, 8)}-${id.substring(8, 12)}-${id.substring(12, 16)}-${id.substring(16, 20)}-${id.substring(20)}"
    private const val CONNECT_PACKAGE = "com.garmin.android.apps.connectmobile"

    sealed interface Result {
        data class Pressure(val hPa: Double, val watch: String) : Result
        data object NoGarminConnect : Result
        data object NoWatch : Result
        data object AppNotInstalled : Result

        /** What Garmin Connect reported on the way, to tell where it stopped. */
        data class NoAnswer(val details: String) : Result
    }

    fun garminConnectInstalled(context: Context): Boolean =
        runCatching { context.packageManager.getPackageInfo(CONNECT_PACKAGE, 0) }.isSuccess

    /**
     * Opens the watch app (the watch asks to confirm) and waits for its reading. [onPrompt] is called
     * when the watch shows that prompt, so the user can be told to accept it. The watch shows its reading
     * in [unit] ("hPa", "mmHg" or "inHg"), the app's pressure unit; the reading itself travels in Pa.
     */
    suspend fun read(context: Context, unit: String = "hPa", timeoutMs: Long = 45_000, onPrompt: () -> Unit = {}): Result {
        if (!garminConnectInstalled(context)) return Result.NoGarminConnect
        val app = context.applicationContext
        val ciq = ConnectIQ.getInstance(app, ConnectIQ.IQConnectType.WIRELESS)
        if (withTimeoutOrNull(10_000) { initialize(ciq, app) } != true) return Result.NoGarminConnect
        val publicApp = IQApp(APP_ID)
        val betaApp = IQApp(BETA_APP_ID)
        val apps = listOf(APP_ID, BETA_APP_ID).flatMap { listOf(IQApp(it), IQApp(dashed(it))) }
        val devices = mutableListOf<IQDevice>()
        try {
            devices += runCatching { ciq.connectedDevices.orEmpty() }.getOrDefault(emptyList())
            if (devices.isEmpty()) return Result.NoWatch

            val answer = CompletableDeferred<Result.Pressure>()
            var notInstalled = 0
            val trace = linkedSetOf<String>()
            for (device in devices) {
                runCatching {
                    for (each in apps) ciq.registerForAppEvents(device, each) { d, _, message, status ->
                        if (status != ConnectIQ.IQMessageStatus.SUCCESS) {
                            trace += "received $status"
                            return@registerForAppEvents
                        }
                        if (pascals(message) == null) trace += "received $message"
                        pascals(message)?.let { answer.complete(Result.Pressure(it / 100.0, d.friendlyName.orEmpty())) }
                    }
                    // The public app first; only when it is missing, the beta, so the watch asks once.
                    ciq.openApplication(device, publicApp) { _, _, status ->
                        trace += "open $status"
                        when (status) {
                            ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> onPrompt()
                            ConnectIQ.IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> runCatching {
                                ciq.openApplication(device, betaApp) { _, _, beta ->
                                    trace += "open beta $beta"
                                    when (beta) {
                                        ConnectIQ.IQOpenApplicationStatus.PROMPT_SHOWN_ON_DEVICE -> onPrompt()
                                        ConnectIQ.IQOpenApplicationStatus.APP_IS_NOT_INSTALLED -> notInstalled++
                                        else -> Unit
                                    }
                                }
                            }.onFailure { trace += it.javaClass.simpleName }
                            else -> Unit
                        }
                    }
                }.onFailure { trace += it.javaClass.simpleName }
            }
            // The app may still be starting when the first request arrives, and a request it misses is
            // lost, so ask again every few seconds until a reading comes.
            return withTimeoutOrNull(timeoutMs) {
                while (!answer.isCompleted) {
                    for (device in devices) {
                        runCatching {
                            for (each in apps) {
                                ciq.sendMessage(device, each, mapOf("get" to 1, "unit" to unit)) { _, a, status ->
                                    trace += "request ${label(a?.applicationId)}$status"
                                }
                            }
                        }.onFailure { trace += it.javaClass.simpleName }
                    }
                    withTimeoutOrNull(3_000) { answer.await() }
                }
                answer.await()
            } ?: if (notInstalled == devices.size) Result.AppNotInstalled else Result.NoAnswer(trace.joinToString())
        } finally {
            for (device in devices) apps.forEach { runCatching { ciq.unregisterForApplicationEvents(device, it) } }
            runCatching { ciq.shutdown(app) }
        }
    }

    /** Which of the ids a send went to, for the diagnostic trace; the public hex id gets no label. */
    private fun label(id: String?): String = when (id) {
        dashed(APP_ID) -> "uuid "
        BETA_APP_ID -> "beta "
        dashed(BETA_APP_ID) -> "beta uuid "
        else -> ""
    }

    private suspend fun initialize(ciq: ConnectIQ, context: Context): Boolean =
        suspendCancellableCoroutine { cont ->
            ciq.initialize(context, false, object : ConnectIQ.ConnectIQListener {
                override fun onSdkReady() {
                    if (cont.isActive) cont.resume(true)
                }

                override fun onInitializeError(status: ConnectIQ.IQSdkErrorStatus?) {
                    if (cont.isActive) cont.resume(false)
                }

                override fun onSdkShutDown() = Unit
            })
        }

    /** The watch sends `{"pa": <float>}`; anything outside a plausible range is ignored. */
    private fun pascals(message: List<Any?>?): Double? =
        message.orEmpty().firstNotNullOfOrNull { (it as? Map<*, *>)?.get("pa") as? Number }
            ?.toDouble()
            ?.takeIf { it in 30_000.0..110_000.0 }
}
