package app.qthrone.mobile.ui

import java.util.Calendar
import java.util.TimeZone

/** Calendar rules use the device's current local date, never UTC or a fixed release-year calendar. */
object SeasonalPolicy {
    val manualThemes=setOf("halloween","newyear","kupala","patrick","christmas","easter","valentine","maslenitsa","spring","autumn")
    fun automatic(month:Int,day:Int):String = when {
        month==10 && day==30 -> "remembrance"
        month==5 && day in 8..9 -> "may_remembrance"
        (month==12 && day>=25) || (month==1 && day<=7) -> "christmas"
        month==2 && day==14 -> "valentine"
        month==3 && day in 15..22 -> "patrick"
        month==3 && day in 8..14 -> "maslenitsa"
        (month==4 && day>=22) || (month==5 && day<=7) -> "easter"
        month in 3..5 -> "spring"
        month in 6..8 -> "kupala"
        month==9 || (month==10 && day<=29) -> "autumn"
        month in 10..11 -> "halloween"
        else -> "newyear"
    }
    fun resolve(choice:String,now:Long=System.currentTimeMillis(),zone:TimeZone=TimeZone.getDefault()):String {
        if(choice!="auto") return if(choice in manualThemes) choice else "none"
        val local=Calendar.getInstance(zone).apply { timeInMillis=now }
        return automatic(local.get(Calendar.MONTH)+1,local.get(Calendar.DAY_OF_MONTH))
    }
    fun migratedChoice(choice:String,migrated:Boolean)=if(migrated) choice else "auto"
}
