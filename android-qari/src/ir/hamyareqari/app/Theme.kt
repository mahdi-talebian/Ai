package ir.hamyareqari.app

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.graphics.drawable.ColorDrawable
import android.content.res.ColorStateList
import android.os.Build
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.TextView

/**
 * هویت بصری «همیار قاری»: سرمه‌ای تیره + طلایی.
 * همه‌چیز دست‌ساز است و به هیچ کتابخانهٔ رابط کاربری بیرونی وابسته نیست.
 */
object Theme {
    const val BG1 = 0xFF0B1224.toInt()
    const val BG2 = 0xFF121C33.toInt()
    const val BG3 = 0xFF0A1020.toInt()
    const val CARD = 0xFF111B31.toInt()
    const val LINE = 0x1AFFFFFF
    const val TXT = 0xFFE8ECF7.toInt()
    const val DIM = 0xFF93A0BD.toInt()
    const val GOLD = 0xFFF7D171.toInt()
    const val GOLD2 = 0xFFFFE6A6.toInt()
    const val TEAL = 0xFF23E0C2.toInt()
    const val VIOLET = 0xFF8B7CFF.toInt()
    const val RED = 0xFFFF7B7B.toInt()
    const val INK = 0xFF0C1322.toInt()

    private var regular: Typeface? = null
    private var bold: Typeface? = null

    fun font(ctx: Context, heavy: Boolean = false): Typeface {
        if (heavy) {
            bold?.let { return it }
            val t = try {
                Typeface.createFromAsset(ctx.assets, "fonts/Estedad-Bold.ttf")
            } catch (e: Exception) {
                Typeface.DEFAULT_BOLD
            }
            bold = t
            return t
        }
        regular?.let { return it }
        val t = try {
            Typeface.createFromAsset(ctx.assets, "fonts/Estedad-Regular.ttf")
        } catch (e: Exception) {
            Typeface.DEFAULT
        }
        regular = t
        return t
    }

    fun dp(ctx: Context, v: Float): Int =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics).toInt()

    fun dpF(ctx: Context, v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, ctx.resources.displayMetrics)

    fun rounded(color: Int, radius: Float, stroke: Int = 0, strokeColor: Int = LINE): GradientDrawable {
        val g = GradientDrawable()
        g.shape = GradientDrawable.RECTANGLE
        g.cornerRadius = radius
        g.setColor(color)
        if (stroke > 0) g.setStroke(stroke, strokeColor)
        return g
    }

    fun gradient(top: Int, bottom: Int, radius: Float): GradientDrawable {
        val g = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(top, bottom))
        g.cornerRadius = radius
        return g
    }

    fun screenBackground(ctx: Context): GradientDrawable =
        GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, intArrayOf(BG1, BG2, BG3))

    fun text(
        ctx: Context,
        text: String,
        sizeSp: Float,
        color: Int = TXT,
        heavy: Boolean = false,
        gravity: Int = Gravity.START or Gravity.CENTER_VERTICAL
    ): TextView {
        val t = TextView(ctx)
        t.text = text
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        t.setTextColor(color)
        t.typeface = font(ctx, heavy)
        t.gravity = gravity
        t.includeFontPadding = false
        return t
    }

    /** کارت پایهٔ رابط */
    fun card(ctx: Context, padDp: Float = 16f): LinearLayout {
        val c = LinearLayout(ctx)
        c.orientation = LinearLayout.VERTICAL
        c.background = rounded(CARD, dpF(ctx, 18f), 1, LINE)
        val p = dp(ctx, padDp)
        c.setPadding(p, p, p, p)
        return c
    }

    /** دکمهٔ اصلی (طلایی) یا ثانویه (شفاف با حاشیه) */
    fun button(ctx: Context, label: String, primary: Boolean = true, small: Boolean = false): TextView {
        val t = TextView(ctx)
        t.text = label
        t.gravity = Gravity.CENTER
        t.typeface = font(ctx, true)
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, if (small) 13f else 15f)
        val h = dp(ctx, if (small) 40f else 50f)
        t.minHeight = h
        t.minimumHeight = h
        val padH = dp(ctx, if (small) 14f else 18f)
        t.setPadding(padH, dp(ctx, 10f), padH, dp(ctx, 10f))
        t.isClickable = true
        t.isFocusable = true
        val radius = dpF(ctx, 15f)
        if (primary) {
            t.setTextColor(INK)
            t.background = RippleDrawable(
                ColorStateList.valueOf(0x33000000),
                gradient(GOLD, GOLD2, radius),
                null
            )
        } else {
            t.setTextColor(TXT)
            val states = StateListDrawable()
            states.addState(intArrayOf(android.R.attr.state_pressed), rounded(0x22FFFFFF, radius, 1, 0x44FFFFFF))
            states.addState(intArrayOf(), rounded(0x0DFFFFFF, radius, 1, LINE))
            t.background = states
        }
        return t
    }

    fun chip(ctx: Context, label: String, color: Int): TextView {
        val t = text(ctx, label, 12f, color, true, Gravity.CENTER)
        val g = rounded(withAlpha(color, 0.12f), dpF(ctx, 20f), 1, withAlpha(color, 0.45f))
        t.background = g
        t.setPadding(dp(ctx, 12f), dp(ctx, 6f), dp(ctx, 12f), dp(ctx, 6f))
        return t
    }


    /** پس‌زمینهٔ دکمهٔ اصلی (برای بازگرداندن حالت بعد از توقف ضبط) */
    fun primaryButtonBg(ctx: Context): android.graphics.drawable.Drawable =
        gradient(GOLD, GOLD2, dpF(ctx, 15f))

    fun withAlpha(color: Int, alpha: Float): Int {
        val a = (Color.alpha(color) * alpha).toInt().coerceIn(0, 255)
        return Color.argb(a, Color.red(color), Color.green(color), Color.blue(color))
    }

    fun divider(ctx: Context): View {
        val v = View(ctx)
        v.setBackgroundColor(0x14FFFFFF)
        val lp = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.max(1, dp(ctx, 0.8f)))
        lp.topMargin = dp(ctx, 10f)
        lp.bottomMargin = dp(ctx, 10f)
        v.layoutParams = lp
        return v
    }

    fun space(ctx: Context, h: Float): View {
        val v = View(ctx)
        v.layoutParams = LinearLayout.LayoutParams(1, dp(ctx, h))
        return v
    }

    fun row(ctx: Context): LinearLayout {
        val r = LinearLayout(ctx)
        r.orientation = LinearLayout.HORIZONTAL
        r.gravity = Gravity.CENTER_VERTICAL
        return r
    }

    fun matchWrap(v: View, weight: Float = 0f, height: Int = ViewGroup.LayoutParams.WRAP_CONTENT) {
        val lp = LinearLayout.LayoutParams(0, height, weight)
        v.layoutParams = lp
    }
}
