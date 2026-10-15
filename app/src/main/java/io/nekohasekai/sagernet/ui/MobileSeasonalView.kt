package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import app.qthrone.mobile.database.DataStore
import org.json.JSONObject

/** Approved transparent wide artwork; dates follow the device clock, including changes while the app is open. */
class MobileSeasonalView(context:Context):View(context) {
    private companion object { val animationEpoch=SystemClock.uptimeMillis() }
    private val manifest=runCatching { context.assets.open("seasonal/manifest.json").bufferedReader().use { JSONObject(it.readText()) } }.getOrNull()
    private val paint=Paint().apply { isFilterBitmap=false;isAntiAlias=false }
    private var kind=""
    private var atlas:Bitmap?=null
    private var columns=1
    private var frames=1
    private var interval=100L
    private var sourceWidth=136
    private var sourceHeight=46
    private var nextCheck=0L
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false }

    private fun updateTheme(now:Long) {
        if(now<nextCheck) return
        nextCheck=now+1000
        val selected=SeasonalPolicy.resolve(DataStore.seasonalDecoration)
        if(selected==kind) return
        kind=selected
        val entry=manifest?.optJSONObject(selected)
        val image=entry?.let { info -> runCatching {
            val sample=if(info.getInt("width")>=544) 4 else 1
            context.assets.open("seasonal/"+info.getString("file")).use {
                BitmapFactory.decodeStream(it,null,BitmapFactory.Options().apply { inSampleSize=sample })
            }
        }.getOrNull() }
        atlas=image
        if(image!=null && entry!=null) {
            columns=entry.getInt("columns");frames=entry.getInt("frames");interval=entry.getLong("interval")
            sourceWidth=image.width/columns
            sourceHeight=image.height/((frames+columns-1)/columns)
        }
    }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val now=SystemClock.uptimeMillis()
        updateTheme(now)
        val image=atlas
        if(image!=null && width>0 && height>0) {
            val scale=minOf(resources.displayMetrics.density*1.35f,width/136f,height/46f)
            val w=(136*scale).toInt();val h=(46*scale).toInt()
            val frame=(((now-animationEpoch)/interval)%frames).toInt()
            val x=frame%columns*sourceWidth;val y=frame/columns*sourceHeight
            canvas.drawBitmap(image,Rect(x,y,x+sourceWidth,y+sourceHeight),Rect(width-w,height-h,width,height),paint)
        }
        if(isShown && windowVisibility==VISIBLE) postInvalidateDelayed(if(image==null) 1000 else interval)
    }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE) { nextCheck=0;invalidate() } }
    override fun onVisibilityChanged(changedView:View,visibility:Int) { super.onVisibilityChanged(changedView,visibility);if(visibility==VISIBLE) { nextCheck=0;invalidate() } }
}
