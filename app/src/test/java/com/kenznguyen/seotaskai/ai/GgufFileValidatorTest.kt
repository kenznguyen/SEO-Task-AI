package com.kenznguyen.seotaskai.ai

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GgufFileValidatorTest {
    @Test
    fun acceptsValidGgufHeaderAndMinimumSize() {
        GgufFileValidator.validateHeader(byteArrayOf(71, 71, 85, 70), GgufFileValidator.MIN_MODEL_BYTES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsNonGgufFile() {
        GgufFileValidator.validateHeader(byteArrayOf(80, 75, 3, 4), GgufFileValidator.MIN_MODEL_BYTES)
    }

    @Test(expected = IllegalArgumentException::class)
    fun rejectsTooSmallFile() {
        GgufFileValidator.validateHeader(byteArrayOf(71, 71, 85, 70), 32)
    }

    @Test
    fun requiresFivePercentStorageHeadroom() {
        assertTrue(GgufFileValidator.hasEnoughSpace(1_344_000_000, 1_280_000_000))
        assertFalse(GgufFileValidator.hasEnoughSpace(1_300_000_000, 1_280_000_000))
    }
}
