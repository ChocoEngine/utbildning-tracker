package com.utbildning.tracker.ui.theme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
@Composable
internal fun CourseActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, content: @Composable RowScope.() -> Unit) {
    Button(onClick = onClick, modifier = modifier.height(48.dp), enabled = enabled, shape = RoundedCornerShape(13.dp), colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF718376), contentColor = Color(0xFFFCFAF6)), contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp), content = {
            ProvideTextStyle(MaterialTheme.typography.bodyMedium.copy(fontSize = 12.sp)) {
                content()
            }
        })
}
