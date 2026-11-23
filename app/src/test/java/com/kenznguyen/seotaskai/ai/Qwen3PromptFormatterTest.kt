package com.kenznguyen.seotaskai.ai

import org.junit.Assert.assertTrue
import org.junit.Test

class Qwen3PromptFormatterTest {
    @Test
    fun wrapsSystemAndUserMessagesUsingQwen3ChatMlTokens() {
        val prompt = Qwen3PromptFormatter.format("system policy", "task text")
        assertTrue(prompt.startsWith("<|im_start|>system\nsystem policy<|im_end|>"))
        assertTrue(prompt.contains("<|im_start|>user\ntask text<|im_end|>"))
        assertTrue(prompt.endsWith("<|im_start|>assistant\n"))
    }
}
