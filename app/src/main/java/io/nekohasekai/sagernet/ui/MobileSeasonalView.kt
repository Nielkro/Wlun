package app.qthrone.mobile.ui

import android.content.Context
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import app.qthrone.mobile.database.DataStore

/** Desktop artwork frames; animation only runs while this view is on screen. */
class MobileSeasonalView(context:Context):View(context) {
    private companion object { val animationEpoch=SystemClock.uptimeMillis() }
    private val kind=DataStore.seasonalDecoration
    private val fps=if(kind=="newyear") 20 else 10
    private val start=animationEpoch
    private val paint=Paint().apply { isFilterBitmap=false;isAntiAlias=false }
    private val atlas=if(kind in listOf("halloween","newyear")) runCatching {
        context.assets.open("seasonal/$kind-${if(MobilePalette.light) "light" else "dark"}.png").use { BitmapFactory.decodeStream(it) }
    }.getOrNull() else null
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;if(atlas==null) visibility=GONE }
    override fun onDraw(canvas:Canvas) {
        super.onDraw(canvas)
        val image=atlas ?: return
        val scale=resources.displayMetrics.density*1.35f
        val w=(136*scale).toInt();val h=(46*scale).toInt()
        if(width<w || height<h) return
        val frame=(((SystemClock.uptimeMillis()-start)*fps/1000)%(24*fps)).toInt()
        val source=Rect(frame%20*136,frame/20*46,frame%20*136+136,frame/20*46+46)
        canvas.drawBitmap(image,source,Rect(width-w,height-h,width,height),paint)
        if(isShown && windowVisibility==VISIBLE) postInvalidateDelayed((1000/fps).toLong())
    }
    override fun onWindowVisibilityChanged(visibility:Int) { super.onWindowVisibilityChanged(visibility);if(visibility==VISIBLE) invalidate() }
    override fun onVisibilityChanged(changedView:View,visibility:Int) { super.onVisibilityChanged(changedView,visibility);if(visibility==VISIBLE) invalidate() }
}
