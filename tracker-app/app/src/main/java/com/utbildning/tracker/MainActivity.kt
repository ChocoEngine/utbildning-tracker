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
import com.utbildning.tracker.backup.BackupImporter
import com.utbildning.tracker.backup.AutomaticBackupManager
import com.utbildning.tracker.maintenance.CalendarCleanupManager
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private var pendingSessionId by mutableStateOf<String?>(null)
    private var pendingSessionAction by mutableStateOf<String?>(null)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (savedInstanceState == null) accept(intent)
        val repository = AppContainer.repository(applicationContext)
        AutomaticBackupManager(applicationContext).reconcile()
        CalendarCleanupManager(applicationContext).reconcile()
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                BackupImporter(applicationContext, repository).recoverInterruptedRestore()
            } catch (cancel: CancellationException) {
                throw cancel
            } catch (error: Exception) {
                android.util.Log.e("Tracker", "Restore recovery failed", error)
            }
            ReminderScheduler.startObserving(applicationContext, repository)
        }
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
