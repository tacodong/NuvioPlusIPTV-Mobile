package com.nuvio.app.features.streams

import android.app.Application
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CompletableJob
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.coroutines.CoroutineContext
import kotlin.test.assertEquals
import kotlin.test.assertSame

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = Application::class)
@OptIn(ExperimentalCoroutinesApi::class)
class ActiveStreamRecoveryTest {
    @Before fun installMainDispatcher() { Dispatchers.setMain(StandardTestDispatcher()) }
    @After fun resetMainDispatcher() { Dispatchers.resetMain() }

    @Test fun aQueuedRecoveryCannotCrossAProfileChangeOrNewerUiSelection() = runTest {
        // Independently queue Main delivery after the background eligibility check passed.
        val mainQueue = ArrayDeque<Runnable>()
        Dispatchers.setMain(object : CoroutineDispatcher() {
            override fun dispatch(context: CoroutineContext, block: Runnable) { mainQueue.addLast(block) }
        })
        fun deliverMain() { while (mainQueue.isNotEmpty()) mainQueue.removeFirst().run() }
        var profile = 1
        var oldRetries = 0
        var newerRetries = 0
        val observer = ActiveStreamRecovery(backgroundScope) { profile }
        fun request(retry: () -> Unit) = ActiveStreamRecovery.Request(1, Job().apply { complete() },
            StreamSourceRecovery.revision.value, { true }, retry)
        observer.watch(request { oldRetries++ })
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        assertEquals(0, oldRetries)
        profile = 2
        deliverMain()
        runCurrent()
        assertEquals(0, oldRetries)

        profile = 1
        observer.watch(request { oldRetries++ })
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        observer.watch(request { newerRetries++ })
        deliverMain()
        runCurrent()
        assertEquals(0, oldRetries)
        assertEquals(0, newerRetries)
    }

    @Test fun recoveryWaitsForTheExistingSearchInsteadOfInterruptingIt() = runTest {
        var retries = 0
        val job = Job()
        val observer = ActiveStreamRecovery(backgroundScope) { 1 }
        observer.watch(ActiveStreamRecovery.Request(1, job, StreamSourceRecovery.revision.value, { true }, { retries++ }))
        runCurrent()
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        assertEquals(0, retries)
        job.complete()
        runCurrent()
        assertEquals(1, retries)
    }

    @Test fun switchingProfileOrLeavingTheListDiscardsThePendingRetry() = runTest {
        var profile = 1
        var retries = 0
        val job = Job()
        val observer = ActiveStreamRecovery(backgroundScope) { profile }
        observer.watch(ActiveStreamRecovery.Request(1, job, StreamSourceRecovery.revision.value, { true }, { retries++ }))
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        profile = 2
        job.complete()
        runCurrent()
        assertEquals(0, retries)

        profile = 1
        val next = Job()
        observer.watch(ActiveStreamRecovery.Request(1, next, StreamSourceRecovery.revision.value, { true }, { retries++ }))
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        observer.cancel()
        next.complete()
        runCurrent()
        assertEquals(0, retries)
    }

    @Test fun aLateFailureCheckCannotReplaceANewerSelection() = runTest {
        var retries = 0
        val checkStarted = CompletableDeferred<Unit>()
        val finishCheck = CompletableDeferred<Unit>()
        val observer = ActiveStreamRecovery(backgroundScope) { 1 }
        val job = Job().apply { complete() }
        observer.watch(ActiveStreamRecovery.Request(1, job, StreamSourceRecovery.revision.value,
            { checkStarted.complete(Unit); finishCheck.await(); true }, { retries++ }))
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        checkStarted.await()
        observer.cancel()
        finishCheck.complete(Unit)
        runCurrent()
        assertEquals(0, retries)
    }

    @Test fun aNewRecoveryDuringARetryWaitsThenRetriesTheLatestGenerationOnce() = runTest {
        var retries = 0
        val attempts = mutableListOf<CompletableJob>()
        val observer = ActiveStreamRecovery(backgroundScope) { 1 }
        fun watchAttempt() {
            val job = Job().also { attempts += it }
            observer.watch(ActiveStreamRecovery.Request(1, job, StreamSourceRecovery.revision.value,
                { true }, { retries++; watchAttempt() }))
        }
        watchAttempt()
        attempts[0].complete()
        runCurrent()
        StreamSourceRecovery.networkRecovered()
        runCurrent()
        assertEquals(1, retries)

        StreamSourceRecovery.networkRecovered()
        runCurrent()
        assertEquals(1, retries)
        attempts[1].complete()
        runCurrent()
        assertEquals(2, retries)

        attempts[2].complete()
        runCurrent()
        assertEquals(2, retries)
    }

    @Test fun automaticRecoveryKeepsSuccessfulRowsWhileFailedSourcesLoad() {
        val good = AddonStreamGroup("Good", "good", listOf(StreamItem(url = "https://example.invalid/good", addonName = "Good", addonId = "good")))
        val failed = AddonStreamGroup("Retry", "retry", emptyList(), error = "offline")
        val previous = StreamsUiState(groups = listOf(good, failed), selectedFilter = "good")
        val loading = previous.groups.map { it.copy(streams = emptyList(), isLoading = true) }.recoveringGroups(previous)
        assertSame(good, loading.first())
        assertEquals(true, loading.last().isLoading)
    }
}
