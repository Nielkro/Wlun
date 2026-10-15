package app.qthrone.mobile.ui

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SeasonalPolicyTest {
    @Test fun datesOverrideSeasonsWithInclusiveBoundaries() {
        val dates=mapOf(
            (10 to 29) to "halloween",(10 to 30) to "remembrance",(10 to 31) to "halloween",
            (12 to 23) to "newyear",(12 to 24) to "christmas",(12 to 31) to "christmas",
            (1 to 1) to "christmas",(1 to 7) to "christmas",(1 to 8) to "newyear",
            (2 to 13) to "newyear",(2 to 14) to "valentine",(2 to 15) to "newyear",
            (3 to 7) to "easter",(3 to 8) to "maslenitsa",(3 to 14) to "maslenitsa",
            (3 to 15) to "easter",(3 to 17) to "patrick",(3 to 18) to "easter",
            (5 to 7) to "easter",(5 to 8) to "may_remembrance",(5 to 9) to "may_remembrance",(5 to 10) to "easter",
            (6 to 1) to "kupala",(8 to 31) to "kupala",(9 to 1) to "halloween")
        dates.forEach { (date,expected) -> assertEquals(date.toString(),expected,SeasonalPolicy.automatic(date.first,date.second)) }
    }
    @Test fun exactSameInstantUsesTheDeviceTimeZone() {
        val instant=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear();set(2026,Calendar.OCTOBER,29,12,0,0)
        }.timeInMillis
        assertEquals("remembrance",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Kiritimati")))
        assertEquals("halloween",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Pago_Pago")))
    }
    @Test fun migrationRunsOnceAndHiddenThemesCannotBeManuallySelected() {
        for(choice in SeasonalPolicy.manualThemes+setOf("auto","none")) {
            assertEquals("auto",SeasonalPolicy.migratedChoice(choice,false))
            assertEquals(choice,SeasonalPolicy.migratedChoice(choice,true))
        }
        assertEquals(8,SeasonalPolicy.manualThemes.size)
        assertEquals("none",SeasonalPolicy.resolve("remembrance"))
        assertEquals("none",SeasonalPolicy.resolve("may_remembrance"))
        assertEquals("none",SeasonalPolicy.resolve("none"))
        assertEquals("halloween",SeasonalPolicy.resolve("halloween"))
    }
}
