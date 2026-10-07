package com.utbildning.tracker.ui.guide

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.utbildning.tracker.R
import com.utbildning.tracker.ui.theme.TrackerTheme

@Composable
internal fun GuideScreen(onBack: () -> Unit) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        TextButton(onClick = onBack) { Text(stringResource(R.string.back)) }
        Text(stringResource(R.string.guide_title), style = MaterialTheme.typography.headlineMedium)
        listOf(R.string.guide_courses, R.string.guide_categories, R.string.guide_topics, R.string.guide_gestures,
            R.string.guide_schedule, R.string.guide_results, R.string.guide_calendar, R.string.guide_lifecycle,
            R.string.guide_notifications, R.string.guide_backup)
            .forEach { Text(stringResource(it), style = MaterialTheme.typography.bodyLarge) }
    }
}

@Preview(locale = "ru", showBackground = true)
@Composable
private fun GuidePreview() { TrackerTheme { Surface { GuideScreen({}) } } }
