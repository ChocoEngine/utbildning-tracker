package com.utbildning.tracker.ui.session

import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.Alignment
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.painterResource
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
internal fun SessionRow(session: SessionEntity, locale: Locale, onDone: (String) -> Unit, onSkip: (String) -> Unit, question: Boolean = false, onPending: (String) -> Unit = {}, todayStyle: Boolean = false) {
    val status = stringResource(when(session.result) {
        SessionResult.DONE -> R.string.session_done
        SessionResult.SKIPPED -> R.string.session_skipped
        SessionResult.PENDING -> R.string.session_planned
    })
    val time = LocalTime.of(session.startMinute / 60, session.startMinute % 60).format(DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale))
    if (todayStyle) {
        Column(Modifier.fillMaxWidth().padding(vertical = 17.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(time, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
                val blot = GenericShape { size, _ ->
                    moveTo(size.width * .1f, size.height * .35f)
                    cubicTo(0f, 0f, size.width * .8f, 0f, size.width * .9f, size.height * .3f)
                    cubicTo(size.width * 1.15f, size.height * .8f, size.width * .7f, size.height, size.width * .4f, size.height * .9f)
                    cubicTo(0f, size.height, 0f, size.height * .6f, size.width * .1f, size.height * .35f)
                    close()
                }
                Box(Modifier.size(22.dp, 25.dp).background(CourseColors[session.colorId].copy(alpha = if (session.result == SessionResult.DONE) 1f else .35f), blot), contentAlignment = Alignment.Center) {
                    if (session.result == SessionResult.SKIPPED) Text("×", style = MaterialTheme.typography.labelSmall)
                }
                Text(session.courseName, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            }
            val buttonBackground = MaterialTheme.colorScheme.secondaryContainer
            val selectedBackground = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = .1f).compositeOver(buttonBackground)
            val doneSelected = session.result == SessionResult.DONE
            val skipSelected = session.result == SessionResult.SKIPPED
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilledTonalButton(onClick = { if (doneSelected) onPending(session.id) else onDone(session.id) }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (doneSelected) selectedBackground else buttonBackground), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.semantics { selected = doneSelected }.testTag("session_done_${session.id}")) {
                    Icon(painterResource(R.drawable.ic_check), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.session_done), style = MaterialTheme.typography.labelMedium)
                }
                FilledTonalButton(onClick = { if (skipSelected) onPending(session.id) else onSkip(session.id) }, shape = RoundedCornerShape(12.dp), colors = ButtonDefaults.filledTonalButtonColors(containerColor = if (skipSelected) selectedBackground else buttonBackground), contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp), modifier = Modifier.semantics { selected = skipSelected }.testTag("session_skip_${session.id}")) {
                    Icon(painterResource(R.drawable.ic_cross), contentDescription = null, modifier = Modifier.size(16.dp))
                    Spacer(Modifier.width(6.dp))
                    Text(stringResource(R.string.session_skipped), style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        return
    }
    Row(Modifier.fillMaxWidth().padding(vertical = 8.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Box(Modifier.width(5.dp).height(72.dp).background(CourseColors[session.colorId].copy(alpha = if (session.result == SessionResult.DONE) 1f else .35f)))
        Column(Modifier.weight(1f)) {
            Text(session.courseName, style = MaterialTheme.typography.titleMedium)
            Text(time + " · " + status)
            if (question) Text(stringResource(R.string.session_question), modifier = Modifier.testTag("question_${session.id}"))
            Row {
                TextButton(onClick = { onDone(session.id) }, modifier = Modifier.testTag("session_done_${session.id}")) { Text(stringResource(R.string.session_done)) }
                TextButton(onClick = { onSkip(session.id) }, modifier = Modifier.testTag("session_skip_${session.id}")) { Text(stringResource(R.string.session_skipped)) }
            }
            if (session.result != SessionResult.PENDING) TextButton(onClick = { onPending(session.id) }, modifier = Modifier.testTag("session_pending_${session.id}")) { Text(stringResource(R.string.session_unmarked)) }
        }
    }
}
