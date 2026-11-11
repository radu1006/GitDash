package com.agentroost.gitdashboard.ui.readme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp
import com.agentroost.gitdashboard.ui.theme.AppTypography
import com.agentroost.gitdashboard.ui.theme.DarkColors
import com.agentroost.gitdashboard.ui.theme.LightColors
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

/**
 * Resolution of the markdown body text colour.
 *
 * Compose's foundation text only paints the colour carried by the [TextStyle], so a typography style
 * with no colour renders black. These tests pin the scheme colour that must be applied instead.
 */
class ReadmeBodyTextStyleTest {

    @Test
    fun `dark body text uses the dark scheme onSurface colour`() {
        assertEquals(
            Color(0xFFE6EDF3),
            markdownBodyTextStyle(AppTypography.bodyLarge, DarkColors.onSurface).color,
        )
    }

    @Test
    fun `light body text uses the light scheme onSurface colour`() {
        assertEquals(
            Color(0xFF1F2328),
            markdownBodyTextStyle(AppTypography.bodyLarge, LightColors.onSurface).color,
        )
    }

    @Test
    fun `typography alone is uncoloured, so the old unspecified colour never matches`() {
        assertEquals(Color.Unspecified, AppTypography.bodyLarge.color)
        assertNotEquals(
            Color(0xFFE6EDF3),
            markdownBodyTextStyle(AppTypography.bodyLarge, Color.Unspecified).color,
        )
    }

    @Test
    fun `resolution only replaces the colour`() {
        val base = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 22.sp)
        val resolved = markdownBodyTextStyle(base, DarkColors.onSurface)
        assertEquals(DarkColors.onSurface, resolved.color)
        assertEquals(FontWeight.SemiBold, resolved.fontWeight)
        assertEquals(22.sp, resolved.fontSize)
    }
}
