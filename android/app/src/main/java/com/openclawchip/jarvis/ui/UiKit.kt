package com.openclawchip.jarvis.ui

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView

/** Shared native surfaces and typography, sized in dp/sp for every screen. */
object UiKit {
    val background = Color.rgb(9, 11, 14)
    val surface = Color.rgb(19, 23, 28)
    val border = Color.rgb(38, 45, 53)
    val ink = Color.rgb(241, 234, 218)
    val muted = Color.rgb(148, 158, 170)
    val amber = Color.rgb(234, 185, 99)
    val mint = Color.rgb(151, 211, 181)
    fun Context.dp(value: Int) = (value * resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int = surface, radius: Int = 24, stroke: Int = border) = GradientDrawable().apply {
        setColor(color); cornerRadius = radius.toFloat(); setStroke(1, stroke)
    }
    fun Context.label(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(this).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create("sans-serif", if (bold) Typeface.BOLD else Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    fun Context.button(value: String, primary: Boolean = false, onClick: () -> Unit) = Button(this).apply {
        text = value; textSize = 14f; isAllCaps = false; minHeight = dp(52)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) UiKit.background else ink)
        background = shape(if (primary) amber else surface, dp(18), if (primary) amber else border)
        backgroundTintList = ColorStateList.valueOf(if (primary) amber else surface)
        stateListAnimator = null
        setPadding(dp(16), dp(8), dp(16), dp(8)); setOnClickListener { onClick() }
    }
    fun Context.column(padding: Int = 0) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; setPadding(dp(padding), dp(padding), dp(padding), dp(padding))
    }
    fun Context.row() = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun LinearLayout.space(height: Int) = addView(View(context), LinearLayout.LayoutParams(1, context.dp(height)))
    fun TextView.update(value: String) { if (text.toString() != value) text = value }
}
