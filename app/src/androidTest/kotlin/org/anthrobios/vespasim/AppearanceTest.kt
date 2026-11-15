package org.anthrobios.vespasim

import android.content.Context
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.anthrobios.vespasim.ui.Appearance
import org.anthrobios.vespasim.ui.AppearanceActivity
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue

@RunWith(AndroidJUnit4::class)
class AppearanceTest {
    private val context get() = InstrumentationRegistry.getInstrumentation().targetContext
    private var previousTheme: String? = null
    private var previousLanguage: String? = null

    @Before fun keepPreferences() {
        val preferences = context.getSharedPreferences(Appearance.PREFERENCES, Context.MODE_PRIVATE)
        previousTheme = preferences.getString(Appearance.THEME_KEY, null)
        previousLanguage = preferences.getString(Appearance.LANGUAGE_KEY, null)
    }

    @After fun restorePreferences() {
        val editor = context.getSharedPreferences(Appearance.PREFERENCES, Context.MODE_PRIVATE).edit()
        if (previousTheme == null) editor.remove(Appearance.THEME_KEY)
            else editor.putString(Appearance.THEME_KEY, previousTheme)
        if (previousLanguage == null) editor.remove(Appearance.LANGUAGE_KEY)
            else editor.putString(Appearance.LANGUAGE_KEY, previousLanguage)
        editor.commit()
    }

    @Test fun savedLanguageAndThemeApplyToRecreatedScreens() {
        Appearance.setPreferences(context, "dark", "fr")
        ActivityScenario.launch(AppearanceActivity::class.java).use { scenario ->
            scenario.onActivity { activity ->
                assertEquals("fr", activity.resources.configuration.locales[0].language)
                assertEquals(Configuration.UI_MODE_NIGHT_YES,
                    activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertEquals("Affichage et à propos", activity.title.toString())
                assertFalse(Appearance.needsRecreation(activity))
                val root = activity.findViewById<ViewGroup>(android.R.id.content)
                val applyButton = root.findViewWithTag<Button>("appearance_apply")
                assertTrue(applyButton.minHeight >= Appearance.dp(activity, 48))
                assertEquals(Appearance.colors(activity).onAccent, applyButton.currentTextColor)
                Appearance.setPreferences(activity, "light", "nl")
                assertTrue(Appearance.needsRecreation(activity))
            }
            scenario.recreate()
            scenario.onActivity { activity ->
                assertEquals("nl", activity.resources.configuration.locales[0].language)
                assertEquals(Configuration.UI_MODE_NIGHT_NO,
                    activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK)
                assertEquals("Weergave en over", activity.title.toString())
                assertFalse(Appearance.needsRecreation(activity))
            }
        }
    }

    @Test fun serviceStringsCanRefreshWithoutChangingGlobalLocale() {
        Appearance.setPreferences(context, "system", "fr")
        val french = Appearance.wrapContext(context)
        assertEquals("Fermer", french.getString(R.string.appearance_close))
        Appearance.setPreferences(context, "system", "en")
        val english = Appearance.wrapContext(context)
        assertEquals("Close", english.getString(R.string.appearance_close))
        // Previously created contexts retain their chosen language.
        assertEquals("Fermer", french.getString(R.string.appearance_close))
    }

    @Test fun mountedButtonRestylingKeepsEnabledAndDisabledBackgroundsInBothThemes() {
        for (theme in listOf("light", "dark")) {
            Appearance.setPreferences(context, theme, "en")
            ActivityScenario.launch(AppearanceActivity::class.java).use { scenario ->
                InstrumentationRegistry.getInstrumentation().waitForIdleSync()
                scenario.onActivity { activity ->
                    val root = activity.findViewById<ViewGroup>(android.R.id.content)
                    val primary = root.findViewWithTag<Button>("appearance_apply")
                    val secondary = root.findViewWithTag<Button>("appearance_back")
                    val colors = Appearance.colors(activity)
                    repeat(3) {
                        Appearance.styleTree(root)
                        assertTrue(primary.isEnabled)
                        assertEquals(colors.accent, backgroundPixel(primary))
                        assertEquals(colors.onAccent, primary.currentTextColor)
                        assertEquals(colors.surface, backgroundPixel(secondary))
                        primary.isEnabled = false
                        Appearance.styleTree(root)
                        assertEquals(colors.subtle, backgroundPixel(primary))
                        assertEquals(colors.muted, primary.currentTextColor)
                        primary.isEnabled = true
                        assertEquals(colors.accent, backgroundPixel(primary))
                        assertEquals(colors.onAccent, primary.currentTextColor)
                    }
                }
            }
        }
    }

    private fun backgroundPixel(button: Button): Int {
        button.jumpDrawablesToCurrentState()
        assertTrue("Button has mounted bounds", button.width > 0 && button.height > 0)
        val bitmap = Bitmap.createBitmap(button.width, button.height, Bitmap.Config.ARGB_8888)
        return try {
            // View.drawBackground normally assigns these bounds on the next frame.
            // This synchronous probe runs immediately after background replacement.
            button.background.setBounds(0, 0, button.width, button.height)
            button.background.draw(Canvas(bitmap))
            bitmap.getPixel(bitmap.width / 2, bitmap.height / 2)
        } finally { bitmap.recycle() }
    }
}

