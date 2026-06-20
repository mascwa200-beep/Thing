package dev.mascwa.pulse.jarvis.agent

import dev.mascwa.pulse.core.telemetry.MemoryStream
import dev.mascwa.pulse.cerebellum.CerebellumStore
import dev.mascwa.pulse.profile.ProfileStore
import kotlinx.coroutines.flow.firstOrNull

class RecallLinkerTool(
    private val memoryStream: MemoryStream,
    private val profileStore: ProfileStore,
    private val cerebellumStore: CerebellumStore
) {

    suspend fun retrieveWithCrossReferences(query: String): RecallResult {
        // Retrieve primary entries from memory stream
        val primaryEntries = memoryStream.search(query)

        // Extract project and task mentions from query and results
        val mentionedProjects = extractProjectMentions(query, primaryEntries)
        val mentionedTasks = extractTaskMentions(query, primaryEntries)

        // Cross-reference with ProfileStore for project-related context
        val profileContext = mentionedProjects.mapNotNull { projectId ->
            profileStore.getProjectById(projectId).firstOrNull()
        }

        // Cross-reference with CerebellumStore for task-related context
        val cerebellumContext = mentionedTasks.mapNotNull { taskId ->
            cerebellumStore.getTaskById(taskId).firstOrNull()
        }

        // Combine all related entries
        val relatedEntries = primaryEntries + profileContext + cerebellumContext

        return RecallResult(
            primaryEntries = primaryEntries,
            relatedProfiles = profileContext,
            relatedTasks = cerebellumContext,
            allEntries = relatedEntries.distinctBy { it.id }
        )
    }

    private suspend fun extractProjectMentions(
        query: String,
        entries: List<MemoryEntry>
    ): List<String> {
        val projectKeywords = listOf("project", "proj", "initiative")
        val mentionedIds = mutableListOf<String>()

        // Search query for project keywords
        projectKeywords.forEach { keyword ->
            if (query.contains(keyword, ignoreCase = true)) {
                val ids = profileStore.searchProjects(query).firstOrNull() ?: emptyList()
                mentionedIds.addAll(ids.map { it.id })
            }
        }

        // Extract from memory entries
        entries.forEach { entry ->
            val extractedIds = entry.extractProjectIds()
            mentionedIds.addAll(extractedIds)
        }

        return mentionedIds.distinct()
    }

    private suspend fun extractTaskMentions(
        query: String,
        entries: List<MemoryEntry>
    ): List<String> {
        val taskKeywords = listOf("task", "todo", "action item")
        val mentionedIds = mutableListOf<String>()

        // Search query for task keywords
        taskKeywords.forEach { keyword ->
            if (query.contains(keyword, ignoreCase = true)) {
                val ids = cerebellumStore.searchTasks(query).firstOrNull() ?: emptyList()
                mentionedIds.addAll(ids.map { it.id })
            }
        }

        // Extract from memory entries
        entries.forEach { entry ->
            val extractedIds = entry.extractTaskIds()
            mentionedIds.addAll(extractedIds)
        }

        return mentionedIds.distinct()
    }

    data class RecallResult(
        val primaryEntries: List<MemoryEntry>,
        val relatedProfiles: List<ProfileEntry>,
        val relatedTasks: List<CerebellumEntry>,
        val allEntries: List<Any>
    )

    interface MemoryEntry {
        val id: String
        fun extractProjectIds(): List<String>
        fun extractTaskIds(): List<String>
    }

    interface ProfileEntry {
        val id: String
    }

    interface CerebellumEntry {
        val id: String
    }
}