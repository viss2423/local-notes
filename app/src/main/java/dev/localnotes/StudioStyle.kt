package dev.localnotes

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.*
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.view.Gravity
import android.view.View
import android.widget.*

/** Small native view toolkit: neutral palette (light and dark), hairline lists, 48dp+ tap targets. */
class StudioStyle(val context: Context) {
    val dark = context.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
    val paper = if (dark) Color.rgb(17, 17, 17) else Color.rgb(247, 247, 245)
    val surface = if (dark) Color.rgb(28, 28, 27) else Color.WHITE
    val ink = if (dark) Color.rgb(237, 237, 236) else Color.rgb(22, 22, 22)
    val muted = if (dark) Color.rgb(154, 154, 150) else Color.rgb(110, 110, 106)
    val line = if (dark) Color.rgb(46, 46, 44) else Color.rgb(228, 228, 224)
    val red = if (dark) Color.rgb(255, 99, 105) else Color.rgb(217, 48, 37)
    val onInk = paper
    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun shape(color: Int, radius: Int = 12, border: Int? = null) = GradientDrawable().apply {
        setColor(color); cornerRadius = dp(radius).toFloat()
        border?.let { setStroke(dp(1), it) }
    }
    fun circle(color: Int, border: Int? = null, width: Int = 1) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color); border?.let { setStroke(dp(width), it) }
    }
    fun ripple(base: android.graphics.drawable.Drawable, mask: android.graphics.drawable.Drawable? = null) =
        RippleDrawable(ColorStateList.valueOf(if (dark) 0x33FFFFFF else 0x1A000000), base, mask)
    fun ripple(color: Int, radius: Int = 12) = ripple(shape(color, radius), shape(Color.WHITE, radius))
    fun column() = LinearLayout(context).apply { orientation = LinearLayout.VERTICAL }
    fun row() = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
    fun text(value: String, size: Float = 15f, color: Int = ink, bold: Boolean = false) = TextView(context).apply {
        text = value; textSize = size; setTextColor(color)
        typeface = Typeface.create(if (bold) "sans-serif-medium" else "sans-serif", Typeface.NORMAL)
        setLineSpacing(dp(3).toFloat(), 1f)
    }
    /** Small uppercase section label. */
    fun label(value: String) = text(value.uppercase(), 12f, muted, true).apply { letterSpacing = .08f }
    fun numbers(value: String, size: Float) = text(value, size, ink).apply {
        typeface = Typeface.create("sans-serif-light", Typeface.NORMAL); fontFeatureSettings = "tnum"
    }
    fun gap(parent: LinearLayout, height: Int) { parent.addView(View(context), LinearLayout.LayoutParams(1, dp(height))) }
    fun divider(parent: LinearLayout) { parent.addView(View(context).apply { setBackgroundColor(line) }, LinearLayout.LayoutParams(-1, dp(1))) }
    fun add(parent: LinearLayout, child: View, top: Int = 0, bottom: Int = 0) {
        parent.addView(child, LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(top); bottomMargin = dp(bottom) })
    }
    /** Primary = filled ink; secondary = hairline outline. */
    fun button(label: String, primary: Boolean = true, enabled: Boolean = true, action: () -> Unit): Button = Button(context).apply {
        text = label; textSize = 15f; isAllCaps = false
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setTextColor(if (primary) onInk else ink)
        background = if (primary) ripple(ink) else ripple(shape(Color.TRANSPARENT, 12, line), shape(Color.WHITE, 12))
        minHeight = dp(50); minimumHeight = dp(50); minWidth = dp(48)
        setPadding(dp(16), dp(12), dp(16), dp(12))
        stateListAnimator = null
        isEnabled = enabled; alpha = if (enabled) 1f else .4f
        setOnClickListener { action() }
    }
    fun iconButton(kind: String, label: String, color: Int = ink, action: () -> Unit): FrameLayout = FrameLayout(context).apply {
        minimumWidth = dp(48); minimumHeight = dp(48)
        background = ripple(Color.TRANSPARENT, 24)
        contentDescription = label; isFocusable = true
        addView(StudioIcon(context, kind, color), FrameLayout.LayoutParams(dp(22), dp(22), Gravity.CENTER))
        setOnClickListener { action() }
    }
    /** Large round transport control with a caption underneath. */
    fun transport(kind: String, label: String, size: Int, fill: Int, border: Int?, iconColor: Int, action: () -> Unit): LinearLayout {
        val box = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        val button = FrameLayout(context).apply {
            background = ripple(circle(fill, border, 2), circle(Color.WHITE))
            contentDescription = label; isFocusable = true
            addView(StudioIcon(context, kind, iconColor), FrameLayout.LayoutParams(dp(size * 2 / 5), dp(size * 2 / 5), Gravity.CENTER))
            setOnClickListener { action() }
        }
        box.addView(button, LinearLayout.LayoutParams(dp(size), dp(size)))
        box.addView(text(label, 13f, muted).apply { gravity = Gravity.CENTER; importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
            LinearLayout.LayoutParams(-2, -2).apply { topMargin = dp(8) })
        return box
    }
}

class StudioIcon(context: Context, private val kind: String, private val color: Int) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = this@StudioIcon.color; strokeWidth = 1.8f; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; style = Paint.Style.STROKE }
    init { importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        canvas.save(); canvas.scale(width / 24f, height / 24f)
        fun line(x: Float, y: Float, x2: Float, y2: Float) = canvas.drawLine(x, y, x2, y2, paint)
        fun filled(block: () -> Unit) { paint.style = Paint.Style.FILL; block(); paint.style = Paint.Style.STROKE }
        when (kind) {
            "mic" -> {
                canvas.drawRoundRect(8f, 2f, 16f, 15f, 4f, 4f, paint)
                canvas.drawArc(5f, 5f, 19f, 19f, 0f, 180f, false, paint)
                line(12f, 19f, 12f, 22f); line(9f, 22f, 15f, 22f)
            }
            "library" -> {
                canvas.drawRoundRect(4f, 3f, 20f, 21f, 3f, 3f, paint)
                line(8f, 8f, 16f, 8f); line(8f, 12f, 16f, 12f); line(8f, 16f, 13f, 16f)
            }
            "setup" -> {
                line(3f, 6f, 21f, 6f); line(3f, 12f, 21f, 12f); line(3f, 18f, 21f, 18f)
                for ((x, y) in listOf(8f to 6f, 16f to 12f, 10f to 18f)) filled { canvas.drawCircle(x, y, 3f, paint) }
            }
            "record" -> filled { canvas.drawCircle(12f, 12f, 10f, paint) }
            "stop" -> filled { canvas.drawRoundRect(4f, 4f, 20f, 20f, 3f, 3f, paint) }
            "pause" -> filled { canvas.drawRoundRect(5f, 3f, 10f, 21f, 1.5f, 1.5f, paint); canvas.drawRoundRect(14f, 3f, 19f, 21f, 1.5f, 1.5f, paint) }
            "play" -> filled { canvas.drawPath(Path().apply { moveTo(6f, 3f); lineTo(21f, 12f); lineTo(6f, 21f); close() }, paint) }
            "arrow" -> { line(9f, 5f, 16f, 12f); line(16f, 12f, 9f, 19f) }
            "back" -> { line(5f, 12f, 19f, 12f); line(10f, 7f, 5f, 12f); line(10f, 17f, 5f, 12f) }
            "check" -> { line(5f, 12f, 10f, 17f); line(10f, 17f, 20f, 6f) }
            "edit" -> {
                val path = Path().apply { moveTo(4f, 20f); lineTo(5f, 14f); lineTo(16f, 3f); lineTo(21f, 8f); lineTo(10f, 19f); close() }
                canvas.drawPath(path, paint); line(14f, 5f, 19f, 10f)
            }
        }
        canvas.restore()
    }
}
