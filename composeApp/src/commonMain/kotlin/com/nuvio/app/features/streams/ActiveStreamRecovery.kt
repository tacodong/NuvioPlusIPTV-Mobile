package com.nuvio.app.features.streams

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** Retries only the still-visible request, after its existing work finishes. */
internal class ActiveStreamRecovery(scope: CoroutineScope, currentProfile: () -> Int) {
    class Request(
        val profile: Int,
        val job: Job,
        val handledRevision: Long,
        val hasFailures: suspend () -> Boolean,
        val retry: () -> Unit,
    )

    private val active = MutableStateFlow<Request?>(null)

    init {
        scope.launch {
            combine(active, StreamSourceRecovery.revision) { request, revision -> request to revision }
                .collectLatest { (request, revision) ->
                    if (request == null || revision <= request.handledRevision) return@collectLatest
                    request.job.join()
                    if (active.value !== request || currentProfile() != request.profile ||
                        revision <= request.handledRevision || !request.hasFailures()) return@collectLatest
                    // UI load/clear/profile changes run on Main. Commit the retry there too,
                    // rechecking after dispatch so an old result cannot replace a newer selection.
                    withContext(Dispatchers.Main.immediate) {
                        if (active.value === request && currentProfile() == request.profile) request.retry()
                    }
                }
        }
    }

    fun watch(request: Request) { active.value = request }
    fun cancel() { active.value = null }
}

internal fun StreamsUiState.hasSourceFailures(): Boolean = groups.any { !it.error.isNullOrBlank() } ||
    emptyStateReason == StreamsEmptyStateReason.StreamFetchFailed

/** Keep successful/prepared rows and the selected filter visible during automatic recovery. */
internal fun List<AddonStreamGroup>.recoveringGroups(previous: StreamsUiState): List<AddonStreamGroup> {
    val old = previous.groups.associateBy { it.addonId }
    return map { loading ->
        old[loading.addonId]?.let { group ->
            if (group.error.isNullOrBlank()) group else group.copy(error = null, isLoading = true)
        } ?: loading
    }
}
