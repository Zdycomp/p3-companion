package com.zdycomp.p3companion

import android.animation.ValueAnimator
import android.app.Dialog
import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.InsetDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.text.InputType
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextUtils
import android.text.style.ImageSpan
import android.util.TypedValue
import android.view.Gravity
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.Window
import android.view.animation.PathInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageButton
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.SeekBar
import android.widget.Switch
import android.widget.TextView

/**
 * Colors for one look of the app. The field names are the 2.0 ones, kept as aliases of the
 * [Scheme] roles (DESIGN-SPEC 1.6), so every screen keeps compiling; new code reads [scheme].
 */
data class Palette(
    val bg: Int,
    val card: Int,
    val cardAlt: Int,
    val text: Int,
    val muted: Int,
    val accent: Int,
    val onAccent: Int,
    val good: Int,
    val warn: Int,
    val bad: Int,
    val line: Int,
    val scheme: Scheme,
) {
    val isDark: Boolean get() = scheme.isDark

    companion object {
        fun from(s: Scheme) = Palette(
            bg = s.surface, card = s.surfaceContainerLow, cardAlt = s.surfaceContainerHigh,
            text = s.onSurface, muted = s.onSurfaceVariant, accent = s.primary, onAccent = s.onPrimary,
            good = s.success, warn = s.warning, bad = s.error, line = s.outlineVariant, scheme = s,
        )

        /** The 2.0 violet looks, generated (no Color.parseColor, so they work in JVM tests). */
        val DARK: Palette by lazy { from(Scheme.build(SchemeSource.fromSeed(Accent.VIOLET.seed!!), dark = true)) }
        val LIGHT: Palette by lazy { from(Scheme.build(SchemeSource.fromSeed(Accent.VIOLET.seed!!), dark = false)) }
    }
}

/** Type scale (DESIGN-SPEC 2.1): size sp, line height sp, weight. */
enum class Type(val sp: Float, val line: Float, val weight: Int) {
    DISPLAY_SMALL(36f, 44f, 400),
    HEADLINE_MEDIUM(28f, 36f, 400),
    HEADLINE_SMALL(24f, 32f, 400),
    TITLE_LARGE(22f, 28f, 400),
    TITLE_MEDIUM(16f, 24f, 500),
    TITLE_SMALL(14f, 20f, 500),
    BODY_LARGE(16f, 24f, 400),
    BODY_MEDIUM(14f, 20f, 400),
    BODY_SMALL(12f, 16f, 400),
    LABEL_LARGE(14f, 20f, 500),
    LABEL_MEDIUM(12f, 16f, 500),
    LABEL_SMALL(11f, 16f, 500),
}

enum class Btn { FILLED, TONAL, OUTLINED, TEXT, DANGER, DANGER_FILLED }
enum class IconBtn { STANDARD, FILLED, TONAL, OUTLINED }
enum class Tone { NEUTRAL, GOOD, WARN, BAD, INFO, BUSY }
enum class Haptic { TICK, TOGGLE_ON, TOGGLE_OFF, PRESS, CONFIRM, REJECT, LONG_PRESS, START, END }

/** A top-bar action: an icon button with a spoken name. */
class BarAction(val icon: Int, val description: String, val onClick: () -> Unit)

/** One row in a bottom sheet. */
class SheetItem(
    val label: String,
    val icon: Int? = null,
    val supporting: String? = null,
    val selected: Boolean = false,
    val danger: Boolean = false,
    val onClick: () -> Unit,
)

/**
 * Small toolkit for building screens in code, so the app needs no libraries. Every component follows
 * docs/DESIGN-SPEC.md and takes its colors from [p] (its [Palette.scheme]).
 */
class Ui(
    val context: Context,
    val p: Palette,
    val textScale: Float = 1f,
    val reduceMotion: Boolean = false,
    val haptics: Boolean = true,
) {
    val s: Scheme get() = p.scheme

    fun dp(v: Int): Int = dpf(v.toFloat()).toInt()
    fun dpf(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private fun sp(v: Float): Float =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_SP, v, context.resources.displayMetrics)

    // ---------------- motion ----------------

    val standard = PathInterpolator(0.2f, 0f, 0f, 1f)
    val decelerate = PathInterpolator(0.05f, 0.7f, 0.1f, 1f)
    val accelerate = PathInterpolator(0.3f, 0f, 0.8f, 0.15f)

    /** Duration, shortened to a plain quick fade when motion is reduced. */
    fun ms(full: Long): Long = if (reduceMotion) minOf(full, 100L) else full

    // ---------------- text ----------------

    fun font(weight: Int): Typeface = fonts.getOrPut(weight) { Typeface.create(Typeface.DEFAULT, weight, false) }

    fun style(tv: TextView, t: Type, color: Int? = null): TextView {
        tv.setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(t.sp * textScale))
        tv.typeface = font(t.weight)
        tv.setLineHeight(sp(t.line * textScale).toInt())
        if (color != null) tv.setTextColor(color)
        return tv
    }

    fun text(value: CharSequence, t: Type = Type.BODY_LARGE, color: Int = s.onSurface): TextView =
        style(TextView(context).apply { text = value }, t, color)

    /** Large screen title (kept for older screens; new screens use [screen]). */
    fun title(text: String): TextView = text(text, Type.HEADLINE_MEDIUM).apply {
        setPadding(dp(4), dp(8), dp(4), dp(12))
        isAccessibilityHeading = true
    }

    fun heading(text: String, icon: Int? = null): View {
        val tv = text(text, Type.TITLE_MEDIUM).apply { isAccessibilityHeading = true }
        if (icon == null) return tv.apply { setPadding(0, 0, 0, dp(8)) }
        return row().apply {
            setPadding(0, 0, 0, dp(8))
            addView(this@Ui.icon(icon, s.primary, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(10) })
            addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
    }

    fun body(text: String, muted: Boolean = false, sizeSp: Float = 15f): TextView {
        val t = when {
            sizeSp >= 15f -> Type.BODY_LARGE
            sizeSp >= 13f -> Type.BODY_MEDIUM
            else -> Type.BODY_SMALL
        }
        return text(text, t, if (muted) s.onSurfaceVariant else s.onSurface)
    }

    fun mono(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(s.onSurface)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_PX, sp(12f * textScale))
        setLineHeight(sp(18f * textScale).toInt())
        setTextIsSelectable(true)
    }

    fun sectionLabel(text: String): TextView = text(text, Type.TITLE_SMALL, s.primary).apply {
        setPadding(dp(4), dp(20), dp(4), dp(8))
        isAccessibilityHeading = true
    }

    // ---------------- shapes and layout ----------------

    fun rounded(color: Int, radiusDp: Int = 16, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
        }

    fun oval(color: Int, sizeDp: Int? = null, strokeColor: Int? = null, strokeDp: Int = 1): GradientDrawable =
        GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(color)
            if (strokeColor != null) setStroke(dp(strokeDp), strokeColor)
            if (sizeDp != null) setSize(dp(sizeDp), dp(sizeDp))
        }

    fun column(padDp: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    /** The scrolling body of a screen: 16 dp gutters, room at the bottom. */
    fun page(): LinearLayout = column().apply { setPadding(dp(16), dp(4), dp(16), dp(24)) }

    /** Filled card (surfaceContainerLow, radius 16). */
    fun card(): LinearLayout = column(16).apply {
        background = rounded(s.surfaceContainerLow)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    /** Outlined card for tips and how-tos. */
    fun outlinedCard(): LinearLayout = card().apply { background = rounded(s.surface, 16, s.outlineVariant) }

    /** Tonal card for stat tiles and answers. */
    fun tonalCard(): LinearLayout = card().apply { background = rounded(s.surfaceContainerHigh) }

    /** A card with no padding that holds list rows (Settings groups). */
    fun group(): LinearLayout = column().apply {
        background = rounded(s.surfaceContainerLow)
        clipToOutline = true
        setPadding(0, dp(4), 0, dp(4))
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(4) }
    }

    fun spacer(hDp: Int): View = View(context).apply { layoutParams = LinearLayout.LayoutParams(MATCH, dp(hDp)) }

    fun divider(): View = View(context).apply {
        setBackgroundColor(s.outlineVariant)
        layoutParams = LinearLayout.LayoutParams(MATCH, maxOf(1, dp(1) / 2 + 1)).apply {
            topMargin = dp(8); bottomMargin = dp(8)
        }
    }

    // ---------------- icons ----------------

    fun drawable(res: Int, color: Int, sizeDp: Int? = null): Drawable {
        val d = context.getDrawable(res)!!.mutate()
        d.setTint(color)
        if (sizeDp != null) d.setBounds(0, 0, dp(sizeDp), dp(sizeDp))
        return d
    }

    fun icon(res: Int, color: Int, sizeDp: Int = 24): ImageView = ImageView(context).apply {
        setImageDrawable(drawable(res, color))
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    private fun withIcon(label: CharSequence, icon: Int?, color: Int, sizeDp: Int = 18): CharSequence {
        if (icon == null) return label
        val sb = SpannableStringBuilder()
        sb.append(" ")
        sb.setSpan(ImageSpan(drawable(icon, color, sizeDp), ImageSpan.ALIGN_CENTER), 0, 1, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.append("  ").append(label)
        return sb
    }

    // ---------------- press feedback ----------------

    /** Ripple is in the background; this adds the press scale (0.96 buttons, 0.98 cards). */
    fun pressable(v: View, scale: Float = 0.96f): View {
        if (reduceMotion) return v
        v.setOnTouchListener { view, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> if (view.isEnabled) view.animate().scaleX(scale).scaleY(scale)
                    .setDuration(100).setInterpolator(standard).start()
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> view.animate().scaleX(1f).scaleY(1f)
                    .setDuration(150).setInterpolator(standard).start()
            }
            false
        }
        return v
    }

    fun haptic(v: View?, kind: Haptic) {
        if (!haptics || v == null) return
        val api34 = Build.VERSION.SDK_INT >= 34
        val c = when (kind) {
            Haptic.TICK -> if (api34) HapticFeedbackConstants.SEGMENT_TICK else HapticFeedbackConstants.CLOCK_TICK
            Haptic.TOGGLE_ON -> if (api34) HapticFeedbackConstants.TOGGLE_ON else HapticFeedbackConstants.CLOCK_TICK
            Haptic.TOGGLE_OFF -> if (api34) HapticFeedbackConstants.TOGGLE_OFF else HapticFeedbackConstants.CLOCK_TICK
            Haptic.PRESS -> HapticFeedbackConstants.VIRTUAL_KEY
            Haptic.CONFIRM -> HapticFeedbackConstants.CONFIRM
            Haptic.REJECT -> HapticFeedbackConstants.REJECT
            Haptic.LONG_PRESS -> HapticFeedbackConstants.LONG_PRESS
            Haptic.START -> HapticFeedbackConstants.GESTURE_START
            Haptic.END -> HapticFeedbackConstants.GESTURE_END
        }
        v.performHapticFeedback(c)
    }

    // ---------------- buttons ----------------

    private inner class StyledButton(val kind: Btn, val iconRes: Int?, val label: CharSequence) : Button(context) {
        private var styled = false

        init {
            isAllCaps = false
            stateListAnimator = null
            minHeight = dp(48)
            minimumHeight = dp(48)
            minWidth = dp(48)
            minimumWidth = dp(48)
            gravity = Gravity.CENTER
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
            style(this, Type.LABEL_LARGE)
            val h = if (kind == Btn.TEXT) 12 else if (iconRes != null) 16 else 24
            setPadding(dp(h), 0, dp(h), 0)
            styled = true
            restyle()
        }

        override fun setEnabled(enabled: Boolean) {
            super.setEnabled(enabled)
            if (styled) restyle()
        }

        fun restyle() {
            val on = isEnabled
            val (container, content) = when (kind) {
                Btn.FILLED -> s.primary to s.onPrimary
                Btn.TONAL -> s.secondaryContainer to s.onSecondaryContainer
                Btn.OUTLINED -> Color.TRANSPARENT to s.primary
                Btn.TEXT -> Color.TRANSPARENT to s.primary
                Btn.DANGER -> s.errorContainer to s.onErrorContainer
                Btn.DANGER_FILLED -> s.error to s.onError
            }
            val fill = if (!on && container != Color.TRANSPARENT) withAlpha(s.onSurface, 31) else container
            val fg = if (on) content else withAlpha(s.onSurface, 97)
            val stroke = if (kind == Btn.OUTLINED) (if (on) s.outline else withAlpha(s.onSurface, 31)) else null
            val shape = rounded(fill, 100, stroke)
            val inset = dp(4)
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(content, 31)),
                InsetDrawable(shape, 0, inset, 0, inset),
                InsetDrawable(rounded(Color.WHITE, 100), 0, inset, 0, inset),
            )
            setTextColor(fg)
            text = withIcon(label, iconRes, fg)
        }
    }

    /** Any button kind. [full] makes it fill the card width (only the main action of a card should). */
    fun button(text: String, kind: Btn = Btn.FILLED, icon: Int? = null, full: Boolean = true, onClick: () -> Unit): Button =
        StyledButton(kind, icon, text).apply {
            layoutParams = if (full) LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            else LinearLayout.LayoutParams(WRAP, WRAP)
            setOnClickListener { onClick() }
            pressable(this)
        }

    fun primaryButton(text: String, onClick: () -> Unit): Button = button(text, Btn.FILLED, onClick = onClick)
    fun secondaryButton(text: String, onClick: () -> Unit): Button = button(text, Btn.TONAL, onClick = onClick)
    fun dangerButton(text: String, onClick: () -> Unit): Button = button(text, Btn.DANGER, onClick = onClick)

    /** Secondary actions side by side, wrapping to more lines when they don't fit. */
    fun buttonRow(vararg views: View): Flow = Flow(context, dp(8), dp(0)).apply {
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) }
        views.forEach { v ->
            v.layoutParams = ViewGroup.MarginLayoutParams(WRAP, WRAP)
            addView(v)
        }
    }

    fun iconButton(
        icon: Int,
        description: String,
        kind: IconBtn = IconBtn.STANDARD,
        tint: Int? = null,
        onClick: () -> Unit,
    ): ImageButton = ImageButton(context).apply {
        val (container, content) = when (kind) {
            IconBtn.STANDARD -> Color.TRANSPARENT to s.onSurfaceVariant
            IconBtn.FILLED -> s.primary to s.onPrimary
            IconBtn.TONAL -> s.secondaryContainer to s.onSecondaryContainer
            IconBtn.OUTLINED -> Color.TRANSPARENT to s.onSurfaceVariant
        }
        val fg = tint ?: content
        setImageDrawable(drawable(icon, fg))
        scaleType = ImageView.ScaleType.CENTER_INSIDE
        val inset = dp(4)
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(fg, 31)),
            InsetDrawable(oval(container, strokeColor = if (kind == IconBtn.OUTLINED) s.outline else null), inset),
            InsetDrawable(oval(Color.WHITE), inset),
        )
        setPadding(dp(12), dp(12), dp(12), dp(12))
        contentDescription = description
        tooltipText = description
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        setOnClickListener { onClick() }
        pressable(this)
    }

    // ---------------- chips and choices ----------------

    fun chip(text: String, selected: Boolean, icon: Int? = null, onClick: (TextView) -> Unit): TextView =
        TextView(context).apply {
            style(this, Type.LABEL_LARGE)
            gravity = Gravity.CENTER
            minHeight = dp(48)
            isClickable = true
            paintChip(this, text, selected, icon)
            setOnClickListener { onClick(this) }
            pressable(this)
        }

    fun paintChip(tv: TextView, label: String, selected: Boolean, icon: Int? = null) {
        val fg = if (selected) s.onSecondaryContainer else s.onSurfaceVariant
        val shape = if (selected) rounded(s.secondaryContainer, 8) else rounded(Color.TRANSPARENT, 8, s.outlineVariant)
        val inset = dp(8)
        tv.background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(fg, 31)),
            InsetDrawable(shape, 0, inset, 0, inset),
            InsetDrawable(rounded(Color.WHITE, 8), 0, inset, 0, inset),
        )
        tv.setTextColor(fg)
        tv.text = withIcon(label, if (selected) R.drawable.ic_check else icon, fg)
        val h = if (selected || icon != null) 12 else 16
        tv.setPadding(dp(h), 0, dp(16), 0)
        tv.isSelected = selected
        tv.stateDescription = if (selected) "Selected" else null
    }

    /** Assist chip: suggested actions (quick prompts, quick fixes). */
    fun assistChip(text: String, icon: Int? = null, onClick: () -> Unit): TextView = TextView(context).apply {
        style(this, Type.LABEL_LARGE, s.onSurface)
        gravity = Gravity.CENTER_VERTICAL
        minHeight = dp(48)
        val inset = dp(8)
        background = RippleDrawable(
            ColorStateList.valueOf(withAlpha(s.onSurface, 31)),
            InsetDrawable(rounded(s.surfaceContainerLow, 8, s.outlineVariant), 0, inset, 0, inset),
            InsetDrawable(rounded(Color.WHITE, 8), 0, inset, 0, inset),
        )
        this.text = withIcon(text, icon, s.primary)
        setPadding(dp(if (icon != null) 8 else 16), 0, dp(16), 0)
        setOnClickListener { onClick() }
        pressable(this)
    }

    /** Single choice: a segmented button when it fits, otherwise wrapping filter chips. */
    fun choiceRow(label: String?, options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val wrap = column().apply { setPadding(0, dp(8), 0, dp(4)) }
        if (label != null) wrap.addView(body(label))
        val fits = options.size <= 4 && options.sumOf { it.length } <= 26 && textScale <= 1.15f
        if (fits) {
            wrap.addView(segmented(options, selected, onPick), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        } else {
            val flow = Flow(context, dp(8), 0)
            val chips = mutableListOf<TextView>()
            options.forEachIndexed { i, opt ->
                val c = chip(opt, i == selected) { v ->
                    chips.forEachIndexed { j, other -> paintChip(other, options[j], j == i) }
                    haptic(v, Haptic.TICK)
                    onPick(i)
                }
                chips += c
                flow.addView(c, ViewGroup.MarginLayoutParams(WRAP, WRAP))
            }
            wrap.addView(flow, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return wrap
    }

    /** Segmented button (40 dp visual, 48 dp touch) with a check on the selected segment. */
    fun segmented(options: List<String>, selected: Int, onPick: (Int) -> Unit): LinearLayout {
        val box = row()
        val segs = mutableListOf<TextView>()
        val r = dpf(20f)
        fun paint(sel: Int) {
            segs.forEachIndexed { i, tv ->
                val on = i == sel
                val radii = when {
                    options.size == 1 -> floatArrayOf(r, r, r, r, r, r, r, r)
                    i == 0 -> floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r)
                    i == options.lastIndex -> floatArrayOf(0f, 0f, r, r, r, r, 0f, 0f)
                    else -> FloatArray(8)
                }
                val shape = GradientDrawable().apply {
                    cornerRadii = radii
                    setColor(if (on) s.secondaryContainer else Color.TRANSPARENT)
                    setStroke(dp(1), s.outline)
                }
                val mask = GradientDrawable().apply { cornerRadii = radii; setColor(Color.WHITE) }
                val fg = if (on) s.onSecondaryContainer else s.onSurface
                val inset = dp(4)
                tv.background = RippleDrawable(
                    ColorStateList.valueOf(withAlpha(fg, 31)),
                    InsetDrawable(shape, 0, inset, 0, inset),
                    InsetDrawable(mask, 0, inset, 0, inset),
                )
                tv.setTextColor(fg)
                tv.text = withIcon(options[i], if (on) R.drawable.ic_check else null, fg, 16)
                tv.stateDescription = if (on) "Selected" else null
                tv.isSelected = on
            }
        }
        options.forEachIndexed { i, opt ->
            val tv = TextView(context).apply {
                style(this, Type.LABEL_LARGE)
                gravity = Gravity.CENTER
                minHeight = dp(48)
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                setPadding(dp(8), 0, dp(8), 0)
                contentDescription = opt
                setOnClickListener { paint(i); haptic(this, Haptic.TICK); onPick(i) }
            }
            segs += tv
            box.addView(tv, LinearLayout.LayoutParams(0, WRAP, 1f))
        }
        paint(selected)
        return box
    }

    // ---------------- switches, sliders, inputs ----------------

    private fun styledSwitch(checked: Boolean): Switch = Switch(context).apply {
        val track = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), rounded(s.primary, 16).apply { setSize(dp(52), dp(32)) })
            addState(intArrayOf(), rounded(s.surfaceContainerHighest, 16, s.outline, 2).apply { setSize(dp(52), dp(32)) })
        }
        fun knob(color: Int, d: Int): Drawable = LayerDrawable(arrayOf(oval(color, d))).apply {
            val side = (26 - d) / 2
            val top = (32 - d) / 2
            setLayerInset(0, dp(side), dp(top), dp(side), dp(top))
        }
        val thumb = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_checked), knob(s.onPrimary, 24))
            addState(intArrayOf(), knob(s.outline, 16))
        }
        trackDrawable = track
        thumbDrawable = thumb
        trackTintList = null
        thumbTintList = null
        showText = false
        isChecked = checked
        stateDescription = if (checked) "On" else "Off"
    }

    /** A labelled on/off row. The whole row toggles; the change is reported immediately. */
    fun switchRow(
        label: String,
        hint: String?,
        checked: Boolean,
        icon: Int? = null,
        enabled: Boolean = true,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val sw = styledSwitch(checked)
        sw.isEnabled = enabled
        sw.setOnCheckedChangeListener { v, on ->
            v.stateDescription = if (on) "On" else "Off"
            haptic(v, if (on) Haptic.TOGGLE_ON else Haptic.TOGGLE_OFF)
            onChange(on)
        }
        sw.importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        val row = listRow(label, hint, icon, sw) { if (enabled) sw.toggle() }
        row.isEnabled = enabled
        row.alpha = if (enabled) 1f else 0.5f
        row.accessibilityDelegate = object : View.AccessibilityDelegate() {
            override fun onInitializeAccessibilityNodeInfo(host: View, info: android.view.accessibility.AccessibilityNodeInfo) {
                super.onInitializeAccessibilityNodeInfo(host, info)
                info.className = Switch::class.java.name
                info.isCheckable = true
                info.isChecked = sw.isChecked
            }
        }
        return row
    }

    /** List row (56-72 dp): optional leading icon, title, supporting text, trailing control. */
    fun listRow(
        title: String,
        supporting: String? = null,
        icon: Int? = null,
        trailing: View? = null,
        danger: Boolean = false,
        onClick: (() -> Unit)? = null,
    ): LinearLayout = row().apply {
        minimumHeight = dp(if (supporting != null) 72 else 56)
        setPadding(dp(16), dp(8), dp(12), dp(8))
        if (icon != null) {
            addView(this@Ui.icon(icon, if (danger) s.error else s.onSurfaceVariant), LinearLayout.LayoutParams(dp(24), dp(24)).apply { marginEnd = dp(16) })
        }
        val texts = column()
        texts.addView(text(title, Type.BODY_LARGE, if (danger) s.error else s.onSurface))
        if (supporting != null) texts.addView(text(supporting, Type.BODY_MEDIUM, s.onSurfaceVariant))
        addView(texts, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (trailing != null) addView(trailing, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(12) })
        if (onClick != null) {
            background = RippleDrawable(ColorStateList.valueOf(withAlpha(s.onSurface, 31)), null, ColorDrawable(Color.WHITE))
            setOnClickListener { onClick() }
            isFocusable = true
        }
        isScreenReaderFocusable = true
    }

    /** Discrete slider with tick feedback; [onChange] gets the step index when the finger lifts. */
    fun slider(steps: Int, value: Int, onStep: ((Int) -> Unit)? = null, onChange: (Int) -> Unit): SeekBar =
        SeekBar(context).apply {
            max = steps - 1
            progress = value.coerceIn(0, steps - 1)
            progressTintList = ColorStateList.valueOf(s.primary)
            progressBackgroundTintList = ColorStateList.valueOf(s.secondaryContainer)
            thumb = oval(s.primary, 20)
            thumbOffset = dp(10)
            splitTrack = false
            minHeight = dp(6); maxHeight = dp(6)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(48))
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar, v: Int, fromUser: Boolean) {
                    if (fromUser) { haptic(sb, Haptic.TICK); onStep?.invoke(v) }
                }
                override fun onStartTrackingTouch(sb: SeekBar) {}
                override fun onStopTrackingTouch(sb: SeekBar) { onChange(sb.progress) }
            })
        }

    fun input(value: String, hint: String, onDone: (String) -> Unit): EditText =
        EditText(context).apply {
            setText(value)
            this.hint = hint
            style(this, Type.BODY_LARGE, s.onSurface)
            setHintTextColor(s.onSurfaceVariant)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = fieldBackground()
            setPadding(dp(16), dp(14), dp(16), dp(14))
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onDone(text.toString()) }
            setOnEditorActionListener { _, _, _ -> onDone(text.toString()); clearFocus(); false }
        }

    /** Filled text field: surfaceContainerHighest, a primary underline when focused. */
    fun fieldBackground(): Drawable {
        fun layer(line: Int, h: Int): Drawable {
            val r = dpf(12f)
            val fill = GradientDrawable().apply {
                setColor(s.surfaceContainerHighest)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            val under = GradientDrawable().apply { setColor(line) }
            return LayerDrawable(arrayOf(fill, under)).apply {
                setLayerGravity(1, Gravity.BOTTOM or Gravity.FILL_HORIZONTAL)
                setLayerHeight(1, dp(h).coerceAtLeast(1))
            }
        }
        return StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), layer(s.primary, 2))
            addState(intArrayOf(), layer(s.onSurfaceVariant, 1))
        }
    }

    fun progress(): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(s.primary)
            progressBackgroundTintList = ColorStateList.valueOf(s.secondaryContainer)
            indeterminateTintList = ColorStateList.valueOf(s.primary)
            minHeight = dp(4); maxHeight = dp(4)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(4)).apply { topMargin = dp(10) }
        }

    fun spinner(sizeDp: Int = 20, color: Int = s.primary): ProgressBar = ProgressBar(context).apply {
        isIndeterminate = true
        indeterminateTintList = ColorStateList.valueOf(color)
        layoutParams = LinearLayout.LayoutParams(dp(sizeDp), dp(sizeDp))
    }

    // ---------------- status ----------------

    /** A colored dot followed by a label, for simple status lines. */
    fun statusLine(text: String, color: Int): LinearLayout = row().apply {
        val dot = View(context).apply {
            background = oval(color)
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        addView(dot)
        addView(body(text))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
    }

    fun toneColors(t: Tone): Pair<Int, Int> = when (t) {
        Tone.NEUTRAL -> Color.TRANSPARENT to s.onSurfaceVariant
        Tone.GOOD -> s.successContainer to s.onSuccessContainer
        Tone.WARN -> s.warningContainer to s.onWarningContainer
        Tone.BAD -> s.errorContainer to s.onErrorContainer
        Tone.INFO -> s.primaryContainer to s.onPrimaryContainer
        Tone.BUSY -> s.secondaryContainer to s.onSecondaryContainer
    }

    /** Pill-shaped status chip with a dot, check or spinner (DESIGN-SPEC 3.13). */
    fun statusChip(text: String, tone: Tone, pulse: Boolean = false): LinearLayout = row().apply {
        val (bg, fg) = toneColors(tone)
        background = if (tone == Tone.NEUTRAL) rounded(Color.TRANSPARENT, 100, s.outline) else rounded(bg, 100)
        setPadding(dp(10), dp(6), dp(14), dp(6))
        val lead: View = when (tone) {
            Tone.GOOD -> icon(R.drawable.ic_check, fg, 16)
            Tone.BUSY -> spinner(14, fg)
            else -> View(context).apply {
                background = oval(fg)
                layoutParams = LinearLayout.LayoutParams(dp(8), dp(8))
                if (pulse && !reduceMotion) {
                    ValueAnimator.ofFloat(1f, 0.3f).apply {
                        duration = 800
                        repeatMode = ValueAnimator.REVERSE
                        repeatCount = ValueAnimator.INFINITE
                        addUpdateListener { a -> alpha = a.animatedValue as Float }
                        start()
                    }
                }
            }
        }
        addView(lead, (lead.layoutParams as? LinearLayout.LayoutParams ?: LinearLayout.LayoutParams(dp(16), dp(16))).apply { marginEnd = dp(8) })
        addView(text(text, Type.LABEL_LARGE, fg))
        accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
        layoutParams = LinearLayout.LayoutParams(WRAP, WRAP)
    }

    /** One-line banner with an icon (errors, success, tips) and an optional text action. */
    fun banner(text: String, tone: Tone, actionText: String? = null, onAction: (() -> Unit)? = null): LinearLayout {
        val (bg, fg) = toneColors(tone)
        val iconRes = when (tone) {
            Tone.BAD -> R.drawable.ic_error
            Tone.WARN -> R.drawable.ic_warning
            Tone.GOOD -> R.drawable.ic_check_circle
            else -> R.drawable.ic_info
        }
        return row().apply {
            background = rounded(if (tone == Tone.NEUTRAL) s.surfaceContainerHigh else bg, 12)
            setPadding(dp(16), dp(12), dp(8), dp(12))
            gravity = Gravity.CENTER_VERTICAL
            addView(icon(iconRes, fg, 20), LinearLayout.LayoutParams(dp(20), dp(20)).apply { marginEnd = dp(12) })
            addView(text(text, Type.BODY_MEDIUM, fg), LinearLayout.LayoutParams(0, WRAP, 1f))
            if (actionText != null && onAction != null) {
                addView(TextView(context).apply {
                    style(this, Type.LABEL_LARGE, fg)
                    this.text = actionText
                    minHeight = dp(48)
                    gravity = Gravity.CENTER
                    setPadding(dp(12), 0, dp(12), 0)
                    background = RippleDrawable(ColorStateList.valueOf(withAlpha(fg, 31)), null, rounded(Color.WHITE, 100))
                    setOnClickListener { onAction() }
                })
            }
            accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
        }
    }

    /** Centred empty state: icon in a circle, title, text, one action (DESIGN-SPEC 3.12). */
    fun emptyState(icon: Int, title: String, body: String, action: String? = null, onAction: (() -> Unit)? = null): LinearLayout =
        column().apply {
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(24), dp(32), dp(24), dp(24))
            val circle = FrameLayout(context).apply {
                background = oval(s.surfaceContainerHigh)
                addView(this@Ui.icon(icon, s.onSurfaceVariant, 48), FrameLayout.LayoutParams(dp(48), dp(48), Gravity.CENTER))
            }
            addView(circle, LinearLayout.LayoutParams(dp(96), dp(96)).apply { bottomMargin = dp(16) })
            addView(text(title, Type.HEADLINE_SMALL).apply { gravity = Gravity.CENTER; isAccessibilityHeading = true })
            addView(text(body, Type.BODY_MEDIUM, s.onSurfaceVariant).apply {
                gravity = Gravity.CENTER; maxLines = 4; setPadding(0, dp(8), 0, 0)
            })
            if (action != null && onAction != null) {
                addView(button(action, Btn.FILLED, full = false, onClick = onAction), LinearLayout.LayoutParams(WRAP, WRAP).apply { topMargin = dp(16) })
            }
        }

    // ---------------- screens ----------------

    /**
     * A screen: a pinned top app bar (64 dp) over a scrolling [body]. The bar takes the
     * surfaceContainerHigh color once content scrolls under it. Scroll position is remembered per
     * [key], so a refresh (or a theme change) doesn't jump back to the top.
     */
    fun screen(
        title: String,
        body: View,
        back: (() -> Unit)? = null,
        actions: List<BarAction> = emptyList(),
        scroll: Boolean = true,
        key: String = title,
    ): LinearLayout {
        val bar = topBar(title, back, actions)
        val root = column()
        root.addView(bar, LinearLayout.LayoutParams(MATCH, dp(64)))
        if (!scroll) {
            root.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f))
            return root
        }
        val sv = ScrollView(context).apply {
            isFillViewport = true
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_IF_CONTENT_SCROLLS
            addView(body, FrameLayout.LayoutParams(MATCH, WRAP))
        }
        var raised = false
        fun lift(up: Boolean, animate: Boolean) {
            if (up == raised && animate) return
            raised = up
            val from = if (up) s.surface else s.surfaceContainerHigh
            val to = if (up) s.surfaceContainerHigh else s.surface
            if (!animate || reduceMotion) { bar.setBackgroundColor(to); return }
            ValueAnimator.ofArgb(from, to).apply {
                duration = 150
                addUpdateListener { bar.setBackgroundColor(it.animatedValue as Int) }
                start()
            }
        }
        sv.setOnScrollChangeListener { _, _, y, _, _ ->
            scrollMemory[key] = y
            lift(y > 0, true)
        }
        val saved = scrollMemory[key] ?: 0
        if (saved > 0) {
            sv.viewTreeObserver.addOnGlobalLayoutListener(object : android.view.ViewTreeObserver.OnGlobalLayoutListener {
                override fun onGlobalLayout() {
                    sv.viewTreeObserver.removeOnGlobalLayoutListener(this)
                    sv.scrollTo(0, saved)
                    lift(sv.scrollY > 0, false)
                }
            })
        }
        root.addView(sv, LinearLayout.LayoutParams(MATCH, 0, 1f))
        return root
    }

    fun topBar(title: String, back: (() -> Unit)?, actions: List<BarAction>): LinearLayout = row().apply {
        setBackgroundColor(s.surface)
        setPadding(dp(if (back != null) 4 else 16), 0, dp(4), 0)
        if (back != null) addView(iconButton(R.drawable.ic_arrow_back, "Back", onClick = back))
        addView(text(title, Type.TITLE_LARGE).apply {
            isAccessibilityHeading = true
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(if (back != null) 4 else 0), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        actions.forEach { a -> addView(iconButton(a.icon, a.description, onClick = a.onClick)) }
    }

    // ---------------- dialogs and sheets ----------------

    private fun baseDialog(): Dialog = Dialog(context).apply {
        requestWindowFeature(Window.FEATURE_NO_TITLE)
        window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        window?.setDimAmount(if (s.amoled) 0.7f else if (s.isDark) 0.6f else 0.32f)
    }

    /**
     * Basic dialog: optional icon, title, text, a custom [view], text buttons on the right.
     * [onConfirm] returning false keeps the dialog open (for validation).
     */
    fun dialog(
        title: String,
        message: String? = null,
        icon: Int? = null,
        view: View? = null,
        confirm: String? = "OK",
        dismiss: String? = null,
        destructive: Boolean = false,
        onDismiss: (() -> Unit)? = null,
        onConfirm: (() -> Boolean)? = null,
    ): Dialog {
        val d = baseDialog()
        val box = column().apply {
            background = rounded(s.surfaceContainerHigh, 28)
            setPadding(dp(24), dp(24), dp(24), dp(12))
        }
        if (icon != null) box.addView(this@Ui.icon(icon, s.secondary, 24), LinearLayout.LayoutParams(dp(24), dp(24)).apply {
            gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(16)
        })
        box.addView(text(title, Type.HEADLINE_SMALL).apply {
            if (icon != null) gravity = Gravity.CENTER_HORIZONTAL
            isAccessibilityHeading = true
        })
        if (message != null) box.addView(text(message, Type.BODY_MEDIUM, s.onSurfaceVariant).apply { setPadding(0, dp(16), 0, 0) })
        if (view != null) box.addView(view, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(16) })
        val buttons = row().apply { gravity = Gravity.END or Gravity.CENTER_VERTICAL; setPadding(0, dp(16), 0, 0) }
        if (dismiss != null) buttons.addView(button(dismiss, Btn.TEXT, full = false) { d.dismiss(); onDismiss?.invoke() })
        if (confirm != null) {
            val b = button(confirm, Btn.TEXT, full = false) {
                val close = onConfirm?.invoke() ?: true
                if (close) d.dismiss()
            }
            if (destructive) b.setTextColor(s.error)
            buttons.addView(b)
        }
        box.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP))
        val scroller = ScrollView(context).apply { addView(box) }
        d.setContentView(scroller)
        d.setOnCancelListener { onDismiss?.invoke() }
        val w = minOf(context.resources.displayMetrics.widthPixels - dp(48), dp(560))
        d.window?.setLayout(w, ViewGroup.LayoutParams.WRAP_CONTENT)
        d.show()
        return d
    }

    /** Bottom sheet with a drag handle and a list of rows; tap outside or pick a row to close. */
    fun sheet(title: String?, items: List<SheetItem>, extra: View? = null): Dialog {
        val d = baseDialog()
        val r = dpf(28f)
        val box = column().apply {
            background = GradientDrawable().apply {
                setColor(s.surfaceContainerLow)
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(0, dp(16), 0, dp(16))
        }
        box.addView(View(context).apply { background = rounded(withAlpha(s.onSurfaceVariant, 102), 2) },
            LinearLayout.LayoutParams(dp(32), dp(4)).apply { gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(12) })
        if (title != null) box.addView(text(title, Type.TITLE_MEDIUM).apply {
            setPadding(dp(24), dp(4), dp(24), dp(8)); isAccessibilityHeading = true
        })
        val list = column()
        items.forEach { item ->
            val trailing = if (item.selected) icon(R.drawable.ic_check, s.primary) else null
            list.addView(listRow(item.label, item.supporting, item.icon, trailing, item.danger) { d.dismiss(); item.onClick() }.apply {
                setPadding(dp(24), dp(8), dp(24), dp(8))
                if (item.selected) stateDescription = "Selected"
            })
        }
        if (extra != null) list.addView(extra)
        val sv = ScrollView(context).apply { addView(list) }
        val maxH = (context.resources.displayMetrics.heightPixels * 0.7f).toInt()
        box.addView(sv, LinearLayout.LayoutParams(MATCH, WRAP))
        d.setContentView(box)
        d.setCanceledOnTouchOutside(true)
        d.window?.apply {
            setGravity(Gravity.BOTTOM)
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        box.post {
            if (sv.height > maxH) sv.layoutParams = (sv.layoutParams as LinearLayout.LayoutParams).apply { height = maxH }
        }
        if (!reduceMotion) {
            box.translationY = dpf(400f)
            box.post { box.animate().translationY(0f).setDuration(300).setInterpolator(decelerate).start() }
        }
        d.show()
        return d
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        /** Scroll positions per screen, kept across refreshes and theme changes. */
        val scrollMemory = HashMap<String, Int>()
        private val fonts = HashMap<Int, Typeface>()

        fun withAlpha(color: Int, alpha: Int): Int = Tonal.withAlpha(color, alpha)
    }
}

/** Lays children left to right and wraps to a new line when they don't fit (chips, button rows). */
class Flow(context: Context, private val hGap: Int, private val vGap: Int) : ViewGroup(context) {
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val maxW = MeasureSpec.getSize(widthMeasureSpec) - paddingLeft - paddingRight
        var x = 0
        var y = 0
        var lineH = 0
        var widest = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            measureChild(c, MeasureSpec.makeMeasureSpec(maxW, MeasureSpec.AT_MOST), heightMeasureSpec)
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            x += c.measuredWidth + hGap
            widest = maxOf(widest, x - hGap)
            lineH = maxOf(lineH, c.measuredHeight)
        }
        val w = if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.EXACTLY) MeasureSpec.getSize(widthMeasureSpec)
        else widest + paddingLeft + paddingRight
        setMeasuredDimension(w, y + lineH + paddingTop + paddingBottom)
    }

    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val maxW = r - l - paddingLeft - paddingRight
        val rtl = layoutDirection == LAYOUT_DIRECTION_RTL
        var x = 0
        var y = paddingTop
        var lineH = 0
        for (i in 0 until childCount) {
            val c = getChildAt(i)
            if (c.visibility == GONE) continue
            if (x > 0 && x + c.measuredWidth > maxW) { x = 0; y += lineH + vGap; lineH = 0 }
            val left = if (rtl) r - l - paddingRight - x - c.measuredWidth else paddingLeft + x
            c.layout(left, y, left + c.measuredWidth, y + c.measuredHeight)
            x += c.measuredWidth + hGap
            lineH = maxOf(lineH, c.measuredHeight)
        }
    }

    override fun generateDefaultLayoutParams(): LayoutParams = MarginLayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT)
    override fun generateLayoutParams(p: LayoutParams?): LayoutParams = MarginLayoutParams(p)
    override fun checkLayoutParams(p: LayoutParams?) = p is MarginLayoutParams
}

/** One screen of the app. Screens are rebuilt whenever they're shown, so they always show fresh data. */
interface Screen {
    val label: String
    fun build(): View
    fun onHidden() {}
    /** Handle the back gesture inside the screen (for example leaving selection); true when used. */
    fun onBack(): Boolean = false
}
