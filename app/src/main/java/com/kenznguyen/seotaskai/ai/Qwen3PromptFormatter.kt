package com.kenznguyen.seotaskai.ai

/** Qwen3 ChatML wrapper configured for concise, non-thinking task classification. */
object Qwen3PromptFormatter {
    fun format(system: String, user: String): String =
        "<|im_start|>system\n$system\n/no_think<|im_end|>\n" +
            "<|im_start|>user\n$user<|im_end|>\n" +
            "<|im_start|>assistant\n"
}
