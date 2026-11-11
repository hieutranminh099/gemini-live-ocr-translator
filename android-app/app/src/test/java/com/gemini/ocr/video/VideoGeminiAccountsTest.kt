package com.gemini.ocr.video

import com.gemini.ocr.settings.GeminiKeyMode
import com.gemini.ocr.settings.GeminiKeySlot
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.junit.Assert.*
import org.junit.Test

class VideoGeminiAccountsTest {
    private val slots = listOf(GeminiKeySlot(1, "fixture-primary"),
        GeminiKeySlot(2, "fixture-secondary"), GeminiKeySlot(3, "fixture-third"))

    private class Fixture {
        var now = 1_000L
        val durable = mutableMapOf<String, GeminiAccountCooldown>()
        val waits = mutableListOf<Long>()
        val turns = mutableListOf<Int>()
        fun pool() = GeminiAccountPool(
            load = { durable[it] },
            save = { identity, cooldown -> if (cooldown == null) durable.remove(identity) else durable[identity] = cooldown },
            clockMs = { now },
            sleep = { waits += it; now += it },
            awaitTurn = { slot, _ -> turns += slot.slot }
        )
    }

    @Test fun adaptiveBatchAdmissionConstrainsMultiKeyRequestsWithoutBypassingKeyCooldowns() = runBlocking {
        val fixture = Fixture()
        val pool = fixture.pool()
        VideoApiRecovery.withAdaptiveBatch(3) {
            var dispatches = 0
            val recovered = pool.request(slots, GeminiKeyMode.BALANCED, { dispatches++ }) { key ->
                if (key == slots[0].key) throw VideoApiException("API_RATE_LIMIT", transient = true)
                "accepted"
            }
            assertEquals("accepted", recovered)
            assertEquals(2, dispatches)
            assertEquals(VideoRequestCapacity(1, 3), VideoApiRecovery.currentCapacity())
            assertEquals("API_RATE_LIMIT", fixture.durable.values.single().code)
            assertTrue(fixture.durable.values.single().untilMs >= fixture.now + 61_000)
            coroutineScope {
                val oneEntered = CompletableDeferred<Unit>()
                val release = CompletableDeferred<Unit>()
                var active = 0; var peak = 0; var entered = 0
                val requests = List(3) {
                    async {
                        pool.request(slots, GeminiKeyMode.BALANCED, { dispatches++ }) { key ->
                            assertNotEquals(slots[0].key, key)
                            active++; peak = maxOf(peak, active); entered++
                            oneEntered.complete(Unit)
                            try { release.await(); "valid" } finally { active-- }
                        }
                    }
                }
                oneEntered.await(); yield()
                assertEquals(1, entered)
                release.complete(Unit)
                requests.forEach { it.await() }
                assertEquals(1, peak)
                assertEquals(5, dispatches)
                assertEquals(VideoRequestCapacity(2, 3), VideoApiRecovery.currentCapacity())
            }
        }
    }

    @Test fun authenticationFailoverDoesNotReduceHealthyKeyCapacity() = runBlocking {
        val fixture = Fixture()
        VideoApiRecovery.withAdaptiveBatch(3) {
            val result = fixture.pool().request(slots, GeminiKeyMode.FAILOVER, {}) { key ->
                if (key == slots[0].key) throw VideoApiException("API_AUTH")
                "healthy"
            }
            assertEquals("healthy", result)
            assertEquals(VideoRequestCapacity(3, 3), VideoApiRecovery.currentCapacity())
        }
    }

    @Test fun failoverUsesNextKeyAndReopenedPoolKeepsThePrimaryCooldown() = runBlocking {
        val fixture = Fixture()
        val calls = mutableListOf<String>()
        var checkpoints = 0
        var physicalDispatches = 0
        val result = fixture.pool().request(slots, GeminiKeyMode.FAILOVER, { physicalDispatches++ },
            onRetry = { _, _, _ -> checkpoints++ }) { key ->
            calls += key
            if (key == slots.first().key) throw VideoApiException("API_AUTH")
            "accepted"
        }
        assertEquals("accepted", result)
        assertEquals(slots.take(2).map { it.key }, calls)
        assertEquals(2, physicalDispatches)
        assertEquals(0, checkpoints)
        assertEquals(1, fixture.durable.size)
        assertTrue(fixture.durable.keys.all { it.matches(Regex("[a-f0-9]{64}")) })

        calls.clear()
        fixture.pool().request(slots, GeminiKeyMode.FAILOVER, {}) { calls += it; "next" }
        assertEquals(listOf(slots[1].key), calls)
        assertTrue(fixture.waits.isEmpty())
    }

    @Test fun balancedRequestsOverlapAcrossThreeKeysAndQueueOnlyTheFourth() = runBlocking {
        val fixture = Fixture()
        val pool = fixture.pool()
        val threeEntered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val activeKeys = mutableSetOf<String>()
        var entered = 0
        var peak = 0
        coroutineScope {
            val requests = List(4) {
                async {
                    pool.request(slots, GeminiKeyMode.BALANCED, {}) { key ->
                        assertTrue("A credential must have only one active request", activeKeys.add(key))
                        entered++
                        peak = maxOf(peak, activeKeys.size)
                        if (entered == 3) threeEntered.complete(Unit)
                        try { release.await(); key } finally { activeKeys.remove(key) }
                    }
                }
            }
            threeEntered.await()
            yield()
            assertEquals(3, entered)
            assertEquals(slots.map { it.key }.toSet(), activeKeys)
            release.complete(Unit)
            requests.forEach { it.await() }
        }
        assertEquals(3, peak)
        assertEquals(4, entered)
        assertTrue(activeKeys.isEmpty())
    }

    @Test fun rateLimitFallsThroughReadyKeysBeforeReturningTheEarliestDurableDeadline() = runBlocking {
        val fixture = Fixture()
        val selected = mutableListOf<String>()
        val checkpoint = mutableListOf<GeminiAccountCooldown>()
        var physicalDispatches = 0
        try {
            fixture.pool().request(slots, GeminiKeyMode.FAILOVER, { physicalDispatches++ },
                onRetry = { _, until, code -> checkpoint += GeminiAccountCooldown(until, code) }) { key ->
                selected += key
                throw VideoApiException("API_RATE_LIMIT", true,
                    retryAfterMs = when (key) {
                        slots[0].key -> 90_000
                        slots[1].key -> 70_000
                        else -> 120_000
                    })
            }
            fail("All three keys must enter cooldown")
        } catch (error: VideoApiException) {
            assertEquals("API_RATE_LIMIT", error.code)
            assertEquals(71_000L, error.nextRetryAt)
            assertFalse(error.message.orEmpty().contains("fixture"))
        }
        assertEquals(slots.map { it.key }, selected)
        assertEquals(3, physicalDispatches)
        assertEquals(listOf(GeminiAccountCooldown(71_000L, "API_RATE_LIMIT")), checkpoint)
        assertTrue(fixture.waits.isEmpty())
    }

    @Test fun duplicateTrimmedKeysShareOneBudgetAndCannotBeReplayedAsAnotherAccount() = runBlocking {
        val fixture = Fixture()
        val configured = listOf(slots[0], GeminiKeySlot(2, " ${slots[0].key} "), slots[2])
        val selected = mutableListOf<String>()
        try {
            fixture.pool().request(configured, GeminiKeyMode.FAILOVER, {}) { key ->
                selected += key
                throw VideoApiException("API_AUTH")
            }
            fail("Both distinct keys are unavailable")
        } catch (error: VideoApiException) { assertEquals("API_AUTH", error.code) }
        assertEquals(listOf(slots[0].key, slots[2].key), selected)
        assertEquals(2, fixture.durable.size)
    }

    @Test fun transientFailureHasTwoBackoffWaitsAndAtMostThreePhysicalAttempts() = runBlocking {
        val fixture = Fixture()
        var attempts = 0
        var dispatches = 0
        val retryDeadlines = mutableListOf<Long>()
        val result = fixture.pool().request(slots.take(1), GeminiKeyMode.FAILOVER, { dispatches++ },
            onRetry = { _, until, _ -> retryDeadlines += until }) {
            attempts++
            if (attempts < 3) throw VideoApiException("API_SERVICE_503", true)
            "success"
        }
        assertEquals("success", result)
        assertEquals(3, attempts)
        assertEquals(3, dispatches)
        assertEquals(listOf(1_000L, 2_000L), fixture.waits)
        assertEquals(listOf(2_000L, 4_000L), retryDeadlines)
        assertTrue(fixture.durable.isEmpty())
    }

    @Test fun cancellingAWaitingRequestAndTheOwnerReleasesTheLease() = runBlocking {
        val fixture = Fixture()
        val pool = fixture.pool()
        val entered = CompletableDeferred<Unit>()
        var operationCalls = 0
        val owner = launch {
            pool.request(slots.take(1), GeminiKeyMode.BALANCED, {}) {
                operationCalls++
                entered.complete(Unit)
                awaitCancellation()
            }
        }
        entered.await()
        val queued = launch {
            pool.request(slots.take(1), GeminiKeyMode.BALANCED, {}) { operationCalls++; "queued" }
        }
        yield()
        queued.cancelAndJoin()
        assertEquals(1, operationCalls)
        owner.cancelAndJoin()
        assertEquals("resumed", pool.request(slots.take(1), GeminiKeyMode.BALANCED, {}) { "resumed" })
        assertTrue(fixture.durable.isEmpty())
    }

    @Test fun callerQuotaGuardAndInvalidRequestNeverTriggerKeySwitching() = runBlocking {
        val fixture = Fixture()
        var calls = 0
        try {
            fixture.pool().request(slots, GeminiKeyMode.FAILOVER,
                { throw VideoQuotaWaitException("Synthetic saved wait", 50_000) }) { calls++; "unused" }
            fail("Caller quota guard must propagate")
        } catch (error: VideoQuotaWaitException) { assertEquals(50_000L, error.nextRetryAt) }
        assertEquals(0, calls)
        try {
            fixture.pool().request(slots, GeminiKeyMode.FAILOVER, {}) {
                calls++
                throw VideoApiException("API_REQUEST_413", splitAllowed = true)
            }
            fail("Invalid payload needs the existing split handling")
        } catch (error: VideoApiException) { assertEquals("API_REQUEST_413", error.code) }
        assertEquals(1, calls)
        assertTrue(fixture.durable.isEmpty())
    }

    @Test fun failoverWaitsForHealthyPrimaryInsteadOfUsingAStandbyConcurrently() = runBlocking {
        val fixture = Fixture()
        val pool = fixture.pool()
        val entered = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        val selected = mutableListOf<String>()
        val first = async {
            pool.request(slots, GeminiKeyMode.FAILOVER, {}) {
                selected += it
                entered.complete(Unit)
                release.await()
                "first"
            }
        }
        entered.await()
        val second = async {
            pool.request(slots, GeminiKeyMode.FAILOVER, {}) { selected += it; "second" }
        }
        yield()
        assertEquals(listOf(slots[0].key), selected)
        release.complete(Unit)
        assertEquals("first", first.await())
        assertEquals("second", second.await())
        assertEquals(listOf(slots[0].key, slots[0].key), selected)
    }

    @Test fun validResponseSurvivesFailureToClearHealthMetadata() = runBlocking {
        val pool = GeminiAccountPool(save = { _, cooldown -> if (cooldown == null) error("Synthetic storage fault") },
            awaitTurn = { _, _ -> })
        assertEquals("accepted", pool.request(slots, GeminiKeyMode.FAILOVER, {}) { "accepted" })
    }

    @Test fun unavailablePoolPrefersRetryableDeadlineOverAnInvalidPrimary() = runBlocking {
        val fixture = Fixture()
        fixture.durable[GeminiAccountPool.identity(slots[0].key)] = GeminiAccountCooldown(60_000, "API_AUTH")
        fixture.durable[GeminiAccountPool.identity(slots[1].key)] = GeminiAccountCooldown(61_000, "API_RATE_LIMIT")
        var calls = 0
        try {
            fixture.pool().request(slots.take(2), GeminiKeyMode.FAILOVER, {}) { calls++; "unused" }
            fail("Both credentials are unavailable")
        } catch (error: VideoApiException) {
            assertEquals("API_RATE_LIMIT", error.code)
            assertEquals(61_000L, error.nextRetryAt)
        }
        assertEquals(0, calls)
    }

    @Test fun serviceRetryAfterBlocksPeersAndReopenedPoolUntilTheSavedDeadline() = runBlocking {
        val fixture = Fixture()
        val sleeping = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        val pool = GeminiAccountPool(
            load = { fixture.durable[it] },
            save = { identity, cooldown ->
                if (cooldown == null) fixture.durable.remove(identity) else fixture.durable[identity] = cooldown
            },
            clockMs = { fixture.now },
            sleep = { duration ->
                fixture.waits += duration
                sleeping.complete(Unit)
                resume.await()
                fixture.now += duration
            },
            awaitTurn = { _, _ -> }
        )
        var calls = 0
        val original = async {
            pool.request(slots.take(1), GeminiKeyMode.FAILOVER, {}) {
                calls++
                if (calls == 1) throw VideoApiException("API_SERVICE_503", true, retryAfterMs = 30_000)
                "accepted"
            }
        }
        try {
            sleeping.await()
            assertEquals(listOf(30_000L), fixture.waits)
            assertEquals(31_000L, fixture.durable.values.single().untilMs)
            for (peer in listOf(pool, fixture.pool())) {
                try {
                    peer.request(slots.take(1), GeminiKeyMode.FAILOVER, {}) { calls++; "unexpected" }
                    fail("A peer must honor the saved provider deadline")
                } catch (error: VideoApiException) {
                    assertEquals("API_SERVICE_503", error.code)
                    assertEquals(31_000L, error.nextRetryAt)
                }
            }
            assertEquals(1, calls)
            resume.complete(Unit)
            assertEquals("accepted", original.await())
            assertEquals(2, calls)
            assertTrue(fixture.durable.isEmpty())
        } finally {
            resume.complete(Unit)
            original.cancelAndJoin()
        }
    }
}
