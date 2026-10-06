package com.utbildning.tracker.ui

import android.app.LocaleConfig
import android.app.LocaleManager
import android.content.res.Configuration
import android.os.LocaleList
import androidx.compose.ui.test.assertContentDescriptionEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsSelected
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.onNodeWithText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.utbildning.tracker.MainActivity
import com.utbildning.tracker.R
import com.utbildning.tracker.data.AppContainer
import com.utbildning.tracker.domain.WeeklyRule
import java.time.LocalDate
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class LanguageSettingsTest {
    @get:Rule
    val compose = createEmptyComposeRule(effectContext = StandardTestDispatcher())

    private val context = InstrumentationRegistry.getInstrumentation().targetContext
    private val localeManager = context.getSystemService(LocaleManager::class.java)
    private lateinit var originalLocales: LocaleList
    private var scenario: ActivityScenario<MainActivity>? = null

    @Before
    fun setKnownLanguage() {
        originalLocales = localeManager.applicationLocales
        localeManager.applicationLocales = LocaleList.forLanguageTags("en")
        scenario = ActivityScenario.launch(MainActivity::class.java)
    }

    @After
    fun restoreLanguage() {
        scenario?.close()
        localeManager.applicationLocales = originalLocales
    }

    @Test
    fun languageChoicesTranslateSettingsAccessibilityAndNavigation() {
        compose.onNodeWithTag("nav_courses").performClick()
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("language_en").assertIsSelected()

        selectLanguage("ru", "Настройки")
        compose.onNodeWithTag("back").assertContentDescriptionEquals("Назад")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("screen_courses").assertIsDisplayed()
        compose.onNodeWithTag("nav_courses").assertTextContains("Курсы")
        compose.onNodeWithTag("settings").assertContentDescriptionEquals("Настройки")

        compose.onNodeWithTag("settings").performClick()
        selectLanguage("en", "Settings")
        compose.onNodeWithTag("back").assertContentDescriptionEquals("Back")
        compose.onNodeWithTag("back").performClick()
        compose.onNodeWithTag("nav_courses").assertTextContains("Courses")
        compose.onNodeWithTag("settings").assertContentDescriptionEquals("Settings")
    }

    @Test
    fun explicitLanguageSurvivesActivityRecreationAndRelaunch() {
        compose.onNodeWithTag("settings").performClick()
        selectLanguage("ru", "Настройки")
        scenario!!.recreate()
        awaitTitle("Настройки")
        compose.onNodeWithTag("language_ru").assertIsSelected()

        scenario!!.close()
        scenario = ActivityScenario.launch(MainActivity::class.java)
        compose.onNodeWithTag("settings").assertContentDescriptionEquals("Настройки")
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("language_ru").assertIsSelected()
        assertEquals("ru", localeManager.applicationLocales.toLanguageTags())
    }

    @Test
    fun externalLanguageChangeIsReflectedAndSystemChoiceClearsOverride() {
        compose.onNodeWithTag("settings").performClick()
        localeManager.applicationLocales = LocaleList.forLanguageTags("ru")
        awaitTitle("Настройки")
        compose.onNodeWithTag("language_ru").assertIsSelected()

        compose.onNodeWithTag("language_system").performClick()
        compose.waitUntil(10_000) { localeManager.applicationLocales.isEmpty }
        compose.waitForIdle()
        compose.onNodeWithTag("language_system").assertIsSelected()
        val systemContext = context.createConfigurationContext(
            Configuration(context.resources.configuration).apply {
                setLocales(localeManager.systemLocales)
            },
        )
        awaitTitle(systemContext.getString(R.string.settings))
        scenario!!.recreate()
        compose.onNodeWithTag("language_system").assertIsSelected()
        assertEquals("", localeManager.applicationLocales.toLanguageTags())
    }

    @Test
    fun selectingSystemLanguageAndItsExplicitEquivalentUpdatesSelection() {
        val systemLanguage = localeManager.systemLocales[0].language
        val effectiveLanguage = if (systemLanguage == "ru") "ru" else "en"
        compose.onNodeWithTag("settings").performClick()
        compose.onNodeWithTag("language_system").performClick()
        compose.waitUntil(10_000) { localeManager.applicationLocales.isEmpty }
        compose.waitForIdle()
        compose.onNodeWithTag("language_system").assertIsSelected()

        selectLanguage(effectiveLanguage, if (effectiveLanguage == "ru") "Настройки" else "Settings")
        compose.onNodeWithTag("language_system").performClick()
        compose.waitUntil(10_000) { localeManager.applicationLocales.isEmpty }
        compose.waitForIdle()
        compose.onNodeWithTag("language_system").assertIsSelected()
    }

    @Test
    fun unsupportedLanguagesUseEnglishAndOnlySupportedLanguagesAreDeclared() {
        for (language in listOf("sv", "ar")) {
            val localizedContext = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language))
                },
            )
            assertEquals("Settings", localizedContext.getString(R.string.settings))
            assertEquals("Back", localizedContext.getString(R.string.back))
            assertEquals("Courses", localizedContext.getString(R.string.nav_courses))
        }
        val config = LocaleConfig(context)
        assertEquals(LocaleConfig.STATUS_SUCCESS, config.status)
        val locales = requireNotNull(config.supportedLocales)
        assertEquals(setOf("en", "ru"), (0 until locales.size()).map { locales[it].language }.toSet())
    }

    @Test
    fun guideCanBeReadAndReopenedInBothLanguages() {
        compose.onNodeWithTag("settings").performClick()
        for ((language, title) in listOf("ru" to "Настройки", "en" to "Settings")) {
            selectLanguage(language, title)
            val localized = context.createConfigurationContext(
                Configuration(context.resources.configuration).apply {
                    setLocales(LocaleList.forLanguageTags(language))
                },
            )
            repeat(2) {
                compose.onNodeWithTag("guide_open").performScrollTo().performClick()
                compose.onNodeWithText(localized.getString(R.string.guide_title)).assertIsDisplayed()
                for (paragraph in listOf(R.string.guide_courses, R.string.guide_categories,
                    R.string.guide_topics, R.string.guide_gestures, R.string.guide_schedule,
                    R.string.guide_results, R.string.guide_calendar, R.string.guide_lifecycle,
                    R.string.guide_notifications)) {
                    compose.onNodeWithText(localized.getString(paragraph)).performScrollTo().assertIsDisplayed()
                }
                compose.onNodeWithText(localized.getString(R.string.back)).performScrollTo().performClick()
                compose.onNodeWithTag("screen_settings").assertIsDisplayed()
            }
        }
    }

    @Test
    fun changingLanguagePreservesNamesScheduleAndTopicProgress() {
        val repository = AppContainer.repository(context)
        val course = runBlocking {
            repository.saveCourseForm(name = "Лекции по C · Arrays", colorId = repository.availableColors().first(),
                categoryName = "C · Программирование", topicText = "Указатели\nArrays\nСтруктуры")
        }
        try {
            val original = runBlocking {
                val tomorrow = LocalDate.now().plusDays(1)
                repository.saveInitialSchedule(course.id, listOf(WeeklyRule(tomorrow.dayOfWeek.value, 720)))
                val topic = repository.getCourseDetails(course.id)!!.topics[1]
                repository.toggleTopicCompletion(course.id, topic.id)
                repository.synchronize()
                Triple(repository.getCourseDetails(course.id), repository.getSchedule(course.id),
                    repository.observeSessions().first().filter { it.courseId == course.id })
            }
            fun assertUnchanged() = runBlocking {
                assertEquals(original.first, repository.getCourseDetails(course.id))
                assertEquals(original.second, repository.getSchedule(course.id))
                assertEquals(original.third, repository.observeSessions().first().filter { it.courseId == course.id })
            }
            compose.onNodeWithTag("settings").performClick()
            for ((language, title) in listOf("ru" to "Настройки", "en" to "Settings")) {
                selectLanguage(language, title)
                assertUnchanged()
            }
            scenario!!.recreate()
            assertUnchanged()
        } finally {
            runBlocking { repository.deleteCourse(course.id) }
        }
    }

    private fun selectLanguage(language: String, title: String) {
        compose.onNodeWithTag("language_$language").performClick()
        compose.waitUntil(10_000) {
            localeManager.applicationLocales.toLanguageTags() == language
        }
        awaitTitle(title)
        compose.onNodeWithTag("language_$language").assertIsSelected()
    }

    private fun awaitTitle(title: String) {
        compose.waitUntil(10_000) {
            compose.onAllNodesWithText(title).fetchSemanticsNodes().isNotEmpty()
        }
        compose.waitForIdle()
    }
}
