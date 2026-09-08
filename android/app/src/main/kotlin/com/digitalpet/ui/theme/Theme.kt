package com.digitalpet.ui.theme

import android.app.Activity
import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat
import androidx.compose.foundation.shape.RoundedCornerShape

/*
 * `internal`, not private, so that `ContrastTest` can measure THE SCHEME rather
 * than the constants behind it.
 *
 * That distinction is not pedantry: the test used to read `LightOnSurfaceVariant`
 * directly, and rewiring a role here to a different constant would have left it
 * green while the app changed colour. A mutation found it. What a component
 * actually receives is what comes out of these two objects, so that is what is
 * measured.
 */
internal val DarkColorScheme = darkColorScheme(
    primary = PetGold,
    secondary = PetPurple,
    tertiary = PetGoldLight,
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onPrimary = DarkBackground,
    onSecondary = DarkBackground,
    onTertiary = DarkBackground,
    onBackground = OnDarkSurface,
    onSurface = OnDarkSurface,
    onSurfaceVariant = OnDarkSurface,

    /*
     * The remaining 24 roles. Until these were set they fell back to Material's
     * BASELINE palette, so M3's own components did not obey this theme: Card,
     * ModalBottomSheet, AlertDialog and the drawer take their backgrounds from
     * surfaceContainer* in M3 1.3, OutlinedTextField and HorizontalDivider from
     * outline/outlineVariant, and the modal scrim from scrim. All of those are
     * used in this app and all of them were rendering in stock Material colours.
     *
     * Filling every role is what makes designing against the M3 component
     * library work: a component can now be dropped in and be correct, instead of
     * arriving wrong and being fixed with a hardcoded override — which is how
     * the six literals in PetConditionCard came to exist.
     */
    primaryContainer = M3PrimaryContainer,
    onPrimaryContainer = M3OnPrimaryContainer,
    inversePrimary = M3InversePrimary,
    secondaryContainer = M3SecondaryContainer,
    onSecondaryContainer = M3OnSecondaryContainer,
    tertiaryContainer = M3TertiaryContainer,
    onTertiaryContainer = M3OnTertiaryContainer,
    error = M3Error,
    onError = M3OnError,
    errorContainer = M3ErrorContainer,
    onErrorContainer = M3OnErrorContainer,
    outline = M3Outline,
    outlineVariant = M3OutlineVariant,
    scrim = M3Scrim,
    inverseSurface = M3InverseSurface,
    inverseOnSurface = M3InverseOnSurface,
    surfaceTint = PetGold,
    surfaceDim = M3SurfaceDim,
    surfaceBright = M3SurfaceBright,
    surfaceContainerLowest = M3SurfaceContainerLowest,
    surfaceContainerLow = M3SurfaceContainerLow,
    surfaceContainer = M3SurfaceContainer,
    surfaceContainerHigh = M3SurfaceContainerHigh,
    surfaceContainerHighest = M3SurfaceContainerHighest,
)

/**
 * What an M3 component reads when it is given no shape.
 *
 * The three values are `tokens/shape.css`'s `--radius-small/medium/large`, and
 * they are taken from [PetRadius] rather than restated so that the design
 * system's shape file has exactly one place to land. Radii the screens draw
 * themselves live in [PetRadius] too; Material has no opinion about those.
 */
val CustomShapes = Shapes(
    small = RoundedCornerShape(PetRadius.r8),
    medium = RoundedCornerShape(PetRadius.r16),
    large = RoundedCornerShape(PetRadius.r24)
)

/*
 * LIGHT — added 2026-08-06 from the Claude Design main surface.
 *
 * Mirrors the dark mapping above role for role rather than inventing a second
 * arrangement, so a component that is correct in one scheme is correct in the
 * other. The values come from the design's own warm ladder; see Color.kt.
 *
 * `secondary` is GREEN here and purple in dark, deliberately — purple appears
 * nowhere in the light design and green does the accent work.
 */
internal val LightColorScheme = lightColorScheme(
    primary = PetGold,
    secondary = LightGreen,
    tertiary = PetGoldLight,
    background = LightBackground,
    surface = LightSurface,
    surfaceVariant = LightSurfaceVariant,
    // Gold is a light colour: its content must be the brown-black, not white.
    onPrimary = LightOnSurface,
    onSecondary = LightSurface,
    onTertiary = LightOnSurface,
    onBackground = LightOnSurface,
    onSurface = LightOnSurface,
    onSurfaceVariant = LightOnSurfaceVariant,

    primaryContainer = M3LightPrimaryContainer,
    onPrimaryContainer = M3LightOnPrimaryContainer,
    inversePrimary = M3LightInversePrimary,
    secondaryContainer = M3LightSecondaryContainer,
    onSecondaryContainer = M3LightOnSecondaryContainer,
    tertiaryContainer = M3LightTertiaryContainer,
    onTertiaryContainer = M3LightOnTertiaryContainer,
    error = LightCritical,
    onError = M3LightOnError,
    errorContainer = M3LightErrorContainer,
    onErrorContainer = M3LightOnErrorContainer,
    outline = LightOutline,
    outlineVariant = M3LightOutlineVariant,
    scrim = M3LightScrim,
    inverseSurface = M3LightInverseSurface,
    inverseOnSurface = M3LightInverseOnSurface,
    surfaceTint = PetGold,
    surfaceDim = M3LightSurfaceDim,
    surfaceBright = M3LightSurfaceBright,
    surfaceContainerLowest = M3LightSurfaceContainerLowest,
    surfaceContainerLow = M3LightSurfaceContainerLow,
    surfaceContainer = M3LightSurfaceContainer,
    surfaceContainerHigh = M3LightSurfaceContainerHigh,
    surfaceContainerHighest = M3LightSurfaceContainerHighest,
)

@Composable
fun DigitalPetTheme(
    /*
     * FOLLOWS THE SYSTEM as of 2026-08-06. This read
     * `darkTheme: Boolean = true, // Force dark theme for this app`, and
     * `lightColorScheme` was imported and never used — so the app was dark-only
     * and the light half of the design had nowhere to land.
     */
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = false, // Disable dynamic colors to keep premium look
    content: @Composable () -> Unit
) {
    val colorScheme = if (darkTheme) DarkColorScheme else LightColorScheme

    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            // Dark icons on the cream background, light icons on the dark one.
            // Getting this backwards leaves the clock invisible, which is the
            // kind of thing only noticed on the device.
            WindowCompat.getInsetsController(window, view)
                .isAppearanceLightStatusBars = !darkTheme
        }
    }

    // PetColors rides alongside the M3 scheme rather than inside it: Material has
    // no role for "the pet needs attention", and inventing one by abusing
    // `tertiary` would make the next palette change ambiguous.
    CompositionLocalProvider(
        LocalPetColors provides if (darkTheme) DarkPetColors else LightPetColors
    ) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            shapes = CustomShapes,
            content = content
        )
    }
}
