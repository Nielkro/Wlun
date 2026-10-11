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
    private val widthPx=112*resources.displayMetrics.density
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
        left.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        right.importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
    }
    override fun onInterceptTouchEvent(e:MotionEvent):Boolean {
        if(e.actionMasked==MotionEvent.ACTION_DOWN) { row.animate().cancel(); downX=e.x;downY=e.y;initial=row.translationX*direction;dragging=false; touchedRow=e.x>=row.translationX && e.x<row.translationX+row.width }
        if(e.actionMasked==MotionEvent.ACTION_MOVE && abs(e.x-downX)>slop && abs(e.x-downX)>abs(e.y-downY)) {
            dragging=true;parent.requestDisallowInterceptTouchEvent(true);return true
        }
        if(e.actionMasked==MotionEvent.ACTION_UP && initial!=0f && !dragging && touchedRow) return true
        return super.onInterceptTouchEvent(e)
    }
    override fun onTouchEvent(e:MotionEvent):Boolean {
        when(e.actionMasked) {
            MotionEvent.ACTION_MOVE -> row.translationX=(initial+(e.x-downX)*direction).coerceIn(-widthPx/2,widthPx)*direction
            MotionEvent.ACTION_UP,MotionEvent.ACTION_CANCEL -> {
                val x=row.translationX*direction
                val end=if(e.actionMasked==MotionEvent.ACTION_CANCEL || !dragging) 0f else if(x>widthPx*.35f) widthPx else if(x< -widthPx*.2f) -widthPx/2 else 0f
                left.importantForAccessibility=if(end>0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                right.importantForAccessibility=if(end<0) View.IMPORTANT_FOR_ACCESSIBILITY_AUTO else View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                row.animate().translationX(end*direction).setDuration(180).start()
                parent.requestDisallowInterceptTouchEvent(false)
            }
        }
        return true
    }
}
