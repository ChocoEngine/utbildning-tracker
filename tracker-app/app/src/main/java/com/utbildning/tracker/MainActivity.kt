package com.utbildning.tracker

import android.os.Bundle
import android.graphics.Color
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.utbildning.tracker.ui.AppNavigation
import com.utbildning.tracker.ui.theme.TrackerTheme
import com.utbildning.tracker.data.AppContainer
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import com.utbildning.tracker.notifications.ReminderScheduler

class MainActivity : ComponentActivity() {
    private var pendingSessionId by mutableStateOf<String?>(null)
    private var pendingSessionAction by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) accept(intent)
        val repository = AppContainer.repository(applicationContext)
        ReminderScheduler.startObserving(applicationContext, repository)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        setContent {
            TrackerTheme {
                AppNavigation(repository, pendingSessionId, onSessionHandled = {
                    pendingSessionId = null
                    pendingSessionAction = null
                }, requestedSessionAction = pendingSessionAction)
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        accept(intent)
    }

    private fun accept(intent: Intent) {
        pendingSessionId = intent.getStringExtra(ReminderScheduler.EXTRA_SESSION_ID)
        pendingSessionAction = intent.action
    }
}
