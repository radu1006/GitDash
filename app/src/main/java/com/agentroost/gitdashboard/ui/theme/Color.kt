package com.agentroost.gitdashboard.ui.theme

import androidx.compose.ui.graphics.Color

// ---------------------------------------------------------------- brand / accents

internal val Blue40 = Color(0xFF0969DA)
internal val Blue80 = Color(0xFF58A6FF)
internal val Green40 = Color(0xFF1A7F37)
internal val Green80 = Color(0xFF3FB950)
internal val Slate40 = Color(0xFF57606A)
internal val Slate80 = Color(0xFF8B949E)
internal val Red40 = Color(0xFFCF222E)
internal val Red80 = Color(0xFFFF7B72)

// ---------------------------------------------------------------- light surfaces

internal val SurfaceLight = Color(0xFFF6F8FA)
internal val OnSurfaceLight = Color(0xFF1F2328)
internal val SurfaceVariantLight = Color(0xFFEAEEF2)
internal val OnSurfaceVariantLight = Color(0xFF57606A)
internal val SurfaceContainerLowestLight = Color(0xFFFFFFFF)
internal val SurfaceContainerLowLight = Color(0xFFF6F8FA)
internal val SurfaceContainerLight = Color(0xFFF0F3F6)
internal val SurfaceContainerHighLight = Color(0xFFEAEEF2)
internal val SurfaceContainerHighestLight = Color(0xFFE4E8EC)
internal val SurfaceBrightLight = Color(0xFFFFFFFF)
internal val SurfaceDimLight = Color(0xFFD8DEE4)
internal val OutlineLight = Color(0xFFD0D7DE)
internal val OutlineVariantLight = Color(0xFFD8DEE4)

// ---------------------------------------------------------------- dark surfaces

internal val BackgroundDark = Color(0xFF0D1117)
internal val SurfaceDark = Color(0xFF161B22)
internal val OnSurfaceDark = Color(0xFFE6EDF3)
internal val SurfaceVariantDark = Color(0xFF21262D)
internal val OnSurfaceVariantDark = Color(0xFF8B949E)
internal val SurfaceContainerLowestDark = Color(0xFF010409)
internal val SurfaceContainerLowDark = Color(0xFF0D1117)
internal val SurfaceContainerHighDark = Color(0xFF1C2128)
internal val SurfaceContainerHighestDark = Color(0xFF22272E)
internal val SurfaceBrightDark = Color(0xFF24292F)
internal val SurfaceDimDark = Color(0xFF010409)
internal val OutlineDark = Color(0xFF3D444D)
internal val OutlineVariantDark = Color(0xFF30363D)

// ---------------------------------------------------------------- light containers

internal val OnPrimaryLight = Color(0xFFFFFFFF)
internal val PrimaryContainerLight = Color(0xFFCFE8FF)
internal val OnPrimaryContainerLight = Color(0xFF0A3069)
internal val OnSecondaryLight = Color(0xFFFFFFFF)
internal val SecondaryContainerLight = Color(0xFFDAFBE1)
internal val OnSecondaryContainerLight = Color(0xFF04260F)
internal val OnTertiaryLight = Color(0xFFFFFFFF)
internal val TertiaryContainerLight = Color(0xFFEAEEF2)
internal val OnTertiaryContainerLight = Color(0xFF24292F)
internal val OnErrorLight = Color(0xFFFFFFFF)
internal val ErrorContainerLight = Color(0xFFFFEBE9)
internal val OnErrorContainerLight = Color(0xFF82071E)

// ---------------------------------------------------------------- dark containers

internal val OnPrimaryDark = Color(0xFF002E69)
internal val PrimaryContainerDark = Color(0xFF0C2D6B)
internal val OnPrimaryContainerDark = Color(0xFFCFE8FF)
internal val OnSecondaryDark = Color(0xFF00391C)
internal val SecondaryContainerDark = Color(0xFF033A16)
internal val OnSecondaryContainerDark = Color(0xFF7EE2A8)
internal val OnTertiaryDark = Color(0xFF24292F)
internal val TertiaryContainerDark = Color(0xFF2D333B)
internal val OnTertiaryContainerDark = Color(0xFFC9D1D9)
internal val OnErrorDark = Color(0xFF67060C)
internal val ErrorContainerDark = Color(0xFF8E1519)
internal val OnErrorContainerDark = Color(0xFFFFDAD6)

// ---------------------------------------------------------------- code viewer
//
// The highlighter always tokenises with [CodePaletteDark]; the viewer remaps the resulting span
// colours onto the active palette so the code surface follows the app's light/dark theme.

internal data class CodePalette(
    val background: Color,
    val gutterBackground: Color,
    val text: Color,
    val gutterText: Color,
    val keyword: Color,
    val stringLiteral: Color,
    val comment: Color,
    val number: Color,
    val property: Color,
    val annotation: Color,
    val tag: Color,
    val attribute: Color,
    val heading: Color,
)

internal val CodePaletteDark = CodePalette(
    background = Color(0xFF0D1117),
    gutterBackground = Color(0xFF161B22),
    text = Color(0xFFE6EDF3),
    gutterText = Color(0xFF6E7681),
    keyword = Color(0xFFFF7B72),
    stringLiteral = Color(0xFFA5D6FF),
    comment = Color(0xFF8B949E),
    number = Color(0xFF79C0FF),
    property = Color(0xFF7EE787),
    annotation = Color(0xFFD2A8FF),
    tag = Color(0xFF7EE787),
    attribute = Color(0xFF79C0FF),
    heading = Color(0xFFFFA657),
)

internal val CodePaletteLight = CodePalette(
    background = Color(0xFFFFFFFF),
    gutterBackground = Color(0xFFF6F8FA),
    text = Color(0xFF1F2328),
    gutterText = Color(0xFF8C959F),
    keyword = Color(0xFFCF222E),
    stringLiteral = Color(0xFF0A3069),
    comment = Color(0xFF6E7781),
    number = Color(0xFF0550AE),
    property = Color(0xFF116329),
    annotation = Color(0xFF8250DF),
    tag = Color(0xFF116329),
    attribute = Color(0xFF0550AE),
    heading = Color(0xFF953800),
)
