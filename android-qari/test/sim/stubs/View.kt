package android.view

import android.content.Context
import android.graphics.Canvas
import java.io.File

open class MotionEvent {
    companion object {
        const val ACTION_UP = 1; const val ACTION_DOWN = 0
        const val ACTION_MOVE = 2; const val ACTION_CANCEL = 3
    }
    var action = 0
    var actionMasked = 0
    var x = 0f
    var y = 0f
}

open class View(val context: Context) {
    companion object { const val LAYER_TYPE_HARDWARE = 2; const val LAYER_TYPE_SOFTWARE = 1 }
    var width: Int = 0
    var height: Int = 0
    var isClickable: Boolean = false
    var parent: View? = null
    fun setLayerType(layer: Int, paint: Any?) { }
    fun requestDisallowInterceptTouchEvent(disallow: Boolean) { }
    open fun invalidate() { }
    open fun performClick(): Boolean = true
    open fun onDraw(canvas: Canvas) { }
    open fun onTouchEvent(event: MotionEvent): Boolean = false
}
