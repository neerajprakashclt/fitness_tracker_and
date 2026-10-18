package app.fitness.android.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** How lists and cards are laid out, which differs by structure between the theme families and not only by colour. */
enum class LayoutStyle {
    /** Strava: a feed of rounded cards on a light-grey background. */
    CARDS,
    /** iOS: grouped inset blocks, hairline dividers, segmented controls. */
    GROUPED,
    /** Unix: plain bordered blocks, monospace, prompt-style headers. */
    TERMINAL,
}

/** Colours for the chart series and the HR zones. Charts and maps read these, never a hard-coded colour. */
@Immutable
class ChartColors(
    val heartRate: Color,
    val pace: Color,
    val elevation: Color,
    val power: Color,
    val cadence: Color,
    val route: Color,
    val zones: List<Color>,
)

/** A theme is a set of design tokens: colour roles, typography, shape and layout style. */
@Immutable
class FitnessTheme(
    val id: String,
    val name: String,
    val dark: Boolean,
    val background: Color,
    val surface: Color,
    val surfaceAlt: Color,
    val text: Color,
    val muted: Color,
    val accent: Color,
    val onAccent: Color,
    val divider: Color,
    val danger: Color,
    val chart: ChartColors,
    val layout: LayoutStyle,
    val fontFamily: FontFamily,
    /** Weight of big numerals and titles. */
    val numeralWeight: FontWeight,
    val cornerRadius: Dp,
    val borderWidth: Dp,
    val borderColor: Color,
    val cardElevation: Dp,
    /** Background of the map when no tiles are shown. */
    val mapBackground: Color,
) {
    val monospace get() = fontFamily == FontFamily.Monospace
}

val LocalFitnessTheme = compositionLocalOf<FitnessTheme> { error("FitnessTheme not provided") }

object Fitness {
    val theme: FitnessTheme
        @Composable @ReadOnlyComposable get() = LocalFitnessTheme.current
}

/** Which colour variant a theme family uses. */
enum class Appearance(val label: String) { SYSTEM("System"), LIGHT("Light"), DARK("Dark") }

/** The themes offered in Settings. A family with a light and a dark variant follows [Appearance]; the rest are fixed. */
class ThemeFamily(val id: String, val name: String, val description: String, val light: FitnessTheme?, val dark: FitnessTheme?) {
    fun resolve(appearance: Appearance, systemDark: Boolean): FitnessTheme {
        val wantDark = when (appearance) { Appearance.SYSTEM -> systemDark; Appearance.LIGHT -> false; Appearance.DARK -> true }
        return (if (wantDark) dark ?: light else light ?: dark)!!
    }
    val switchesWithAppearance get() = light != null && dark != null
}

private val sans = FontFamily.Default
private val mono = FontFamily.Monospace

private fun zones(vararg c: Long) = c.map { Color(it) }
private val classicZones = zones(0xFF74C0FC, 0xFF51CF66, 0xFFFCC419, 0xFFFF922B, 0xFFFA5252)

private fun stravaLight() = FitnessTheme(
    id = "strava", name = "Strava", dark = false,
    background = Color(0xFFF2F2F5), surface = Color.White, surfaceAlt = Color(0xFFF7F7FA),
    text = Color(0xFF242428), muted = Color(0xFF6D6D78), accent = Color(0xFFFC4C02), onAccent = Color.White,
    divider = Color(0xFFE4E4EA), danger = Color(0xFFD7263D),
    chart = ChartColors(Color(0xFFE03131), Color(0xFF1C7ED6), Color(0xFF2F9E44), Color(0xFF7048E8), Color(0xFFF08C00),
        Color(0xFFFC4C02), classicZones),
    layout = LayoutStyle.CARDS, fontFamily = sans, numeralWeight = FontWeight.Bold,
    cornerRadius = 12.dp, borderWidth = 0.dp, borderColor = Color.Transparent, cardElevation = 1.dp,
    mapBackground = Color(0xFFE8E8EE),
)

private fun stravaDark() = FitnessTheme(
    id = "strava", name = "Strava", dark = true,
    background = Color(0xFF111114), surface = Color(0xFF1E1E23), surfaceAlt = Color(0xFF26262C),
    text = Color(0xFFF2F2F5), muted = Color(0xFFA0A0AB), accent = Color(0xFFFC4C02), onAccent = Color.White,
    divider = Color(0xFF34343B), danger = Color(0xFFFF6B6B),
    chart = ChartColors(Color(0xFFFF6B6B), Color(0xFF4DABF7), Color(0xFF69DB7C), Color(0xFFB197FC), Color(0xFFFFA94D),
        Color(0xFFFC4C02), classicZones),
    layout = LayoutStyle.CARDS, fontFamily = sans, numeralWeight = FontWeight.Bold,
    cornerRadius = 12.dp, borderWidth = 0.dp, borderColor = Color.Transparent, cardElevation = 0.dp,
    mapBackground = Color(0xFF1B1B20),
)

private fun iosLight() = FitnessTheme(
    id = "ios", name = "iOS style", dark = false,
    background = Color(0xFFF2F2F7), surface = Color.White, surfaceAlt = Color(0xFFF2F2F7),
    text = Color.Black, muted = Color(0xFF8E8E93), accent = Color(0xFF007AFF), onAccent = Color.White,
    divider = Color(0xFFC6C6C8), danger = Color(0xFFFF3B30),
    chart = ChartColors(Color(0xFFFF3B30), Color(0xFF007AFF), Color(0xFF34C759), Color(0xFFAF52DE), Color(0xFFFF9500),
        Color(0xFF007AFF), zones(0xFF5AC8FA, 0xFF34C759, 0xFFFFCC00, 0xFFFF9500, 0xFFFF3B30)),
    layout = LayoutStyle.GROUPED, fontFamily = sans, numeralWeight = FontWeight.SemiBold,
    cornerRadius = 14.dp, borderWidth = 0.dp, borderColor = Color.Transparent, cardElevation = 0.dp,
    mapBackground = Color(0xFFE5E5EA),
)

private fun iosDark() = FitnessTheme(
    id = "ios", name = "iOS style", dark = true,
    background = Color.Black, surface = Color(0xFF1C1C1E), surfaceAlt = Color(0xFF2C2C2E),
    text = Color.White, muted = Color(0xFF8E8E93), accent = Color(0xFF0A84FF), onAccent = Color.White,
    divider = Color(0xFF38383A), danger = Color(0xFFFF453A),
    chart = ChartColors(Color(0xFFFF453A), Color(0xFF0A84FF), Color(0xFF30D158), Color(0xFFBF5AF2), Color(0xFFFF9F0A),
        Color(0xFF0A84FF), zones(0xFF64D2FF, 0xFF30D158, 0xFFFFD60A, 0xFFFF9F0A, 0xFFFF453A)),
    layout = LayoutStyle.GROUPED, fontFamily = sans, numeralWeight = FontWeight.SemiBold,
    cornerRadius = 14.dp, borderWidth = 0.dp, borderColor = Color.Transparent, cardElevation = 0.dp,
    mapBackground = Color(0xFF1C1C1E),
)

/** A Unix/terminal theme: monospace, square corners, 1 dp borders, no shadows. */
private fun unix(
    id: String, name: String, dark: Boolean, bg: Long, surface: Long, text: Long, muted: Long, accent: Long, divider: Long,
    danger: Long, hr: Long, pace: Long, elev: Long, power: Long, cadence: Long, onAccent: Long = bg,
) = FitnessTheme(
    id = id, name = name, dark = dark,
    background = Color(bg), surface = Color(surface), surfaceAlt = Color(surface),
    text = Color(text), muted = Color(muted), accent = Color(accent), onAccent = Color(onAccent),
    divider = Color(divider), danger = Color(danger),
    chart = ChartColors(Color(hr), Color(pace), Color(elev), Color(power), Color(cadence), Color(accent),
        zones(pace, elev, cadence, power, hr)),
    layout = LayoutStyle.TERMINAL, fontFamily = mono, numeralWeight = FontWeight.Normal,
    cornerRadius = 0.dp, borderWidth = 1.dp, borderColor = Color(divider), cardElevation = 0.dp,
    mapBackground = Color(bg),
)

private fun terminalGreen() = unix("green", "Terminal green", true, 0xFF050A05, 0xFF0A130A, 0xFF33FF66, 0xFF1FA84A, 0xFF33FF66, 0xFF1E5A2E,
    0xFFFF5555, hr = 0xFFFF5555, pace = 0xFF33FF66, elev = 0xFF9BFFB5, power = 0xFFD7FF33, cadence = 0xFF1FA84A)

private fun amber() = unix("amber", "Amber CRT", true, 0xFF0D0800, 0xFF150E00, 0xFFFFB000, 0xFFA66F00, 0xFFFFB000, 0xFF5C3D00,
    0xFFFF6A3D, hr = 0xFFFF6A3D, pace = 0xFFFFB000, elev = 0xFFFFD37A, power = 0xFFFFE9B0, cadence = 0xFFA66F00)

private fun solarizedDark() = unix("solarized", "Solarized", true, 0xFF002B36, 0xFF073642, 0xFF93A1A1, 0xFF657B83, 0xFFB58900, 0xFF0F4B5A,
    0xFFDC322F, hr = 0xFFDC322F, pace = 0xFF268BD2, elev = 0xFF859900, power = 0xFF6C71C4, cadence = 0xFFCB4B16)

private fun solarizedLight() = unix("solarized", "Solarized", false, 0xFFFDF6E3, 0xFFEEE8D5, 0xFF586E75, 0xFF93A1A1, 0xFFB58900, 0xFFD8D2BE,
    0xFFDC322F, hr = 0xFFDC322F, pace = 0xFF268BD2, elev = 0xFF859900, power = 0xFF6C71C4, cadence = 0xFFCB4B16)

private fun gruvboxDark() = unix("gruvbox", "Gruvbox", true, 0xFF282828, 0xFF32302F, 0xFFEBDBB2, 0xFFA89984, 0xFFFE8019, 0xFF504945,
    0xFFFB4934, hr = 0xFFFB4934, pace = 0xFF83A598, elev = 0xFFB8BB26, power = 0xFFD3869B, cadence = 0xFFFABD2F)

private fun gruvboxLight() = unix("gruvbox", "Gruvbox", false, 0xFFFBF1C7, 0xFFF2E5BC, 0xFF3C3836, 0xFF7C6F64, 0xFFAF3A03, 0xFFD5C4A1,
    0xFF9D0006, hr = 0xFF9D0006, pace = 0xFF076678, elev = 0xFF79740E, power = 0xFF8F3F71, cadence = 0xFFB57614)

private fun nord() = unix("nord", "Nord", true, 0xFF2E3440, 0xFF3B4252, 0xFFECEFF4, 0xFF9AA5BA, 0xFF88C0D0, 0xFF4C566A,
    0xFFBF616A, hr = 0xFFBF616A, pace = 0xFF81A1C1, elev = 0xFFA3BE8C, power = 0xFFB48EAD, cadence = 0xFFEBCB8B)

private fun dracula() = unix("dracula", "Dracula", true, 0xFF282A36, 0xFF303341, 0xFFF8F8F2, 0xFF6272A4, 0xFFBD93F9, 0xFF44475A,
    0xFFFF5555, hr = 0xFFFF5555, pace = 0xFF8BE9FD, elev = 0xFF50FA7B, power = 0xFFFF79C6, cadence = 0xFFFFB86C)

val ThemeFamilies: List<ThemeFamily> = listOf(
    ThemeFamily("strava", "Strava", "Cards on a light grey feed, orange accent", stravaLight(), stravaDark()),
    ThemeFamily("ios", "iOS style", "Grouped lists, hairlines, segmented controls", iosLight(), iosDark()),
    ThemeFamily("green", "Unix: Terminal green", "Green on black, monospace", null, terminalGreen()),
    ThemeFamily("amber", "Unix: Amber CRT", "Amber on black, monospace", null, amber()),
    ThemeFamily("solarized", "Unix: Solarized", "Solarized dark or light", solarizedLight(), solarizedDark()),
    ThemeFamily("gruvbox", "Unix: Gruvbox", "Gruvbox dark or light", gruvboxLight(), gruvboxDark()),
    ThemeFamily("nord", "Unix: Nord", "Cool blue-grey", null, nord()),
    ThemeFamily("dracula", "Unix: Dracula", "Purple on dark", null, dracula()),
)

fun themeFamily(id: String): ThemeFamily = ThemeFamilies.firstOrNull { it.id == id } ?: ThemeFamilies.first()

/** Provides the theme to the tree and maps it onto Material 3 so stock components follow it. */
@Composable
fun FitnessThemeHost(familyId: String, appearance: Appearance, content: @Composable () -> Unit) {
    val theme = themeFamily(familyId).resolve(appearance, isSystemInDarkTheme())
    val scheme = if (theme.dark) {
        darkColorScheme(
            primary = theme.accent, onPrimary = theme.onAccent, background = theme.background, onBackground = theme.text,
            surface = theme.surface, onSurface = theme.text, surfaceVariant = theme.surfaceAlt, onSurfaceVariant = theme.muted,
            outline = theme.divider, outlineVariant = theme.divider, error = theme.danger,
            secondaryContainer = theme.surfaceAlt, onSecondaryContainer = theme.text,
        )
    } else {
        lightColorScheme(
            primary = theme.accent, onPrimary = theme.onAccent, background = theme.background, onBackground = theme.text,
            surface = theme.surface, onSurface = theme.text, surfaceVariant = theme.surfaceAlt, onSurfaceVariant = theme.muted,
            outline = theme.divider, outlineVariant = theme.divider, error = theme.danger,
            secondaryContainer = theme.surfaceAlt, onSecondaryContainer = theme.text,
        )
    }
    val base = Typography()
    fun TextStyle.themed(weight: FontWeight? = null) = copy(fontFamily = theme.fontFamily, fontWeight = weight ?: fontWeight)
    val typography = Typography(
        displayLarge = base.displayLarge.themed(theme.numeralWeight), displayMedium = base.displayMedium.themed(theme.numeralWeight),
        displaySmall = base.displaySmall.themed(theme.numeralWeight),
        headlineLarge = base.headlineLarge.themed(theme.numeralWeight), headlineMedium = base.headlineMedium.themed(theme.numeralWeight),
        headlineSmall = base.headlineSmall.themed(theme.numeralWeight),
        titleLarge = base.titleLarge.themed(theme.numeralWeight), titleMedium = base.titleMedium.themed(theme.numeralWeight),
        titleSmall = base.titleSmall.themed(), bodyLarge = base.bodyLarge.themed(), bodyMedium = base.bodyMedium.themed(),
        bodySmall = base.bodySmall.themed(), labelLarge = base.labelLarge.themed(), labelMedium = base.labelMedium.themed(),
        labelSmall = base.labelSmall.copy(fontFamily = theme.fontFamily, fontSize = 11.sp),
    )
    val r = theme.cornerRadius
    val shapes = Shapes(
        extraSmall = RoundedCornerShape(r / 3), small = RoundedCornerShape(r / 2), medium = RoundedCornerShape(r),
        large = RoundedCornerShape(r), extraLarge = RoundedCornerShape(r),
    )
    CompositionLocalProvider(LocalFitnessTheme provides theme) {
        MaterialTheme(colorScheme = scheme, typography = typography, shapes = shapes, content = content)
    }
}
