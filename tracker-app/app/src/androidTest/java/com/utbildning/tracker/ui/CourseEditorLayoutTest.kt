package com.utbildning.tracker.ui

import androidx.compose.material3.Surface
import androidx.compose.foundation.layout.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.utbildning.tracker.domain.EditableTopic
import com.utbildning.tracker.ui.courses.CourseDraft
import com.utbildning.tracker.ui.courses.CourseEditorContent
import com.utbildning.tracker.ui.theme.TrackerTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class CourseEditorLayoutTest {
    @get:Rule val compose = createComposeRule()

    @Test fun togglingCompletionPreservesTitleBoundsAndPencilOpensEditor() {
        var draft by mutableStateOf(CourseDraft(name = "C", text = "A long topic title about pointers and memory management",
            topics = listOf(EditableTopic("topic", "A long topic title about pointers and memory management", 0))))
        compose.setContent {
            TrackerTheme { Surface {
                CourseEditorContent(draft, emptyList(), listOf(0, 1), false, null,
                    onChange = { draft = it(draft) }, onSave = {}, onCancel = {}, onApply = {},
                    onDeleteCategory = {}, onToggle = {
                        val topic = draft.topics.single()
                        draft = draft.copy(topics = listOf(topic.copy(isCompleted = !topic.isCompleted)),
                            text = if (!topic.isCompleted) "" else topic.title)
                    })
            } }
        }
        val before = compose.onNodeWithTag("topic_title_topic", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("topic_topic").performTouchInput { longClick() }
        compose.onNodeWithTag("topic_done_topic", useUnmergedTree = true).assertExists()
        val after = compose.onNodeWithTag("topic_title_topic", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot
        assertEquals(before, after)
        compose.onNodeWithTag("topic_topic").performTouchInput { longClick() }
        assertEquals(before, compose.onNodeWithTag("topic_title_topic", useUnmergedTree = true).fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("topics_edit").performClick()
        compose.onNodeWithTag("topics_input").assertExists()
    }
    @Test fun longTopicListScrollsWithoutMovingHeaderOrActionsAt320dp() {
        val topics = (1..60).map { EditableTopic("topic-$it", "Topic $it", it - 1) }
        val draft = CourseDraft(id = "course", name = "C", topics = topics,
            text = topics.joinToString("\n") { it.title })
        compose.setContent {
            TrackerTheme { Surface(Modifier.width(320.dp).fillMaxHeight()) {
                CourseEditorContent(draft, emptyList(), (0..9).toList(), false, null,
                    onChange = {}, onSave = {}, onCancel = {}, onApply = {}, onDeleteCategory = {}, onToggle = {})
            } }
        }
        val title = compose.onNodeWithTag("course_title").fetchSemanticsNode().boundsInRoot
        val actions = compose.onNodeWithTag("course_actions").fetchSemanticsNode().boundsInRoot
        compose.onNodeWithTag("topic_topic-60").performScrollTo().assertIsDisplayed()
        assertEquals(title, compose.onNodeWithTag("course_title").fetchSemanticsNode().boundsInRoot)
        assertEquals(actions, compose.onNodeWithTag("course_actions").fetchSemanticsNode().boundsInRoot)
        compose.onNodeWithTag("course_complete").assertIsDisplayed()
        compose.onNodeWithTag("topics_edit").assertIsDisplayed()
    }

}
