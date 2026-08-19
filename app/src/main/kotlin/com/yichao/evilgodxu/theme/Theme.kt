package com.yichao.evilgodxu.theme

import android.app.Activity
import android.graphics.Bitmap
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.ClipOp
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.core.view.drawToBitmap
import androidx.core.view.WindowCompat
import com.yichao.evilgodxu.data.repository.SettingsRepository
import com.yichao.evilgodxu.data.settings.ThemeMode
import org.koin.compose.koinInject

class ThemeTransitionController {
    var request: ((Offset) -> Unit)? = null

    fun revealAt(origin: Offset) {
        request?.invoke(origin)
    }
}

val LocalThemeTransitionController = androidx.compose.runtime.staticCompositionLocalOf<ThemeTransitionController> {
    error("ThemeTransitionController is not provided")
}

// 成功态配色（随主题切换）
val LocalSuccessColor = androidx.compose.runtime.staticCompositionLocalOf { md_theme_light_success }

val LightColorScheme = lightColorScheme(
    primary = md_theme_light_primary,
    onPrimary = md_theme_light_onPrimary,
    primaryContainer = md_theme_light_primaryContainer,
    onPrimaryContainer = md_theme_light_onPrimaryContainer,
    secondary = md_theme_light_secondary,
    onSecondary = md_theme_light_onSecondary,
    secondaryContainer = md_theme_light_secondaryContainer,
    onSecondaryContainer = md_theme_light_onSecondaryContainer,
    tertiary = md_theme_light_tertiary,
    onTertiary = md_theme_light_onTertiary,
    tertiaryContainer = md_theme_light_tertiaryContainer,
    onTertiaryContainer = md_theme_light_onTertiaryContainer,
    error = md_theme_light_error,
    onError = md_theme_light_onError,
    errorContainer = md_theme_light_errorContainer,
    onErrorContainer = md_theme_light_onErrorContainer,
    outline = md_theme_light_outline,
    background = md_theme_light_background,
    onBackground = md_theme_light_onBackground,
    surface = md_theme_light_surface,
    onSurface = md_theme_light_onSurface,
    surfaceVariant = md_theme_light_surfaceVariant,
    onSurfaceVariant = md_theme_light_onSurfaceVariant,
    surfaceContainerLowest = md_theme_light_surfaceContainerLowest,
    surfaceContainerLow = md_theme_light_surfaceContainerLow,
    surfaceContainer = md_theme_light_surfaceContainer,
    surfaceContainerHigh = md_theme_light_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_light_surfaceContainerHighest,
    inverseSurface = md_theme_light_inverseSurface,
    inverseOnSurface = md_theme_light_inverseOnSurface,
    inversePrimary = md_theme_light_inversePrimary,
    surfaceTint = md_theme_light_surfaceTint,
    surfaceDim = md_theme_light_surfaceDim,
    surfaceBright = md_theme_light_surfaceBright,
    outlineVariant = md_theme_light_outlineVariant,
    scrim = md_theme_light_scrim,
)

val DarkColorScheme = darkColorScheme(
    primary = md_theme_dark_primary,
    onPrimary = md_theme_dark_onPrimary,
    primaryContainer = md_theme_dark_primaryContainer,
    onPrimaryContainer = md_theme_dark_onPrimaryContainer,
    secondary = md_theme_dark_secondary,
    onSecondary = md_theme_dark_onSecondary,
    secondaryContainer = md_theme_dark_secondaryContainer,
    onSecondaryContainer = md_theme_dark_onSecondaryContainer,
    tertiary = md_theme_dark_tertiary,
    onTertiary = md_theme_dark_onTertiary,
    tertiaryContainer = md_theme_dark_tertiaryContainer,
    onTertiaryContainer = md_theme_dark_onTertiaryContainer,
    error = md_theme_dark_error,
    onError = md_theme_dark_onError,
    errorContainer = md_theme_dark_errorContainer,
    onErrorContainer = md_theme_dark_onErrorContainer,
    outline = md_theme_dark_outline,
    background = md_theme_dark_background,
    onBackground = md_theme_dark_onBackground,
    surface = md_theme_dark_surface,
    onSurface = md_theme_dark_onSurface,
    surfaceVariant = md_theme_dark_surfaceVariant,
    onSurfaceVariant = md_theme_dark_onSurfaceVariant,
    surfaceContainerLowest = md_theme_dark_surfaceContainerLowest,
    surfaceContainerLow = md_theme_dark_surfaceContainerLow,
    surfaceContainer = md_theme_dark_surfaceContainer,
    surfaceContainerHigh = md_theme_dark_surfaceContainerHigh,
    surfaceContainerHighest = md_theme_dark_surfaceContainerHighest,
    inverseSurface = md_theme_dark_inverseSurface,
    inverseOnSurface = md_theme_dark_inverseOnSurface,
    inversePrimary = md_theme_dark_inversePrimary,
    surfaceTint = md_theme_dark_surfaceTint,
    surfaceDim = md_theme_dark_surfaceDim,
    surfaceBright = md_theme_dark_surfaceBright,
    outlineVariant = md_theme_dark_outlineVariant,
    scrim = md_theme_dark_scrim,
)

@Composable
fun MyApplicationTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    val settingsRepository = koinInject<SettingsRepository>()
    val settings by settingsRepository.settings.collectAsState(initial = null)

    val isDarkTheme = when (settings?.themeMode) {
        ThemeMode.DARK -> true
        ThemeMode.LIGHT -> false
        else -> darkTheme
    }

    val transitionController = remember { ThemeTransitionController() }
    var previousBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var revealOrigin by remember { mutableStateOf(Offset.Zero) }
    val revealProgress = remember { Animatable(1f) }
    val view = LocalView.current

    transitionController.request = { origin ->
        if (view.width > 0 && view.height > 0) {
            previousBitmap = view.drawToBitmap()
            revealOrigin = origin
        }
    }

    LaunchedEffect(isDarkTheme, previousBitmap) {
        if (previousBitmap != null) {
            revealProgress.snapTo(0f)
            revealProgress.animateTo(1f, tween(800))
            previousBitmap = null
        }
    }

    // 同步状态栏与导航栏图标颜色与主题
    if (!view.isInEditMode) {
        SideEffect {
            // view.context 可能非 Activity，判空避免崩溃
            val window = (view.context as? Activity)?.window ?: return@SideEffect
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !isDarkTheme
                isAppearanceLightNavigationBars = !isDarkTheme
            }
        }
    }

    CompositionLocalProvider(
        LocalThemeTransitionController provides transitionController,
        LocalSuccessColor provides if (isDarkTheme) md_theme_dark_success else md_theme_light_success,
    ) {
        MaterialTheme(
            colorScheme = if (isDarkTheme) DarkColorScheme else LightColorScheme,
            typography = Typography,
        ) {
            androidx.compose.foundation.layout.Box(
                modifier = Modifier
                    .fillMaxSize()
                    .drawWithContent {
                        drawContent()
                        val bitmap = previousBitmap ?: return@drawWithContent
                        drawOldThemeOutsideReveal(bitmap, revealOrigin, revealProgress.value)
                    },
            ) {
                content()
            }
        }
    }
}

private fun DrawScope.drawOldThemeOutsideReveal(
    bitmap: Bitmap,
    origin: Offset,
    progress: Float,
) {
    val radius = maxRevealRadius(origin, size.width, size.height) * progress
    val path = Path().apply {
        addOval(
            androidx.compose.ui.geometry.Rect(
                left = origin.x - radius,
                top = origin.y - radius,
                right = origin.x + radius,
                bottom = origin.y + radius,
            ),
        )
    }
    clipPath(path, ClipOp.Difference) {
        drawImage(bitmap.asImageBitmap())
    }
}

private fun maxRevealRadius(origin: Offset, width: Float, height: Float): Float {
    return maxOf(
        origin.getDistance(),
        Offset(width, 0f).minus(origin).getDistance(),
        Offset(0f, height).minus(origin).getDistance(),
        Offset(width, height).minus(origin).getDistance(),
    )
}
