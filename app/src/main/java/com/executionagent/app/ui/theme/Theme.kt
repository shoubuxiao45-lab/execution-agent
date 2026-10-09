package com.executionagent.app.ui.theme

import android.app.Activity
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val LightColors = lightColorScheme(primary = Color(0xFF286A5D), secondary = Color(0xFF50645D))
private val DarkColors = darkColorScheme(primary = Color(0xFF8DD4C1), secondary = Color(0xFFB6CCC2))

@Composable
fun ExecutionAgentTheme(darkTheme: Boolean, content: @Composable () -> Unit) {
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            (view.context as? Activity)?.window?.let { window ->
                WindowCompat.getInsetsController(window, view).apply {
                    isAppearanceLightStatusBars = !darkTheme
                    isAppearanceLightNavigationBars = !darkTheme
                }
            }
        }
    }
    MaterialTheme(colorScheme = if (darkTheme) DarkColors else LightColors, content = content)
}
