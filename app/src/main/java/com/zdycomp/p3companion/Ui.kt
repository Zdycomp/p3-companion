package com.zdycomp.p3companion

import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.Switch
import android.widget.TextView

/** Colors for one look of the app. */
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
) {
    companion object {
        val DARK = Palette(
            bg = Color.parseColor("#0E1116"),
            card = Color.parseColor("#171B22"),
            cardAlt = Color.parseColor("#1F2530"),
            text = Color.parseColor("#E8EAED"),
            muted = Color.parseColor("#9AA0A6"),
            accent = Color.parseColor("#8B5CF6"),
            onAccent = Color.WHITE,
            good = Color.parseColor("#34D399"),
            warn = Color.parseColor("#FBBF24"),
            bad = Color.parseColor("#F87171"),
            line = Color.parseColor("#2A303B"),
        )
        val LIGHT = Palette(
            bg = Color.parseColor("#F5F5F7"),
            card = Color.WHITE,
            cardAlt = Color.parseColor("#EEEAF8"),
            text = Color.parseColor("#14161A"),
            muted = Color.parseColor("#5F6368"),
            accent = Color.parseColor("#6D28D9"),
            onAccent = Color.WHITE,
            good = Color.parseColor("#059669"),
            warn = Color.parseColor("#B45309"),
            bad = Color.parseColor("#DC2626"),
            line = Color.parseColor("#DADCE0"),
        )

        fun resolve(context: Context, theme: Prefs.Theme): Palette = when (theme) {
            Prefs.Theme.DARK -> DARK
            Prefs.Theme.LIGHT -> LIGHT
            Prefs.Theme.SYSTEM -> {
                val night = context.resources.configuration.uiMode and
                    Configuration.UI_MODE_NIGHT_MASK
                if (night == Configuration.UI_MODE_NIGHT_YES) DARK else LIGHT
            }
        }
    }
}

/** Small toolkit for building screens in code, so the app needs no libraries. */
class Ui(val context: Context, val p: Palette) {

    fun dp(v: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, v.toFloat(), context.resources.displayMetrics
    ).toInt()

    fun rounded(color: Int, radiusDp: Int = 16, strokeColor: Int? = null): GradientDrawable =
        GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radiusDp).toFloat()
            if (strokeColor != null) setStroke(dp(1), strokeColor)
        }

    fun column(padDp: Int = 0): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(padDp), dp(padDp), dp(padDp), dp(padDp))
    }

    fun row(): LinearLayout = LinearLayout(context).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
    }

    fun card(): LinearLayout = column(16).apply {
        background = rounded(p.card)
        layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(12) }
    }

    fun title(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 24f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(dp(4), dp(8), dp(4), dp(12))
    }

    fun heading(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(p.text)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 17f)
        typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
        setPadding(0, 0, 0, dp(6))
    }

    fun body(text: String, muted: Boolean = false, sizeSp: Float = 15f): TextView =
        TextView(context).apply {
            this.text = text
            setTextColor(if (muted) p.muted else p.text)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
            setLineSpacing(0f, 1.15f)
        }

    fun mono(text: String): TextView = TextView(context).apply {
        this.text = text
        setTextColor(p.text)
        typeface = Typeface.MONOSPACE
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
        setTextIsSelectable(true)
    }

    fun primaryButton(text: String, onClick: () -> Unit): Button =
        button(text, p.accent, p.onAccent, onClick)

    fun secondaryButton(text: String, onClick: () -> Unit): Button =
        button(text, p.cardAlt, p.text, onClick)

    fun dangerButton(text: String, onClick: () -> Unit): Button =
        button(text, p.cardAlt, p.bad, onClick)

    private fun button(text: String, bg: Int, fg: Int, onClick: () -> Unit): Button =
        Button(context).apply {
            this.text = text
            isAllCaps = false
            setTextColor(fg)
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
            typeface = Typeface.create("sans-serif-medium", Typeface.NORMAL)
            stateListAnimator = null
            minHeight = dp(48)
            background = RippleDrawable(
                ColorStateList.valueOf(withAlpha(fg, 60)), rounded(bg, 14), null
            )
            setPadding(dp(16), dp(10), dp(16), dp(10))
            layoutParams = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) }
            setOnClickListener { onClick() }
        }

    /** A labelled on/off row. The change is reported immediately. */
    fun switchRow(
        label: String,
        hint: String?,
        checked: Boolean,
        onChange: (Boolean) -> Unit,
    ): LinearLayout {
        val sw = Switch(context).apply {
            isChecked = checked
            thumbTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(p.accent, p.muted)
            )
            trackTintList = ColorStateList(
                arrayOf(intArrayOf(android.R.attr.state_checked), intArrayOf()),
                intArrayOf(withAlpha(p.accent, 110), withAlpha(p.muted, 80))
            )
            setOnCheckedChangeListener { _, v -> onChange(v) }
        }
        val texts = column().apply {
            addView(body(label))
            if (hint != null) addView(body(hint, muted = true, sizeSp = 13f))
            layoutParams = LinearLayout.LayoutParams(0, WRAP, 1f)
        }
        return row().apply {
            setPadding(0, dp(8), 0, dp(8))
            addView(texts)
            addView(sw)
            setOnClickListener { sw.toggle() }
        }
    }

    /** A row of choices where exactly one is selected. */
    fun choiceRow(
        label: String,
        options: List<String>,
        selected: Int,
        onPick: (Int) -> Unit,
    ): LinearLayout {
        val wrap = column().apply { setPadding(0, dp(8), 0, dp(8)) }
        wrap.addView(body(label))
        val chips = row().apply { setPadding(0, dp(6), 0, 0) }
        val views = mutableListOf<TextView>()
        fun paint(sel: Int) {
            views.forEachIndexed { i, v ->
                val on = i == sel
                v.background = rounded(if (on) p.accent else p.cardAlt, 20)
                v.setTextColor(if (on) p.onAccent else p.text)
            }
        }
        options.forEachIndexed { i, opt ->
            val chip = TextView(context).apply {
                text = opt
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 14f)
                setPadding(dp(14), dp(8), dp(14), dp(8))
                layoutParams = LinearLayout.LayoutParams(WRAP, WRAP).apply { rightMargin = dp(8) }
                setOnClickListener { paint(i); onPick(i) }
            }
            views += chip
            chips.addView(chip)
        }
        paint(selected)
        // Scrolls sideways when the choices don't fit on a narrow phone.
        wrap.addView(android.widget.HorizontalScrollView(context).apply {
            isHorizontalScrollBarEnabled = false
            addView(chips)
        })
        return wrap
    }

    fun input(value: String, hint: String, onDone: (String) -> Unit): EditText =
        EditText(context).apply {
            setText(value)
            this.hint = hint
            setHintTextColor(p.muted)
            setTextColor(p.text)
            isSingleLine = true
            inputType = InputType.TYPE_CLASS_TEXT
            background = rounded(p.cardAlt, 12)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setOnFocusChangeListener { _, hasFocus -> if (!hasFocus) onDone(text.toString()) }
            setOnEditorActionListener { _, _, _ -> onDone(text.toString()); clearFocus(); false }
        }

    fun progress(): ProgressBar =
        ProgressBar(context, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            progressTintList = ColorStateList.valueOf(p.accent)
            progressBackgroundTintList = ColorStateList.valueOf(p.cardAlt)
            layoutParams = LinearLayout.LayoutParams(MATCH, dp(8)).apply { topMargin = dp(10) }
        }

    fun divider(): View = View(context).apply {
        setBackgroundColor(p.line)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply {
            topMargin = dp(6); bottomMargin = dp(6)
        }
    }

    /** A colored dot followed by a label, for connection status. */
    fun statusLine(text: String, color: Int): LinearLayout = row().apply {
        val dot = View(context).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(color)
            }
            layoutParams = LinearLayout.LayoutParams(dp(10), dp(10)).apply { rightMargin = dp(10) }
        }
        addView(dot)
        addView(body(text))
    }

    companion object {
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun withAlpha(color: Int, alpha: Int): Int =
            Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))
    }
}

/** One screen of the app. Screens are rebuilt whenever they're shown, so they always show fresh data. */
interface Screen {
    val label: String
    fun build(): View
    fun onHidden() {}
}
