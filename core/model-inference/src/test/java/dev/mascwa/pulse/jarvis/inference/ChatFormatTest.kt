package dev.mascwa.pulse.jarvis.inference

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ChatFormatTest {

    private val qwenUrl =
        "https://huggingface.co/litert-community/Qwen2.5-1.5B-Instruct/resolve/main/Qwen2.5-1.5B-Instruct_q8_ekv1280.task"
    private val gemmaUrl = "https://huggingface.co/litert-community/Gemma-3-1B-IT/resolve/main/gemma3-1b-it.task"

    @Test
    fun autoResolvesQwenToChatMl() {
        assertEquals(ChatFormat.CHATML, ChatFormat.resolve(ChatFormat.AUTO, qwenUrl))
    }

    @Test
    fun autoResolvesGemmaToGemma() {
        assertEquals(ChatFormat.GEMMA, ChatFormat.resolve(ChatFormat.AUTO, gemmaUrl))
    }

    @Test
    fun autoFallsBackToChatMlForNullOrUnknownUrl() {
        assertEquals(ChatFormat.CHATML, ChatFormat.resolve(ChatFormat.AUTO, null))
        assertEquals(ChatFormat.CHATML, ChatFormat.resolve(ChatFormat.AUTO, "https://example.com/some-model.task"))
    }

    @Test
    fun explicitFormatsArePassedThroughRegardlessOfUrl() {
        assertEquals(ChatFormat.PLAIN, ChatFormat.resolve(ChatFormat.PLAIN, gemmaUrl))
        assertEquals(ChatFormat.GEMMA, ChatFormat.resolve(ChatFormat.GEMMA, null))
        assertEquals(ChatFormat.CHATML, ChatFormat.resolve(ChatFormat.CHATML, gemmaUrl))
    }

    @Test
    fun chatmlWrapsTurnsAndOpensAssistant() {
        val out = renderPrompt(
            ChatFormat.CHATML,
            prompt = "how are you",
            history = listOf(ChatTurn("user", "hi"), ChatTurn("jarvis", "hello")),
            system = "Sys",
        )
        assertTrue(out.contains("<|im_start|>system\nSys<|im_end|>"))
        assertTrue(out.contains("<|im_start|>user\nhi<|im_end|>"))
        assertTrue(out.contains("<|im_start|>assistant\nhello<|im_end|>"))
        assertTrue(out.contains("<|im_start|>user\nhow are you<|im_end|>"))
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun chatmlOmitsSystemBlockWhenBlank() {
        val out = renderPrompt(ChatFormat.CHATML, "hi", emptyList(), system = "")
        assertFalse(out.contains("<|im_start|>system"))
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun gemmaUsesTurnTokensAndFoldsSystemIntoFirstUserTurn() {
        val out = renderPrompt(
            ChatFormat.GEMMA,
            prompt = "how are you",
            history = listOf(ChatTurn("user", "hi"), ChatTurn("jarvis", "hello")),
            system = "Sys",
        )
        assertFalse(out.contains("<|im_start|>"))
        // System has no dedicated role in Gemma — it is prepended to the first user turn only.
        assertTrue(out.contains("<start_of_turn>user\nSys\n\nhi<end_of_turn>"))
        assertTrue(out.contains("<start_of_turn>model\nhello<end_of_turn>"))
        assertTrue(out.contains("<start_of_turn>user\nhow are you<end_of_turn>"))
        assertTrue(out.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun gemmaFoldsSystemIntoCurrentTurnWhenNoHistory() {
        val out = renderPrompt(ChatFormat.GEMMA, "hello", emptyList(), system = "Sys")
        assertTrue(out.contains("<start_of_turn>user\nSys\n\nhello<end_of_turn>"))
        assertTrue(out.endsWith("<start_of_turn>model\n"))
    }

    @Test
    fun plainMatchesLegacyTranscript() {
        val out = renderPrompt(
            ChatFormat.PLAIN,
            prompt = "how are you",
            history = listOf(ChatTurn("user", "hi"), ChatTurn("jarvis", "hello")),
            system = "Sys",
        )
        assertTrue(out.startsWith("Sys\n\n"))
        assertTrue(out.contains("User: hi\n"))
        assertTrue(out.contains("J.A.R.V.I.S.: hello\n"))
        assertTrue(out.endsWith("User: how are you\nJ.A.R.V.I.S.: "))
    }

    @Test
    fun autoIsTreatedAsChatmlDefensivelyAtRenderTime() {
        val out = renderPrompt(ChatFormat.AUTO, "hi", emptyList(), system = null)
        assertTrue(out.contains("<|im_start|>user\nhi<|im_end|>"))
        assertTrue(out.endsWith("<|im_start|>assistant\n"))
    }

    @Test
    fun onlyTheMostRecentTurnsAreKept() {
        val history = (1..10).map { ChatTurn("user", "msg$it") }
        val out = renderPrompt(ChatFormat.PLAIN, "now", history, system = null, maxTurns = 2)
        assertFalse(out.contains("msg8"))
        assertTrue(out.contains("msg9"))
        assertTrue(out.contains("msg10"))
    }
}
