package org.anthrobios.vespasim.ui

import android.app.Activity
import android.content.Context
import android.content.res.ColorStateList
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.LocaleList
import android.view.View
import android.view.ViewGroup
import android.view.WindowInsetsController
import android.widget.Button
import android.widget.CompoundButton
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import org.anthrobios.vespasim.R
import java.util.Locale
import java.util.WeakHashMap
import kotlin.math.roundToInt

/** Presentation preferences only. This object never starts, stops or binds a radio service. */
object Appearance {
    const val PREFERENCES = "appearance"
    const val THEME_KEY = "theme"
    const val LANGUAGE_KEY = "language"
    val themeChoices: List<String> = listOf("system", "light", "dark")
    val languageChoices: List<String> = listOf("system", "en", "nl", "fr")

    data class Colors(
        val page: Int, val surface: Int, val paper: Int, val subtle: Int,
        val hover: Int, val selected: Int, val text: Int, val muted: Int,
        val accent: Int, val accentHover: Int, val onAccent: Int,
        val accentSubtle: Int, val accentText: Int, val border: Int, val rule: Int,
        val chrome: Int, val success: Int, val successBg: Int,
        val warning: Int, val warningBg: Int, val danger: Int, val dangerBg: Int,
        val focus: Int,
    )

    private fun hex(value: String): Int = (0xff000000L or value.toLong(16)).toInt()

    /** Exact portable AnthroBios Nexus 1.0.0 semantic roles, based on core v2.15. */
    fun palette(dark: Boolean): Colors = if (dark) Colors(
        page = hex("191919"), surface = hex("222222"), paper = hex("1d1d1d"),
        subtle = hex("2c2c2c"), hover = hex("2f2f2f"), selected = hex("383838"),
        text = hex("c2c2b0"), muted = hex("a5a598"), accent = hex("c9a554"),
        accentHover = hex("dcc07c"), onAccent = hex("191919"),
        accentSubtle = hex("322a19"), accentText = hex("dcc07c"),
        border = hex("6e6e66"), rule = hex("383838"), chrome = hex("161616"),
        success = hex("8fbf8f"), successBg = hex("1f2b20"),
        warning = hex("e7ac75"), warningBg = hex("302818"),
        danger = hex("e5aa97"), dangerBg = hex("332018"), focus = hex("dcc07c"),
    ) else Colors(
        page = hex("f3f1e9"), surface = hex("fffef9"), paper = hex("faf9f3"),
        subtle = hex("e9e7dd"), hover = hex("e5e2d6"), selected = hex("dbd7c9"),
        text = hex("262621"), muted = hex("57574e"), accent = hex("795811"),
        accentHover = hex("5f440a"), onAccent = hex("ffffff"),
        accentSubtle = hex("f1e5c6"), accentText = hex("674a0d"),
        border = hex("77766c"), rule = hex("c7c5b9"), chrome = hex("e9e7dd"),
        success = hex("315e36"), successBg = hex("e4f0e1"),
        warning = hex("754010"), warningBg = hex("f8e8d8"),
        danger = hex("873d30"), dangerBg = hex("f7e4de"), focus = hex("795811"),
    )

    private val appliedKeys = WeakHashMap<Activity, String>()
    private val fonts = mutableMapOf<Int, Typeface>()

    fun themePreference(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(THEME_KEY, "system").takeIf { it in themeChoices } ?: "system"

    fun languagePreference(context: Context): String =
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
            .getString(LANGUAGE_KEY, "system").takeIf { it in languageChoices } ?: "system"

    fun setPreferences(context: Context, theme: String, language: String) {
        require(theme in themeChoices) { "Unsupported appearance mode" }
        require(language in languageChoices) { "Unsupported interface language" }
        context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE).edit()
            .putString(THEME_KEY, theme).putString(LANGUAGE_KEY, language).apply()
    }

    private fun systemConfiguration(context: Context): Configuration =
        context.applicationContext?.resources?.configuration ?: context.resources.configuration

    fun isDark(context: Context): Boolean = when (themePreference(context)) {
        "light" -> false
        "dark" -> true
        else -> systemConfiguration(context).uiMode and Configuration.UI_MODE_NIGHT_MASK ==
            Configuration.UI_MODE_NIGHT_YES
    }

    fun colors(context: Context): Colors = palette(isDark(context))

    /**
     * Wrap each Activity in attachBaseContext. Services can use this context to resolve
     * notification strings, without mutating process-wide Locale or restarting the service.
     */
    fun wrapContext(base: Context): Context {
        val configuration = Configuration(base.resources.configuration)
        when (val language = languagePreference(base)) {
            "system" -> {
                val locales = systemConfiguration(base).locales
                configuration.setLocales(locales)
                configuration.setLayoutDirection(locales[0])
            }
            else -> {
                val locale = Locale.forLanguageTag(language)
                configuration.setLocales(LocaleList(locale))
                configuration.setLayoutDirection(locale)
            }
        }
        configuration.uiMode = (configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK.inv()) or
            if (isDark(base)) Configuration.UI_MODE_NIGHT_YES else Configuration.UI_MODE_NIGHT_NO
        return base.createConfigurationContext(configuration)
    }

    /** Includes resolved system choices so returning activities also refresh after OS changes. */
    fun key(context: Context): String = listOf(
        themePreference(context), languagePreference(context),
        isDark(context).toString(), systemConfiguration(context).locales.toLanguageTags(),
    ).joinToString("|")

    /** Call before super.onCreate, after attachBaseContext has applied the language. */
    @Suppress("DEPRECATION")
    fun apply(activity: Activity) {
        val dark = isDark(activity)
        activity.setTheme(if (dark) R.style.Theme_AnthroBios_Dark else R.style.Theme_AnthroBios_Light)
        val palette = palette(dark)
        activity.window.statusBarColor = palette.chrome
        activity.window.navigationBarColor = palette.chrome
        activity.window.setBackgroundDrawable(ColorDrawable(palette.page))
        if (Build.VERSION.SDK_INT >= 29) {
            activity.window.isStatusBarContrastEnforced = false
            activity.window.isNavigationBarContrastEnforced = false
        }
        if (Build.VERSION.SDK_INT >= 30) {
            val mask = WindowInsetsController.APPEARANCE_LIGHT_STATUS_BARS or
                WindowInsetsController.APPEARANCE_LIGHT_NAVIGATION_BARS
            // apply() runs before super.onCreate: PhoneWindow may not have a decor view
            // yet. Resolve the controller from the attached view, never the early window.
            val decor = activity.window.decorView
            decor.post {
                decor.windowInsetsController?.setSystemBarsAppearance(if (dark) 0 else mask, mask)
            }
        } else {
            val flags = View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR or View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR
            activity.window.decorView.systemUiVisibility =
                (activity.window.decorView.systemUiVisibility and flags.inv()) or if (dark) 0 else flags
        }
        appliedKeys[activity] = key(activity)
    }

    /** An Activity recreation preserves saved state; it must not recreate the acquisition service. */
    fun needsRecreation(activity: Activity): Boolean =
        appliedKeys[activity]?.let { it != key(activity) } ?: false

    fun uiTypeface(context: Context): Typeface = font(context, R.font.ibm_plex_mono_regular)
    fun headingTypeface(context: Context): Typeface = font(context, R.font.ibm_plex_mono_medium)
    fun readingTypeface(context: Context): Typeface = font(context, R.font.ibm_plex_sans)

    @Synchronized
    private fun font(context: Context, resource: Int): Typeface = fonts.getOrPut(resource) {
        context.resources.getFont(resource)
    }

    /** Mark long prose explicitly; ordinary controls and technical values retain Plex Mono. */
    fun reading(view: TextView) {
        view.setTag(R.id.anb_reading_text, true)
        view.typeface = readingTypeface(view.context)
    }

    fun dp(context: Context, value: Int): Int =
        (value * context.resources.displayMetrics.density).roundToInt()

    fun panelBackground(context: Context, strongBorder: Boolean = false): GradientDrawable {
        val palette = colors(context)
        return shape(context, palette.surface, if (strongBorder) palette.border else palette.rule)
    }

    fun buttonBackground(context: Context, primary: Boolean = false): StateListDrawable {
        val palette = colors(context)
        val normal = if (primary) palette.accent else palette.surface
        val pressed = if (primary) palette.accentHover else palette.hover
        val border = if (primary) palette.accent else palette.border
        return StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), shape(context, palette.subtle, palette.rule))
            addState(intArrayOf(android.R.attr.state_focused), shape(context, pressed, palette.focus, 2))
            addState(intArrayOf(android.R.attr.state_pressed), shape(context, pressed, palette.focus))
            addState(intArrayOf(), shape(context, normal, border))
        }
    }

    private fun shape(context: Context, fill: Int, stroke: Int, width: Int = 1) =
        GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = 0f
            setColor(fill)
            setStroke(dp(context, width), stroke)
        }

    private fun stateColors(enabled: Int, disabled: Int): ColorStateList = ColorStateList(
        arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
        intArrayOf(disabled, enabled),
    )

    private fun controls(palette: Colors): ColorStateList = ColorStateList(
        arrayOf(
            intArrayOf(-android.R.attr.state_enabled),
            intArrayOf(android.R.attr.state_checked),
            intArrayOf(android.R.attr.state_focused),
            intArrayOf(),
        ),
        intArrayOf(palette.muted, palette.accent, palette.accent, palette.border),
    )

    /**
     * Style newly constructed native controls. Existing view tags and listeners are preserved.
     * Semantically coloured labels remain intact; legacy palette values are mapped once.
     */
    fun styleTree(view: View) {
        val palette = colors(view.context)
        val background = view.background
        if (background is ColorDrawable) {
            mapLegacy(background.color, palette)?.let(view::setBackgroundColor)
        } else if (background is GradientDrawable) {
            background.color?.defaultColor?.let { old ->
                mapLegacy(old, palette)?.let {
                    background.setColor(it)
                    background.cornerRadius = 0f
                    background.setStroke(dp(view.context, 1), palette.rule)
                }
            }
        }
        if (view is TextView) {
            val style = view.typeface?.style ?: Typeface.NORMAL
            view.typeface = when {
                view.getTag(R.id.anb_reading_text) == true -> readingTypeface(view.context)
                style and Typeface.BOLD != 0 -> headingTypeface(view.context)
                else -> uiTypeface(view.context)
            }
            mapLegacy(view.currentTextColor, palette)?.let(view::setTextColor)
            view.setLinkTextColor(palette.accent)
        }
        when (view) {
            is CompoundButton -> {
                view.buttonTintList = controls(palette)
                view.minHeight = dp(view.context, 48)
            }
            is Button -> {
                val primary = view.backgroundTintList?.defaultColor == palette.accent ||
                    view.getTag(R.id.anb_primary_action) == true
                view.setTag(R.id.anb_primary_action, primary)
                view.isAllCaps = false
                view.minHeight = dp(view.context, 48)
                view.stateListAnimator = null
                view.elevation = 0f
                view.translationZ = 0f
                view.backgroundTintList = null
                val drawable = buttonBackground(view.context, primary)
                // Install the state selector directly: nesting it inside a ripple can
                // leave the child at its initial disabled state after restyling a view.
                drawable.state = view.drawableState
                view.background = drawable
                drawable.state = view.drawableState
                view.refreshDrawableState()
                view.jumpDrawablesToCurrentState()
                view.setTextColor(stateColors(if (primary) palette.onAccent else palette.text, palette.muted))
            }
            is EditText -> {
                view.minHeight = dp(view.context, 48)
                view.setTextColor(palette.text)
                view.setHintTextColor(palette.muted)
                view.backgroundTintList = controls(palette)
                view.highlightColor = palette.selected
            }
            is Spinner -> {
                view.minimumHeight = dp(view.context, 48)
                view.backgroundTintList = controls(palette)
                view.setPopupBackgroundDrawable(ColorDrawable(palette.surface))
            }
            is SeekBar -> {
                view.progressTintList = ColorStateList.valueOf(palette.accent)
                view.progressBackgroundTintList = ColorStateList.valueOf(palette.rule)
                view.thumbTintList = ColorStateList.valueOf(palette.accent)
            }
            is ProgressBar -> {
                view.progressTintList = ColorStateList.valueOf(palette.accent)
                view.progressBackgroundTintList = ColorStateList.valueOf(palette.rule)
                view.indeterminateTintList = ColorStateList.valueOf(palette.accent)
            }
        }
        if (view is ViewGroup) for (index in 0 until view.childCount) styleTree(view.getChildAt(index))
    }

    private fun mapLegacy(color: Int, palette: Colors): Int? = when (color) {
        Color.rgb(12, 22, 30), Color.rgb(16, 24, 32) -> palette.page
        Color.rgb(24, 39, 51), Color.rgb(30, 45, 57) -> palette.surface
        Color.rgb(228, 238, 244), Color.rgb(225, 235, 242), Color.WHITE -> palette.text
        Color.rgb(157, 178, 192), Color.LTGRAY -> palette.muted
        Color.rgb(75, 217, 202), Color.rgb(97, 212, 198) -> palette.accent
        Color.rgb(255, 185, 94) -> palette.warning
        else -> null
    }
}

