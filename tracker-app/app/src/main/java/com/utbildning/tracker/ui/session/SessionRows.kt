package com.utbildning.tracker.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.utbildning.tracker.R
import com.utbildning.tracker.data.local.SessionEntity
import com.utbildning.tracker.data.local.SessionResult
import com.utbildning.tracker.ui.theme.CourseColors
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable
internal fun SessionRow(session: SessionEntity, locale: Locale, onDone: (String) -> Unit, onSkip: (String) -> Unit, question: Boolean = false, onPending: (String) -> Unit = {}) {
    val status = stringResource(when(session.result) {
        SessionResult.DONE -> R.string.session_done
        SessionResult.SKIPPED -> R.string.session_skipped
        SessionResult.PENDING -> R.string.session_planned
    })
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(5.dp).height(72.dp).background(CourseColors[session.colorIdSnapshot].copy(alpha = if (session.result == SessionResult.DONE) 1f else .35f)))
        Column(Modifier.weight(1f)) {
            Text(session.courseNameSnapshot, style = MaterialTheme.typography.titleMedium)
            Text(LocalTime.of(session.startMinute / 60, session.startMinute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)) + " · " + status)
            if (question) Text(stringResource(R.string.session_question), modifier = Modifier.testTag("question_${session.id}"))
            Row {
                TextButton(onClick = { onDone(session.id) }, modifier = Modifier.testTag("session_done_${session.id}")) { Text(stringResource(R.string.session_done)) }
                TextButton(onClick = { onSkip(session.id) }, modifier = Modifier.testTag("session_skip_${session.id}")) { Text(stringResource(R.string.session_skipped)) }
            }
            if (session.result != SessionResult.PENDING) TextButton(onClick = { onPending(session.id) }, modifier = Modifier.testTag("session_pending_${session.id}")) { Text(stringResource(R.string.session_unmarked)) }
        }
    }
}
