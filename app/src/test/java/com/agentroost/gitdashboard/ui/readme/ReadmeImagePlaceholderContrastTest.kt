package com.agentroost.gitdashboard.ui.readme

import androidx.compose.ui.graphics.Color
import com.agentroost.gitdashboard.ui.theme.DarkColors
import com.agentroost.gitdashboard.ui.theme.LightColors
import com.agentroost.gitdashboard.ui.theme.OnPrimaryContainerDark
import com.agentroost.gitdashboard.ui.theme.OnPrimaryContainerLight
import com.agentroost.gitdashboard.ui.theme.SurfaceContainerHighDark
import com.agentroost.gitdashboard.ui.theme.SurfaceContainerHighLight
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.pow

/**
 * Contrast of the README image placeholder labels.
 *
 * The "Open image" affordance was painted in `primary`, which is only 4.45:1 on the light
 * placeholder surface - a WCAG AA miss for 11sp text. These tests compute the real WCAG 2.1
 * relative-luminance ratio from the colours the screen resolves, so a scheme edit that drops the
 * label back under 4.5:1 fails here instead of shipping an unreadable affordance.
 */
class ReadmeImagePlaceholderContrastTest {

    @Test
    fun `light Open image label clears 4_5 to 1 on the placeholder surface`() {
        val surface = readmeImagePlaceholderSurface(LightColors)
        val label = readmeImageOpenLabelColor(LightColors)

        assertEquals(SurfaceContainerHighLight, surface)
        assertTrue(
            "light label $label on $surface is ${contrastRatio(label, surface)}:1",
            contrastRatio(label, surface) >= 4.5,
        )
        assertEquals(OnPrimaryContainerLight, label)
    }

    @Test
    fun `dark Open image label clears 4_5 to 1 on the placeholder surface`() {
        val surface = readmeImagePlaceholderSurface(DarkColors)
        val label = readmeImageOpenLabelColor(DarkColors)

        assertEquals(SurfaceContainerHighDark, surface)
        assertTrue(
            "dark label $label on $surface is ${contrastRatio(label, surface)}:1",
            contrastRatio(label, surface) >= 4.5,
        )
        assertEquals(OnPrimaryContainerDark, label)
    }

    @Test
    fun `the placeholder's secondary labels keep 4_5 to 1 in both themes`() {
        for ((theme, scheme) in listOf("light" to LightColors, "dark" to DarkColors)) {
            val surface = readmeImagePlaceholderSurface(scheme)
            val ratio = contrastRatio(scheme.onSurfaceVariant, surface)
            assertTrue("$theme onSurfaceVariant on $surface is $ratio:1", ratio >= 4.5)
        }
    }

    /** WCAG 2.1 contrast ratio between two opaque sRGB colours; 1.0 (identical) to 21.0. */
    private fun contrastRatio(a: Color, b: Color): Double {
        val luminanceA = relativeLuminance(a)
        val luminanceB = relativeLuminance(b)
        val lighter = maxOf(luminanceA, luminanceB)
        val darker = minOf(luminanceA, luminanceB)
        return (lighter + 0.05) / (darker + 0.05)
    }

    private fun relativeLuminance(color: Color): Double =
        0.2126 * channelLuminance(color.red) +
            0.7152 * channelLuminance(color.green) +
            0.0722 * channelLuminance(color.blue)

    private fun channelLuminance(channel: Float): Double {
        val value = channel.toDouble()
        return if (value <= 0.03928) value / 12.92 else ((value + 0.055) / 1.055).pow(2.4)
    }
}
