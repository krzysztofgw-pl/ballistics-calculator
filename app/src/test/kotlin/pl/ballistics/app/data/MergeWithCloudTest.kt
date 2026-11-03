package pl.ballistics.app.data

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertSame
import kotlin.test.assertTrue

class MergeWithCloudTest {
    private val rifle = WeaponProfile(name = "Ruger SFAR", caliber = "6.5 Creedmoor")
    private val load = AmmoProfile(name = "S&B 142 HPBT")
    private val cloud = AppData(
        weapons = listOf(rifle.copy(ammoId = load.id)),
        ammo = listOf(load),
        activeWeaponId = rifle.id,
        modifiedAt = 1_000,
    )

    @Test
    fun freshInstallWithEditsKeepsTheCloudProfiles() {
        // A new install holds the sample rifle and was touched after the cloud copy was made.
        val local = AppData.sample().copy(modifiedAt = 5_000)
        val merged = local.mergedWithCloud(cloud, now = 9_000)
        assertSame(cloud, merged)
    }

    @Test
    fun profilesFromBothSidesAreKept() {
        val ownRifle = WeaponProfile(name = "Tikka T3x")
        val local = AppData(weapons = listOf(ownRifle), activeWeaponId = ownRifle.id, modifiedAt = 5_000)
        val merged = local.mergedWithCloud(cloud, now = 9_000)
        assertEquals(listOf(rifle.id, ownRifle.id), merged.weapons.map { it.id })
        assertEquals(listOf(load.id), merged.ammo.map { it.id })
        assertEquals(rifle.id, merged.activeWeaponId)
        assertEquals(9_000, merged.modifiedAt)
    }

    @Test
    fun theCloudWinsForTheSameProfile() {
        val local = AppData(weapons = listOf(rifle.copy(name = "old name")), modifiedAt = 5_000)
        val merged = local.mergedWithCloud(cloud, now = 9_000)
        assertSame(cloud, merged)
    }

    @Test
    fun anEditedSampleIsKept() {
        val sample = AppData.sample()
        val edited = sample.copy(weapons = sample.weapons.map { it.copy(name = "My .308") })
        val merged = edited.mergedWithCloud(cloud, now = 9_000)
        assertTrue(merged.weapons.any { it.name == "My .308" })
        // Its load is the untouched sample load, which is dropped, so the rifle loses the link.
        assertTrue(merged.weapons.single { it.name == "My .308" }.ammoId == null)
    }
}
