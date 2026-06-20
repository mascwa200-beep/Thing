package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.jarvis.tool.Tool
import dev.mascwa.pulse.jarvis.tool.ToolResult

/**
 * Chains multiple tools together, piping the output of one tool as input to the next.
 * Enables complex workflows like maps → weather or other sequential operations.
 */
class ToolChain(private val tools: List<Tool>) {

    /**
     * Executes a sequence of tools, passing the output of each tool as input to the next.
     *
     * @param initialInput The input for the first tool in the chain
     * @return The result of the final tool in the chain
     * @throws IllegalArgumentException if the chain is empty
     * @throws IllegalStateException if any tool in the chain fails
     */
    suspend fun execute(initialInput: String): ToolResult {
        require(tools.isNotEmpty()) { "Tool chain cannot be empty" }

        var currentInput = initialInput
        var lastResult: ToolResult? = null

        for (tool in tools) {
            lastResult = tool.execute(currentInput)
            
            if (!lastResult.success) {
                throw IllegalStateException(
                    "Tool chain failed at '${tool.name}': ${lastResult.message}"
                )
            }

            currentInput = lastResult.output
        }

        return lastResult ?: throw IllegalStateException("Tool chain produced no result")
    }

    /**
     * Executes a sequence of tools with error recovery.
     * Returns a result even if intermediate tools fail.
     *
     * @param initialInput The input for the first tool in the chain
     * @return The result of the last successfully executed tool
     */
    suspend fun executeWithRecovery(initialInput: String): ToolResult {
        require(tools.isNotEmpty()) { "Tool chain cannot be empty" }

        var currentInput = initialInput
        var lastResult: ToolResult? = null

        for (tool in tools) {
            lastResult = tool.execute(currentInput)
            
            if (!lastResult.success) {
                break
            }

            currentInput = lastResult.output
        }

        return lastResult ?: ToolResult(
            success = false,
            output = initialInput,
            message = "Tool chain produced no result"
        )
    }

    /**
     * Gets the names of all tools in the chain.
     */
    fun getChainDescription(): String = tools.joinToString(" → ") { it.name }
}