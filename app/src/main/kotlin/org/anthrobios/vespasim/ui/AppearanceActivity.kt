package org.anthrobios.vespasim.ui

import android.app.Activity
import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.view.WindowInsets
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import org.anthrobios.vespasim.BuildConfig
import org.anthrobios.vespasim.R
import java.util.concurrent.Executors

/** Presentation settings are independent of the acquisition and advertising services. */
class AppearanceActivity : Activity() {
    private val worker = Executors.newSingleThreadExecutor()
    private var destroyed = false

    override fun attachBaseContext(base: Context) = super.attachBaseContext(Appearance.wrapContext(base))

    override fun onCreate(savedInstanceState: Bundle?) {
        Appearance.apply(this)
        super.onCreate(savedInstanceState)
        title = getString(R.string.appearance_title)
        val palette = Appearance.colors(this)
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(16), dp(16), dp(24))
            setBackgroundColor(palette.page)
            setOnApplyWindowInsetsListener { view, insets ->
                @Suppress("DEPRECATION")
                val top = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).top
                    else insets.systemWindowInsetTop
                @Suppress("DEPRECATION")
                val bottom = if (Build.VERSION.SDK_INT >= 30) insets.getInsets(WindowInsets.Type.systemBars()).bottom
                    else insets.systemWindowInsetBottom
                view.setPadding(dp(16), dp(16) + top, dp(16), dp(24) + bottom)
                insets
            }
        }
        setContentView(ScrollView(this).apply { isFillViewport = true; addView(body) })
        body.addView(text(getString(R.string.app_name), 12f, palette.accent))
        body.addView(text(getString(R.string.appearance_title), 24f, heading = true))
        body.addView(action(R.string.appearance_back, "appearance_back") { finish() })
        body.addView(text(getString(R.string.appearance_intro), reading = true))

        val themeLabel = text(getString(R.string.appearance_theme), 18f, heading = true)
        val theme = spinner(
            listOf(R.string.appearance_theme_system, R.string.appearance_theme_light, R.string.appearance_theme_dark),
            Appearance.themeChoices.indexOf(Appearance.themePreference(this)),
            "appearance_theme",
        )
        themeLabel.labelFor = theme.id
        body.addView(themeLabel)
        body.addView(theme)

        val languageLabel = text(getString(R.string.appearance_language), 18f, heading = true)
        val language = spinner(
            listOf(R.string.appearance_language_system, R.string.appearance_language_english,
                R.string.appearance_language_dutch, R.string.appearance_language_french),
            Appearance.languageChoices.indexOf(Appearance.languagePreference(this)),
            "appearance_language",
        )
        languageLabel.labelFor = language.id
        body.addView(languageLabel)
        body.addView(language)
        body.addView(action(R.string.appearance_apply, "appearance_apply") {
            Appearance.setPreferences(this,
                Appearance.themeChoices[theme.selectedItemPosition],
                Appearance.languageChoices[language.selectedItemPosition])
            Toast.makeText(this, Appearance.wrapContext(this).getString(R.string.appearance_saved), Toast.LENGTH_SHORT).show()
            recreate()
        }.apply { setTag(R.id.anb_primary_action, true) })

        body.addView(text(getString(R.string.appearance_about), 20f, heading = true))
        body.addView(text(getString(R.string.appearance_about_body), reading = true))
        body.addView(text(getString(R.string.appearance_version,
            BuildConfig.VERSION_NAME, BuildConfig.GIT_COMMIT.take(12)), 12f, palette.muted))
        body.addView(action(R.string.appearance_project_link, "appearance_project") {
            openLink("https://www.anthrobiosnexus.com/projects/openvespatrack/")
        })
        body.addView(action(R.string.appearance_desktop_link, "appearance_desktop") {
            openLink("https://github.com/benjaminvdz/openvespatrack-ble-tagfinder")
        })
        body.addView(text(getString(R.string.appearance_links_external), 12f, palette.muted, reading = true))
        body.addView(text(getString(R.string.appearance_licenses), 20f, heading = true))
        body.addView(text(getString(R.string.appearance_licenses_body), reading = true))
        body.addView(action(R.string.appearance_license_mit, "appearance_license_mit") {
            showLicense(R.string.appearance_license_title_mit, listOf("licenses/MIT.txt"))
        })
        body.addView(action(R.string.appearance_license_fonts, "appearance_license_fonts") {
            showLicense(R.string.appearance_license_title_fonts,
                listOf("licenses/ibm-plex-mono-OFL.txt", "licenses/ibm-plex-sans-OFL.txt"))
        })
        Appearance.styleTree(body)
    }

    private fun text(value: String, size: Float = 16f, color: Int = Appearance.colors(this).text,
        heading: Boolean = false, reading: Boolean = false): TextView = TextView(this).apply {
        text = value
        textSize = size
        setTextColor(color)
        setPadding(0, dp(if (heading) 20 else 10), 0, dp(10))
        setLineSpacing(dp(2).toFloat(), if (reading) 1.25f else 1.1f)
        if (heading) {
            typeface = Typeface.create(Appearance.headingTypeface(context), Typeface.BOLD)
            if (Build.VERSION.SDK_INT >= 28) isAccessibilityHeading = true
        }
        if (reading) Appearance.reading(this)
    }

    private fun spinner(labels: List<Int>, selection: Int, key: String): Spinner = Spinner(this).apply {
        id = View.generateViewId()
        tag = key
        adapter = ArrayAdapter(this@AppearanceActivity,
            android.R.layout.simple_spinner_item, labels.map { getString(it) }).apply {
            setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        }
        setSelection(selection.coerceAtLeast(0))
        minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(-1, -2)
    }

    private fun action(label: Int, key: String, onClick: () -> Unit): Button = Button(this).apply {
        setText(label)
        tag = key
        isAllCaps = false
        minHeight = dp(48)
        setPadding(dp(12), dp(8), dp(12), dp(8))
        setOnClickListener { onClick() }
        layoutParams = LinearLayout.LayoutParams(-1, -2).apply { topMargin = dp(8) }
    }

    private fun openLink(url: String) {
        runCatching { startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) }.onFailure {
            Toast.makeText(this, R.string.appearance_open_link_failed, Toast.LENGTH_LONG).show()
        }
    }

    private fun showLicense(titleId: Int, files: List<String>) {
        Toast.makeText(this, R.string.appearance_license_loading, Toast.LENGTH_SHORT).show()
        worker.execute {
            val result = runCatching {
                files.joinToString("\n\n") { path -> assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() } }
            }
            runOnUiThread {
                if (destroyed || isFinishing) return@runOnUiThread
                result.onSuccess { contents ->
                    val text = text(contents, 14f, reading = true).apply {
                        setPadding(dp(16), dp(8), dp(16), dp(8))
                        setTextIsSelectable(true)
                    }
                    val scroll = ScrollView(this).apply { addView(text) }
                    Appearance.styleTree(scroll)
                    AlertDialog.Builder(this).setTitle(titleId).setView(scroll)
                        .setPositiveButton(R.string.appearance_close, null).show()
                }.onFailure {
                    Toast.makeText(this, R.string.appearance_license_unavailable, Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun dp(value: Int): Int = Appearance.dp(this, value)

    override fun onDestroy() {
        destroyed = true
        worker.shutdownNow()
        super.onDestroy()
    }
}

