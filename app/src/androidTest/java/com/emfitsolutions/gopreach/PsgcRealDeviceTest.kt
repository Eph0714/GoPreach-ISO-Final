package com.emfitsolutions.gopreach

import android.util.Log
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.emfitsolutions.gopreach.data.local.psgc.PsgcDatabase
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Diagnostic-only test: opens the bundled PSGC asset database on a REAL
 * device/emulator exactly the way DatabaseModule.providePsgcDatabase does
 * (Room.databaseBuilder + createFromAsset), then runs the exact cascading
 * query chain a publisher would trigger in the Address Picker (Region II ->
 * Nueva Vizcaya -> Solano -> its barangays), logging counts at every step so
 * the real on-device data flow can be inspected via logcat, not just static
 * file inspection on the dev machine.
 */
@RunWith(AndroidJUnit4::class)
class PsgcRealDeviceTest {
    @Test
    fun psgcDatabaseLoadsRealRecords() = runBlocking {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        // Wipe any pre-existing on-device copy first so this test always
        // exercises a genuine fresh createFromAsset copy, not whatever
        // happened to be left over from a previous run/app launch.
        context.getDatabasePath(PsgcDatabase.DATABASE_NAME).delete()

        val db = Room.databaseBuilder(context, PsgcDatabase::class.java, PsgcDatabase.DATABASE_NAME)
            .createFromAsset(PsgcDatabase.ASSET_PATH)
            .build()
        val dao = db.psgcDao()

        val allProvinces = dao.searchProvinces("")
        Log.d("PSGC_DEBUG", "Provinces loaded (unfiltered, capped at 50): ${allProvinces.size}")
        assertTrue("Expected provinces to load, got ${allProvinces.size}", allProvinces.isNotEmpty())

        // Matches what the real UI does when the publisher types a name —
        // not the unfiltered/capped list above, which only shows the first
        // 50 alphabetically and was never meant to contain every province.
        val provinces = dao.searchProvinces("NUEVA VIZCAYA")
        Log.d("PSGC_DEBUG", "searchProvinces('NUEVA VIZCAYA') -> ${provinces.size}")
        val nuevaVizcaya = provinces.firstOrNull { it.nameNormalized == "NUEVA VIZCAYA" }
        Log.d("PSGC_DEBUG", "Nueva Vizcaya found: ${nuevaVizcaya != null}")
        assertTrue("Nueva Vizcaya must exist in provinces", nuevaVizcaya != null)

        val muncities = dao.searchMuncitiesInProvince(nuevaVizcaya!!.id, "")
        Log.d("PSGC_DEBUG", "Municipalities in Nueva Vizcaya: ${muncities.size} -> ${muncities.map { it.name }}")
        assertTrue("Expected municipalities under Nueva Vizcaya", muncities.isNotEmpty())

        val solano = muncities.firstOrNull { it.nameNormalized == "SOLANO" }
        Log.d("PSGC_DEBUG", "Solano found: ${solano != null}")
        assertTrue("Solano must exist under Nueva Vizcaya", solano != null)

        val barangays = dao.searchBarangaysInMuncity(solano!!.id, "")
        Log.d("PSGC_DEBUG", "Barangays in Solano: ${barangays.size} -> ${barangays.map { it.name }}")
        assertTrue("Expected barangays under Solano", barangays.isNotEmpty())

        // New Province/Municipality-City/Barangay format: no top-level
        // "province" row should ever be a city in disguise anymore -- every
        // Highly Urbanized/Independent City must appear one level down, as a
        // Municipality/City under its real geographic province, and NCR's
        // cities must all be reachable under a single "Metro Manila" entry.
        val cebuProvince = dao.searchProvinces("CEBU").firstOrNull { it.nameNormalized == "CEBU" }
        assertTrue("Cebu (the province) must exist", cebuProvince != null)
        val cebuCities = dao.searchMuncitiesInProvince(cebuProvince!!.id, "")
        Log.d("PSGC_DEBUG", "Cities under Cebu province: ${cebuCities.map { it.name }}")
        assertTrue("Cebu City must be a Municipality/City under Cebu province", cebuCities.any { it.nameNormalized == "CEBU CITY" })

        val metroManila = dao.searchProvinces("METRO MANILA").firstOrNull()
        assertTrue("Metro Manila province bucket must exist for NCR", metroManila != null)
        val ncrCities = dao.searchMuncitiesInProvince(metroManila!!.id, "")
        Log.d("PSGC_DEBUG", "Metro Manila cities/municipalities: ${ncrCities.size} -> ${ncrCities.map { it.name }}")
        assertTrue("Metro Manila must contain Quezon City", ncrCities.any { it.nameNormalized == "QUEZON CITY" })
        assertTrue("Metro Manila must contain Manila", ncrCities.any { it.nameNormalized == "MANILA" })

        Log.d("PSGC_DEBUG", "ALL CHECKS PASSED")
        db.close()
    }
}
