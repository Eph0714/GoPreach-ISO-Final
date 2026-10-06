package com.emfitsolutions.gopreach.data

import com.emfitsolutions.gopreach.data.model.AppSettings
import com.emfitsolutions.gopreach.data.model.DeletedRecord
import com.emfitsolutions.gopreach.data.repository.RecycleBinRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Calendar

class RecycleBinRetentionTest {
    private fun date(month: Int, day: Int) = Calendar.getInstance().apply { clear(); set(2026, month, day, 12, 0) }.timeInMillis

    @Test
    fun permanentDeletionDateIsDeletionPlusRetention() {
        val record = DeletedRecord(deletedAt = date(Calendar.OCTOBER, 5))
        val settings = AppSettings(trashAutoDeleteEnabled = true, trashRetentionDays = 30)
        // Deleted October 5, 2026 + 30 days = November 4, 2026.
        assertEquals(date(Calendar.NOVEMBER, 4), RecycleBinRepository.permanentDeleteAt(record, settings))
    }

    @Test
    fun noAutomaticDeletionDateWhenTheSettingIsOff() {
        val record = DeletedRecord(deletedAt = date(Calendar.OCTOBER, 5))
        assertNull(RecycleBinRepository.permanentDeleteAt(record, AppSettings(trashAutoDeleteEnabled = false, trashRetentionDays = 7)))
    }

    @Test
    fun retentionOptionsAreTheOfferedOnes() {
        assertEquals(listOf(7, 14, 30, 60, 90, 180, 365), AppSettings.TRASH_RETENTION_OPTIONS)
        // Off by default: nothing is deleted automatically until someone turns it on.
        assertEquals(false, AppSettings().trashAutoDeleteEnabled)
    }
}
