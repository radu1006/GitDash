package com.agentroost.gitdashboard.data.auth

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The only part of token storage that is plain JVM logic: the display mask. */
class TokenRepositoryTest {

    @Test
    fun `nothing is shown for a missing or blank token`() {
        assertNull(TokenRepository.mask(null))
        assertNull(TokenRepository.mask(""))
        assertNull(TokenRepository.mask("   "))
    }

    @Test
    fun `short tokens are fully masked`() {
        assertEquals("…", TokenRepository.mask("ghp_1"))
        assertEquals("…", TokenRepository.mask("12345678"))
    }

    @Test
    fun `long tokens keep only the first and last four characters`() {
        assertEquals("ghp_…9f2c", TokenRepository.mask("ghp_123456789abcdef9f2c"))
    }
}
