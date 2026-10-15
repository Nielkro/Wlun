package app.qthrone.mobile.ui

import java.util.Calendar
import java.util.TimeZone
import org.junit.Assert.*
import org.junit.Test

class SeasonalPolicyTest {
    @Test fun datesOverrideSeasonsWithInclusiveBoundaries() {
        val dates=mapOf(
            (9 to 1) to "autumn",(10 to 29) to "autumn",(10 to 30) to "remembrance",(10 to 31) to "halloween",
            (11 to 30) to "halloween",(12 to 1) to "newyear",(12 to 24) to "newyear",(12 to 25) to "christmas",(12 to 31) to "christmas",
            (1 to 1) to "christmas",(1 to 7) to "christmas",(1 to 8) to "newyear",
            (2 to 13) to "newyear",(2 to 14) to "valentine",(2 to 15) to "newyear",(2 to 29) to "newyear",
            (3 to 1) to "spring",(3 to 7) to "spring",(3 to 8) to "maslenitsa",(3 to 14) to "maslenitsa",
            (3 to 15) to "patrick",(3 to 22) to "patrick",(3 to 23) to "spring",
            (4 to 21) to "spring",(4 to 22) to "easter",(5 to 7) to "easter",
            (5 to 8) to "may_remembrance",(5 to 9) to "may_remembrance",(5 to 10) to "spring",(5 to 31) to "spring",
            (6 to 1) to "kupala",(8 to 31) to "kupala")
        dates.forEach { (date,expected) -> assertEquals(date.toString(),expected,SeasonalPolicy.automatic(date.first,date.second)) }
    }
    @Test fun fixedScheduleCoversEveryDayOfCommonAndLeapYears() {
        // Inclusive period ends from the approved whole-year table, independent of implementation branches.
        val periods=listOf(107 to "christmas",213 to "newyear",214 to "valentine",229 to "newyear",
            307 to "spring",314 to "maslenitsa",322 to "patrick",421 to "spring",507 to "easter",
            509 to "may_remembrance",531 to "spring",831 to "kupala",1029 to "autumn",
            1030 to "remembrance",1130 to "halloween",1224 to "newyear",1231 to "christmas")
        for(year in listOf(2027,2028)) {
            val date=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply { clear();set(year,Calendar.JANUARY,1) }
            var days=0
            while(date.get(Calendar.YEAR)==year) {
                val month=date.get(Calendar.MONTH)+1;val day=date.get(Calendar.DAY_OF_MONTH)
                val expected=periods.first { month*100+day<=it.first }.second
                assertEquals("$year-$month-$day",expected,SeasonalPolicy.resolve("auto",date.timeInMillis,date.timeZone))
                date.add(Calendar.DAY_OF_MONTH,1);days++
            }
            assertEquals(if(year==2028) 366 else 365,days)
        }
    }
    @Test fun exactSameInstantUsesTheDeviceTimeZone() {
        val instant=Calendar.getInstance(TimeZone.getTimeZone("UTC")).apply {
            clear();set(2026,Calendar.OCTOBER,29,12,0,0)
        }.timeInMillis
        assertEquals("remembrance",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Kiritimati")))
        assertEquals("autumn",SeasonalPolicy.resolve("auto",instant,TimeZone.getTimeZone("Pacific/Pago_Pago")))
    }
    @Test fun migrationRunsOnceAndHiddenThemesCannotBeManuallySelected() {
        for(choice in SeasonalPolicy.manualThemes+setOf("auto","none")) {
            assertEquals("auto",SeasonalPolicy.migratedChoice(choice,false))
            assertEquals(choice,SeasonalPolicy.migratedChoice(choice,true))
        }
        assertEquals(10,SeasonalPolicy.manualThemes.size)
        assertEquals("none",SeasonalPolicy.resolve("remembrance"))
        assertEquals("none",SeasonalPolicy.resolve("may_remembrance"))
        assertEquals("none",SeasonalPolicy.resolve("none"))
        assertEquals("halloween",SeasonalPolicy.resolve("halloween"))
        assertEquals("newyear",SeasonalPolicy.resolve("newyear")) // Rename must preserve the saved choice.
        assertEquals("spring",SeasonalPolicy.resolve("spring"))
        assertEquals("autumn",SeasonalPolicy.resolve("autumn"))
    }
}
