package app.qthrone.mobile.ui

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.view.View
import androidx.fragment.app.Fragment
import app.qthrone.mobile.R

/** Animate the actual laid-out screen, including its children, as one surface. */
object MobileScreenMotion {
    fun create(fragment: Fragment, nextAnim: Int): Animator? {
        val view = fragment.view ?: return null
        val width = (view.parent as? View)?.width?.takeIf { it > 0 }
            ?: fragment.resources.displayMetrics.widthPixels
        val (from, to) = when (nextAnim) {
            R.anim.settings_enter -> width.toFloat() to 0f
            R.anim.settings_exit -> 0f to -width * .12f
            R.anim.settings_pop_enter -> -width * .12f to 0f
            R.anim.settings_pop_exit -> 0f to width.toFloat()
            else -> return null
        }
        return ObjectAnimator.ofFloat(view, View.TRANSLATION_X, from, to).apply {
            duration = 220
            interpolator = android.view.animation.DecelerateInterpolator(1.5f)
            addListener(object : AnimatorListenerAdapter() {
                override fun onAnimationStart(animation: Animator) { view.setLayerType(View.LAYER_TYPE_HARDWARE, null) }
                override fun onAnimationEnd(animation: Animator) { view.setLayerType(View.LAYER_TYPE_NONE, null) }
                override fun onAnimationCancel(animation: Animator) { view.setLayerType(View.LAYER_TYPE_NONE, null) }
            })
        }
    }
}
