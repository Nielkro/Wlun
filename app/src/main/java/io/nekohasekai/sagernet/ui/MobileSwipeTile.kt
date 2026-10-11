package app.qthrone.mobile.ui

import android.content.Context
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import kotlin.math.abs

/** Horizontal swipe reveals actions; vertical drags continue scrolling the subscription list. */
class MobileSwipeTile(context:Context, private val row:View, test:View, share:View, delete:View):FrameLayout(context) {
    var onOpened: ((MobileSwipeTile) -> Unit)? = null
    private val primaryActions=listOf(test,share)
    private val actionWidth=56*resources.displayMetrics.density
    fun showShareChoices(vararg choices:View) {
        left.removeAllViews()
        choices.forEach { left.addView(it,LinearLayout.LayoutParams(0,-1,1f)) }
        widthPx=actionWidth*choices.size
        left.layoutParams=left.layoutParams.apply { width=widthPx.toInt() }
        row.animate().translationX(widthPx*direction).setDuration(180).start()
    }
    private fun restoreActions() {
        left.removeAllViews()
        primaryActions.forEach { left.addView(it,LinearLayout.LayoutParams(0,-1,1f)) }
        widthPx=actionWidth*2
        left.layoutParams=left.layoutParams.apply { width=widthPx.toInt() }
    }
    fun closeActions() {
        left.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        right.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        row.animate().translationX(0f).setDuration(180).withEndAction { left.visibility=View.INVISIBLE; right.visibility=View.INVISIBLE;restoreActions() }.start()
    }
    private var widthPx=112*resources.displayMetrics.density
    private val slop=ViewConfiguration.get(context).scaledTouchSlop
    private var downX=0f; private var downY=0f; private var initial=0f
    private var dragging=false
    private var touchedRow=false
    private val direction: Float get() = if(layoutDirection==View.LAYOUT_DIRECTION_RTL) -1f else 1f
    private val left=LinearLayout(context)
    private val right=delete
    init {
        left.addView(test,LinearLayout.LayoutParams(0,-1,1f));left.addView(share,LinearLayout.LayoutParams(0,-1,1f))
        addView(left,LayoutParams(widthPx.toInt(),-1,android.view.Gravity.START))
        addView(delete,LayoutParams((widthPx/2).toInt(),-1,android.view.Gravity.END))
        addView(row,LayoutParams(-1,-2))
        clipChildren=true
        left.visibility=View.INVISIBLE
        right.visibility=View.INVISIBLE
        left.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        right.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) { row.animate().cancel(); downX=e.x;downY=e.y;initial=row.translationX*direction;dragging=false; touchedRow=e.x>=row.translationX && e.x<row.translationX+row.width; if(initial!=0f && touchedRow) return true }
        if(e.actionMasked==MotionEvent.ACTION_MOVE && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) {
            dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true
        }
        if(e.actionMasked==MotionEvent.ACTION_UP && initial!=0f && !dragging && touchedRow) return true
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_MOVE -> {
                if(abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) dragging=true
                val offset=(initial+(e.x-downX)*direction).coerceIn(-actionWidth,widthPx)
                left.visibility=if(offset>0) View.VISIBLE else View.INVISIBLE
                right.visibility=if(offset<0) View.VISIBLE else View.INVISIBLE
                row.translationX=offset*direction
            }
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val x=row.translationX*direction
                val end=if(e.actionMasked==MotionEvent.ACTION_CANCEL || !dragging) 0f else if(x>widthPx*.35f) widthPx else if(x< -actionWidth*.4f) -actionWidth else 0f
                if(end!=0f) onOpened?.invoke(this)
                left.importantForAccessibility=if(end>0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                right.importantForAccessibility=if(end<0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                row.animate().translationX(end*direction).setDuration(180).withEndAction {
                    left.visibility=if(end>0) View.VISIBLE else View.INVISIBLE
                    right.visibility=if(end<0) View.VISIBLE else View.INVISIBLE
                    if(end==0f) restoreActions()
                }.start()
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
