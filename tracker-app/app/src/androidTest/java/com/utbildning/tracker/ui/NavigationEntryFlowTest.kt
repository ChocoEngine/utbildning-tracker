package com.utbildning.tracker.ui

import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NavigationEntryFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun currentEntryFlowResumesWhenGraphIsInstalledAfterWaitingStarts() {
        val installGraph = mutableStateOf(false)
        var destinationObserved = false
        compose.setContent {
            val navController = rememberNavController()
            LaunchedEffect(navController) {
                navController.currentBackStackEntryFlow.first()
                destinationObserved = true
            }
            if (installGraph.value) {
                NavHost(navController, startDestination = "late") {
                    composable("late") { Text("Late graph") }
                }
            }
        }

        compose.runOnIdle { assertFalse(destinationObserved) }
        compose.runOnIdle { installGraph.value = true }
        compose.waitUntil(5_000) { destinationObserved }
        compose.runOnIdle { assertTrue(destinationObserved) }
    }
}
