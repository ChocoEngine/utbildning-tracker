package com.utbildning.tracker.ui

import androidx.annotation.DrawableRes
import androidx.annotation.StringRes
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.delay
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.first
import androidx.lifecycle.withResumed
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.utbildning.tracker.R
import com.utbildning.tracker.data.TrackerRepository
import com.utbildning.tracker.ui.schedule.ScheduleScreen
import com.utbildning.tracker.ui.session.SessionScreen
import com.utbildning.tracker.ui.guide.GuideScreen
import com.utbildning.tracker.notifications.ReminderScheduler
import com.utbildning.tracker.data.PendingSessionActionResult
import com.utbildning.tracker.data.local.SessionResult
import androidx.compose.ui.platform.LocalContext
import com.utbildning.tracker.ui.calendar.CalendarScreen
import com.utbildning.tracker.ui.courses.CoursesScreen
import com.utbildning.tracker.ui.settings.SettingsScreen
import com.utbildning.tracker.ui.today.TodayScreen
import com.utbildning.tracker.ui.theme.TrackerTheme

private enum class MainDestination(
    val route: String,
    @param:StringRes val label: Int,
    @param:DrawableRes val icon: Int,
) {
    Today("today", R.string.nav_today, R.drawable.ic_sun),
    Calendar("calendar", R.string.nav_calendar, R.drawable.ic_calendar),
    Courses("courses", R.string.nav_courses, R.drawable.ic_book),
}

@Composable
fun AppNavigation(
    repository: TrackerRepository? = null,
    requestedSessionId: String? = null,
    requestedSessionAction: String? = null,
    onSessionHandled: () -> Unit = {},
) {
    val navController = rememberNavController()
    val hostLifecycle = LocalLifecycleOwner.current.lifecycle
    val handled by rememberUpdatedState(onSessionHandled)
    val context = LocalContext.current
    LaunchedEffect(requestedSessionId, requestedSessionAction) {
        if (repository != null && requestedSessionId != null) {
            val open = when (requestedSessionAction) {
                ReminderScheduler.ACTION_COMPLETE_SESSION -> when (
                    repository.applyPendingSessionAction(requestedSessionId, SessionResult.DONE)
                ) {
                    PendingSessionActionResult.NEEDS_TOPICS -> true
                    PendingSessionActionResult.APPLIED -> {
                        ReminderScheduler.reconcile(context, repository)
                        false
                    }
                    PendingSessionActionResult.IGNORED -> false
                }
                else -> repository.getSessionDetails(requestedSessionId) != null
            }
            // External intents can arrive during initial composition, before NavHost
            // installs its graph. Wait for the first destination instead of racing it.
            snapshotFlow { navController.currentBackStackEntry }.filterNotNull().first()
            // Room may suspend; external navigation must run on the Android main
            // thread while the host can receive lifecycle changes.
            withContext(Dispatchers.Main.immediate) {
                hostLifecycle.withResumed {
                    if (open) navController.navigate("session/$requestedSessionId") { launchSingleTop = true }
                    handled()
                }
            }
        }
    }
    var courseExit by remember { mutableStateOf<((() -> Unit) -> Unit)?>(null) }
    val entry by navController.currentBackStackEntryAsState()
    val route = entry?.destination?.route ?: MainDestination.Today.route
    if (repository != null) {
        SynchronizeOnResume(repository, requestedSessionId == null && route != "session/{sessionId}")
    }
    Scaffold(
        contentWindowInsets = WindowInsets.safeDrawing,
        bottomBar = {
            if (route in MainDestination.entries.map { it.route } && !(route == "courses" && courseExit != null)) {
                androidx.compose.foundation.layout.Column {
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                    NavigationBar(
                        containerColor = MaterialTheme.colorScheme.background,
                        tonalElevation = 0.dp,
                    ) {
                        MainDestination.entries.forEach { destination ->
                            NavigationBarItem(
                                modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp).background(
                                    if (route == destination.route) MaterialTheme.colorScheme.surfaceVariant else Color.Transparent, RoundedCornerShape(16.dp)).testTag("nav_${destination.route}"),
                                selected = route == destination.route,
                                onClick = {
                                    val navigate = { navController.navigate(destination.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    } }
                                    if (route == "courses" && courseExit != null) courseExit!!(navigate) else navigate()
                                },
                                icon = {
                                    Icon(
                                        painterResource(destination.icon),
                                        contentDescription = null,
                                        modifier = Modifier.size(22.dp),
                                    )
                                },
                                label = { Text(stringResource(destination.label)) },
                                colors = NavigationBarItemDefaults.colors(
                                    indicatorColor = Color.Transparent,
                                    selectedIconColor = MaterialTheme.colorScheme.onSurface,
                                    selectedTextColor = MaterialTheme.colorScheme.onSurface,
                                    unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                    unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                ),
                            )
                        }
                    }
                }
            }
        },
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = MainDestination.Today.route,
            modifier = Modifier.padding(padding).consumeWindowInsets(padding),
        ) {
            val openSettings: () -> Unit = {
                navController.navigate("settings") { launchSingleTop = true }
            }
            val openSession: (String) -> Unit = { navController.navigate("session/$it") }
            composable(MainDestination.Today.route) {
                if (repository != null) TodayScreen(openSettings, repository, openSession)
                else AppHeader(stringResource(R.string.nav_today), onSettings = openSettings)
            }
            composable(MainDestination.Calendar.route) {
                if (repository != null) CalendarScreen(openSettings, repository, openSession)
                else AppHeader(stringResource(R.string.nav_calendar), onSettings = openSettings)
            }
            composable(MainDestination.Courses.route) {
                if (repository != null) CoursesScreen(openSettings, repository, onExitHandler = { courseExit = it }) { navController.navigate("schedule/$it") }
                else AppHeader(stringResource(R.string.nav_courses), onSettings = openSettings)
            }
            composable("settings") {
                SettingsScreen(onBack = { navController.popBackStack() }, onGuide = { navController.navigate("guide") })
            }
            composable("schedule/{courseId}") { entry ->
                if (repository != null) ScheduleScreen(repository, entry.arguments!!.getString("courseId")!!) { navController.popBackStack() }
            }
            composable("session/{sessionId}") { entry ->
                if (repository != null) SessionScreen(repository, entry.arguments!!.getString("sessionId")!!) { navController.popBackStack() }
            }
            composable("guide") { GuideScreen { navController.popBackStack() } }
        }
    }
}

@Composable
private fun SynchronizeOnResume(repository: TrackerRepository, enabled: Boolean) {
    val context = LocalContext.current
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    var resumed by remember { mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) }
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _, _ -> resumed = lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    LaunchedEffect(repository, resumed, enabled) {
        if (resumed && enabled) while (true) {
            try { repository.synchronize(); ReminderScheduler.reconcile(context, repository) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { android.util.Log.e("Tracker", "Calendar synchronization failed", e) }
            delay(com.utbildning.tracker.domain.millisUntilNextLocalDay(System.currentTimeMillis(), java.time.ZoneId.systemDefault()))
        }
    }
}

@Composable
internal fun AppHeader(
    title: String,
    onSettings: (() -> Unit)? = null,
    onBack: (() -> Unit)? = null,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack, modifier = Modifier.testTag("back")) {
                Icon(painterResource(R.drawable.ic_back), stringResource(R.string.back))
            }
        }
        Text(
            text = title,
            modifier = Modifier.weight(1f).padding(horizontal = 8.dp),
            style = MaterialTheme.typography.headlineLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (onSettings != null) {
            IconButton(onClick = onSettings, modifier = Modifier.testTag("settings")) {
                Icon(painterResource(R.drawable.ic_settings), stringResource(R.string.settings))
            }
        }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun AppNavigationPreview() {
    TrackerTheme { AppNavigation() }
}
