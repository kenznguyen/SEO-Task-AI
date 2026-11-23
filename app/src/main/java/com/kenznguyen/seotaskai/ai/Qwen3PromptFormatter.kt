package com.kenznguyen.seotaskai.ai

/** Qwen3's ChatML conversation wrapper; task text remains untrusted user data. */
object Qwen3PromptFormatter {
    fun format(system: String, user: String): String =
        "<|im_start|>system\n$system<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n" +
            "<|im_start|>assistant\n"
}
