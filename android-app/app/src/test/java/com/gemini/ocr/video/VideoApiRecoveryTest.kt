package com.gemini.ocr.video

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.yield
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicLong

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28])
class VideoApiRecoveryTest {
    @Test fun optedInScopeOverlapsIndependentRequestsWithinItsLimit() = runBlocking {
        var active = 0; var peak = 0; var completed = 0
        val twoActive = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        VideoApiRecovery.withParallelRequests(2) {
            coroutineScope {
                val requests = List(4) {
                    async {
                        VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) {
                            active++; peak = maxOf(peak, active)
                            if (active == 2) twoActive.complete(Unit)
                            try { release.await(); completed++ } finally { active-- }
                        }
                    }
                }
                twoActive.await()
                assertEquals(2, active)
                assertEquals(0, completed)
                release.complete(Unit)
                requests.forEach { it.await() }
            }
        }
        assertEquals(2, peak)
        assertEquals(4, completed)
    }

    @Test fun providerCooldownIsSharedWithOtherVideoRequests() = runBlocking {
        val now = AtomicLong(1_000L)
        val retryWaiting = CompletableDeferred<Unit>()
        val peerWaiting = CompletableDeferred<Unit>()
        val resume = CompletableDeferred<Unit>()
        var originalCalls = 0; var peerCalls = 0
        val deadlines = mutableListOf<Long>()
        VideoApiRecovery.withParallelRequests(2, clockMs = now::get) {
            coroutineScope {
                val retrying = async {
                    VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}, jitter = { 0 },
                        sleep = {
                            assertEquals(12_000L, it)
                            retryWaiting.complete(Unit)
                            resume.await()
                        }) {
                        originalCalls++
                        if (originalCalls == 1) throw VideoApiRecovery.httpFailure(429, retryAfter = "12")
                        assertTrue(now.get() >= 13_000L)
                        "recovered"
                    }
                }
                retryWaiting.await()
                val peer = async {
                    VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {},
                        onRetry = { _, deadline, code ->
                            deadlines += deadline
                            assertEquals("API_RATE_LIMIT", code)
                        }, sleep = {
                            assertEquals(12_000L, it)
                            peerWaiting.complete(Unit)
                            resume.await()
                        }) {
                        peerCalls++
                        assertTrue(now.get() >= 13_000L)
                        "peer"
                    }
                }
                peerWaiting.await()
                assertEquals(1, originalCalls)
                assertEquals(0, peerCalls)
                now.set(13_000L)
                resume.complete(Unit)
                assertEquals("recovered", retrying.await())
                assertEquals("peer", peer.await())
            }
        }
        assertEquals(listOf(13_000L), deadlines)
        assertEquals(2, originalCalls)
        assertEquals(1, peerCalls)
    }

    @Test fun authenticationFailureStopsQueuedRequestsWithoutPoisoningLaterScopes() = runBlocking {
        val firstActive = CompletableDeferred<Unit>()
        val release = CompletableDeferred<Unit>()
        var peerCalls = 0
        VideoApiRecovery.withParallelRequests(1) {
            coroutineScope {
                val first = async {
                    runCatching {
                        VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) {
                            firstActive.complete(Unit)
                            release.await()
                            throw VideoApiRecovery.httpFailure(403)
                        }
                    }
                }
                firstActive.await()
                val peer = async {
                    runCatching {
                        VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) { peerCalls++ }
                    }
                }
                yield()
                release.complete(Unit)
                assertEquals("API_AUTH", (first.await().exceptionOrNull() as VideoApiException).code)
                assertEquals("API_AUTH", (peer.await().exceptionOrNull() as VideoApiException).code)
            }
        }
        assertEquals(0, peerCalls)
        assertEquals("new request", VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}) { "new request" })
    }

    @Test fun cancelledPeerWaitingForSharedCooldownNeverDispatches() = runBlocking {
        val originalWaiting = CompletableDeferred<Unit>()
        val peerWaiting = CompletableDeferred<Unit>()
        var peerCalls = 0
        VideoApiRecovery.withParallelRequests(2) {
            coroutineScope {
                val original = launch {
                    VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {},
                        sleep = { originalWaiting.complete(Unit); awaitCancellation() }) {
                        throw VideoApiRecovery.httpFailure(429, retryAfter = "120")
                    }
                }
                originalWaiting.await()
                val peer = launch {
                    VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {},
                        sleep = { peerWaiting.complete(Unit); awaitCancellation() }) { peerCalls++ }
                }
                peerWaiting.await()
                peer.cancelAndJoin()
                original.cancelAndJoin()
            }
        }
        assertEquals(0, peerCalls)
    }

    @Test fun transient503RetriesBoundedlyAndRechecksGateAfterEachWait() = runBlocking {
        var requests = 0; var gates = 0; var turns = 0
        val waits = mutableListOf<Long>(); val checkpoints = mutableListOf<Int>()
        val result = VideoApiRecovery.run(beforeDispatch = { gates++ },
            onRetry = { attempt, _, code -> checkpoints += attempt; assertEquals("API_SERVICE_503", code) },
            sleep = { waits += it }, jitter = { 0 }, dispatchTurn = { turns++ }) {
            if (++requests < 3) throw VideoApiRecovery.httpFailure(503)
            "accepted"
        }
        assertEquals("accepted", result); assertEquals(3, requests); assertEquals(3, gates); assertEquals(3, turns)
        assertEquals(listOf(1_000L, 2_000L), waits); assertEquals(listOf(1, 2), checkpoints)
    }

    @Test fun networkFailureStopsAfterThreeDispatchesWithoutLeakingOriginalError() = runBlocking {
        var requests = 0
        try {
            VideoApiRecovery.run(beforeDispatch = {}, sleep = {}, jitter = { 0 }, dispatchTurn = {}) {
                requests++; throw SocketTimeoutException("SECRET_KEY_AND_URL")
            }
            fail("must stop")
        } catch (error: VideoApiException) {
            assertEquals("API_TIMEOUT", error.code); assertNull(error.cause)
            assertFalse(error.message.orEmpty().contains("SECRET"))
        }
        assertEquals(3, requests)
    }

    @Test fun authAndTrueDailyQuotaNeverRetry() = runBlocking {
        val body = JSONObject().put("error", JSONObject().put("details", JSONArray().put(JSONObject()
            .put("@type", "type.googleapis.com/google.rpc.QuotaFailure")
            .put("violations", JSONArray().put(JSONObject().put("quotaMetric", "requests_per_day")))))).toString()
        for (error in listOf(VideoApiRecovery.httpFailure(401), VideoApiRecovery.httpFailure(403),
            VideoApiRecovery.httpFailure(429, body))) {
            var count = 0
            try {
                VideoApiRecovery.run(beforeDispatch = {}, sleep = { fail("must not wait") }, dispatchTurn = {}) {
                    count++; throw error
                }
            } catch (actual: VideoApiException) { assertSame(error, actual) }
            assertEquals(1, count)
        }
    }

    @Test fun structuredInvalidApiKey400StopsWithoutRetryOrSplittingButOther400sRemainRequestErrors() = runBlocking {
        fun body(type: String, reason: String) = JSONObject().put("error", JSONObject()
            .put("message", "SECRET_KEY_AND_PROVIDER_BODY")
            .put("details", JSONArray().put(JSONObject().put("@type", type).put("reason", reason)))).toString()
        val failure = VideoApiRecovery.httpFailure(400, body("type.googleapis.com/google.rpc.ErrorInfo", "API_KEY_INVALID"))
        assertEquals("API_AUTH", failure.code); assertFalse(failure.transient); assertFalse(failure.splitAllowed)
        assertNull(failure.cause); assertFalse(failure.message.orEmpty().contains("SECRET"))
        var requests = 0
        try {
            VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}, sleep = { fail("authentication must not wait/retry") }) {
                requests++; throw failure
            }
            fail("must stop authentication failure")
        } catch (actual: VideoApiException) { assertSame(failure, actual) }
        assertEquals(1, requests)
        for (other in listOf(body("type.googleapis.com/google.rpc.ErrorInfo", "INVALID_REQUEST"),
            body("unrecognized/error", "API_KEY_INVALID"), "{\"error\":{\"message\":\"API_KEY_INVALID\"}}", "invalid JSON")) {
            val requestError = VideoApiRecovery.httpFailure(400, other)
            assertEquals("API_REQUEST_400", requestError.code); assertTrue(requestError.splitAllowed)
        }
    }

    @Test fun retryAfterSecondsOverridesExponentialAndLongCooldownRecoversAutomatically() = runBlocking {
        var requests = 0; val waits = mutableListOf<Long>()
        VideoApiRecovery.run(beforeDispatch = {}, sleep = { waits += it }, dispatchTurn = {}) {
            if (++requests == 1) throw VideoApiRecovery.httpFailure(429, retryAfter = "12")
            Unit
        }
        assertEquals(listOf(12_000L), waits)
        requests = 0; waits.clear()
        val before = System.currentTimeMillis(); val checkpointTimes = mutableListOf<Long>()
        val recovered = VideoApiRecovery.run(beforeDispatch = {}, sleep = { waits += it },
            onRetry = { _, at, _ -> checkpointTimes += at }, dispatchTurn = {}) {
            if (++requests == 1) throw VideoApiRecovery.httpFailure(429, retryAfter = "120")
            "recovered without resume"
        }
        assertEquals("recovered without resume", recovered); assertEquals(2, requests)
        assertEquals(listOf(120_000L), waits); assertTrue(checkpointTimes.single() >= before + 120_000L)
        assertEquals(0, VideoApiRecovery.retryAfterMs("not a header"))
        assertEquals(2_000L, VideoApiRecovery.retryAfterMs("Wed, 21 Oct 2015 07:28:02 GMT", 1445412480000L))
    }

    @Test fun cancellationDuringLongRetryAfterWaitDoesNotDispatchAgain() = runBlocking {
        var requests = 0; val waiting = CompletableDeferred<Unit>()
        val worker = launch {
            VideoApiRecovery.run(beforeDispatch = {}, dispatchTurn = {}, sleep = { ms ->
                assertEquals(120_000L, ms); waiting.complete(Unit); awaitCancellation()
            }) { requests++; throw VideoApiRecovery.httpFailure(429, retryAfter = "120") }
        }
        waiting.await(); worker.cancelAndJoin()
        assertEquals(1, requests); assertTrue(worker.isCancelled)
    }

    @Test fun structuredRateLimitOverridesGenericBillingTextAndHonorsRetryInfo() {
        val shortWindow = """
            {"error":{"message":"Check your plan and billing details.","details":[
              {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
                {"quotaId":"GenerateRequestsPerMinutePerProjectPerModel","quotaValue":"15"}]},
              {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"61.001s"}]}}
        """.trimIndent()
        val temporary = VideoApiRecovery.httpFailure(429, shortWindow, "20")
        assertEquals("API_RATE_LIMIT", temporary.code)
        assertTrue(temporary.transient)
        assertEquals(61_001L, temporary.retryAfterMs)
        val daily = VideoApiRecovery.httpFailure(429, shortWindow.replace("PerMinute", "PerDay"))
        assertEquals("API_QUOTA_EXHAUSTED", daily.code)
        assertFalse(daily.transient)
        val disabledQuota = VideoApiRecovery.httpFailure(429, shortWindow.replace("\"15\"", "\"0\""))
        assertEquals("API_QUOTA_EXHAUSTED", disabledQuota.code)
    }

    @Test fun billingAdviceAndRollingSpendLimitDoNotBecomeDayLongQuotaFailures() {
        val generic = """{"error":{"message":"Check your plan and billing details.","details":[
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"60s"}]}}"""
        val rolling = """{"error":{"message":"Spend limit reached. Check billing details.","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
              {"quotaId":"GenerateContentSpendPer10MinutesPerProject","quotaValue":"10"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"120s"}]}}"""
        for ((body, wait) in listOf(generic to 60_000L, rolling to 120_000L)) {
            val error = VideoApiRecovery.httpFailure(429, body)
            assertEquals("API_RATE_LIMIT", error.code)
            assertTrue(error.transient)
            assertEquals(wait, error.retryAfterMs)
        }
    }

    @Test fun dailyQuotaWithRetryInfoRemainsTerminalAndUnknownViolationTypesDoNotOverrideIt() {
        val daily = """{"error":{"message":"Daily quota exceeded; check billing details.","details":[
            {"@type":"type.googleapis.com/google.rpc.QuotaFailure","violations":[
              {"quotaMetric":"requests_per_day","quotaValue":"100"}]},
            {"@type":"type.googleapis.com/google.rpc.RetryInfo","retryDelay":"60s"}]}}"""
        assertEquals("API_QUOTA_EXHAUSTED", VideoApiRecovery.httpFailure(429, daily).code)
        assertFalse(VideoApiRecovery.httpFailure(429, daily).transient)
        assertEquals("API_QUOTA_EXHAUSTED", VideoApiRecovery.httpFailure(429,
            """{"error":{"message":"Daily quota exceeded."}}""").code)
        val unknown = daily.replace("Daily quota exceeded;", "Quota limit;")
            .replace("type.googleapis.com/google.rpc.QuotaFailure", "unrecognized/QuotaFailure")
        assertEquals("API_RATE_LIMIT", VideoApiRecovery.httpFailure(429, unknown).code)
        assertEquals("API_RATE_LIMIT", VideoApiRecovery.httpFailure(429,
            """{"error":{"message":"Review daily limits and billing details."}}""").code)
        assertEquals("API_RATE_LIMIT", VideoApiRecovery.httpFailure(429,
            """{"error":{"message":"Spend limit: 0.5, check billing."}}""").code)
    }

    @Test fun cancellationDuringGateNeverDispatchesOrRetries() = runBlocking {
        var requests = 0
        try {
            VideoApiRecovery.run(beforeDispatch = { throw CancellationException("pause") }, dispatchTurn = {}) { requests++ }
            fail("must cancel")
        } catch (_: CancellationException) { }
        assertEquals(0, requests)
    }

    @Test fun incompleteAndDuplicateTextIdsDoNotMapByArrayOrder() {
        val cues = listOf(VideoCue("a", 0, 1000, "你好"), VideoCue("b", 1000, 2000, "再见"))
        val batch = VideoTranslationPlan.nextCloudBatch(cues, false)!!
        fun response(rows: JSONArray) = envelope(JSONObject().put("translations", rows).toString())
        val partial = VideoTranslationClient.parseResponse(response(JSONArray().put(JSONObject().put("id", "b")
            .put("translation", "Tạm biệt"))), batch, "", "model")
        assertEquals(mapOf("b" to "Tạm biệt"), partial.accepted); assertEquals(listOf("a"), partial.missingIds)
        val row = JSONObject().put("id", "b").put("translation", "Tạm biệt")
        val error = assertThrows(VideoApiException::class.java) {
            VideoTranslationClient.parseResponse(response(JSONArray().put(row).put(row)), batch, "", "model")
        }
        assertEquals("TEXT_INVALID_RESPONSE", error.code); assertTrue(error.splitAllowed); assertNull(error.cause)
    }

    @Test fun polishIsIndependentTextOnlyAndIncompleteResponsePreservesDraft() = runBlocking {
        val cues = listOf(VideoCue("a", 0, 1000, "不卖马", localDraft = "Bán ngựa", refinementState = "pending"),
            VideoCue("b", 1000, 2000, "你好", localDraft = "Xin chào", refinementState = "pending"))
        val batch = VideoTranslationPlan.nextCloudBatch(cues, true)!!
        val payload = VideoPolishClient.buildRequest(batch, "", "")
        assertTrue(payload.contains("Independent editorial pass")); assertTrue(payload.contains("Bán ngựa"))
        assertFalse(payload.contains("inlineData")); assertFalse(payload.contains("image/jpeg"))
        VideoPolishClient.overrideResponseForTest = { envelope("{\"translations\":[{\"id\":\"a\",\"translation\":\"Không bán ngựa\"}]}") }
        try {
            try { VideoPolishClient().polish("", batch, "", "") {}; fail("missing ID must reject editorial result") }
            catch (error: VideoApiException) { assertEquals("POLISH_MISSING_IDS", error.code) }
            assertEquals("Bán ngựa", cues[0].localDraft); assertEquals("", cues[0].translation)
            assertEquals(listOf("a", "b"), cues.map { it.id }); assertEquals(1000L, cues[0].endMs)
        } finally { VideoPolishClient.overrideResponseForTest = null }
    }

    @Test fun fencedJsonIsAcceptedWithoutExtractingJsonFromArbitraryProse() {
        val batch = VideoTranslationPlan.nextCloudBatch(listOf(VideoCue("a", 0, 1000, "你好")), false)!!
        val json = "{\"translations\":[{\"id\":\"a\",\"translation\":\"Xin chào\"}]}"
        assertEquals("Xin chào", VideoTranslationClient.parseResponse(envelope("```json\n$json\n```"), batch, "", "m").accepted["a"])
        assertThrows(VideoApiException::class.java) { VideoTranslationClient.parseResponse(envelope("Here: $json"), batch, "", "m") }
    }

    @Test fun polishUncertaintyFlagsUseIdsAndMissingFlagConservativelyRequiresReview() = runBlocking {
        val cues = listOf(VideoCue("a", 0, 1000, "你好", localDraft = "Xin chào", refinementState = "pending"),
            VideoCue("b", 1000, 2000, "再见", localDraft = "Tạm biệt", refinementState = "pending"),
            VideoCue("c", 2000, 3000, "别走", localDraft = "Đừng đi", refinementState = "pending"))
        val batch = VideoTranslationPlan.nextCloudBatch(cues, true)!!
        val schema = JSONObject(VideoPolishClient.buildRequest(batch, "", ""))
            .getJSONObject("generationConfig").getJSONObject("responseSchema").getJSONObject("properties")
            .getJSONObject("translations").getJSONObject("items")
        assertEquals("BOOLEAN", schema.getJSONObject("properties").getJSONObject("needsReview").getString("type"))
        assertTrue(schema.getJSONArray("required").toString().contains("needsReview"))
        VideoPolishClient.overrideResponseForTest = { envelope(JSONObject().put("translations", JSONArray()
            .put(JSONObject().put("id", "c").put("translation", "Đừng đi").put("needsReview", true))
            .put(JSONObject().put("id", "a").put("translation", "Xin chào").put("needsReview", false))
            .put(JSONObject().put("id", "b").put("translation", "Tạm biệt"))).toString()) }
        try {
            val result = VideoPolishClient().polish("", batch, "", "") {}
            assertEquals(setOf("b", "c"), result.needsReviewIds)
            assertEquals(setOf("a", "b", "c"), result.accepted.keys)
            assertEquals("Tạm biệt", result.accepted["b"])
            assertEquals(listOf("Xin chào", "Tạm biệt", "Đừng đi"), cues.map { it.localDraft })
            assertTrue(cues.all { it.translation.isEmpty() && !it.approved })
        } finally { VideoPolishClient.overrideResponseForTest = null }
    }

    @Test fun malformedPolishUncertaintyFlagRejectsWholeResponseAndKeepsDraft() = runBlocking {
        val cues = listOf(VideoCue("a", 0, 1000, "你好", localDraft = "Xin chào", refinementState = "pending"),
            VideoCue("b", 1000, 2000, "再见", localDraft = "Tạm biệt", refinementState = "pending"))
        val batch = VideoTranslationPlan.nextCloudBatch(cues, true)!!
        try {
            for (malformed in listOf("true", 1, JSONObject.NULL)) {
                VideoPolishClient.overrideResponseForTest = { envelope(JSONObject().put("translations", JSONArray()
                    .put(JSONObject().put("id", "a").put("translation", "Xin chào").put("needsReview", false))
                    .put(JSONObject().put("id", "b").put("translation", "Tạm biệt").put("needsReview", malformed))).toString()) }
                try { VideoPolishClient().polish("", batch, "", "") {}; fail("invalid review flag must reject entire group") }
                catch (error: VideoApiException) {
                    assertEquals("POLISH_INVALID_REVIEW_FLAGS", error.code); assertNull(error.cause)
                }
                assertEquals(listOf("Xin chào", "Tạm biệt"), cues.map { it.localDraft })
                assertTrue(cues.all { it.translation.isEmpty() && !it.approved })
                assertEquals(listOf(0L, 1000L), cues.map { it.startMs })
            }
        } finally { VideoPolishClient.overrideResponseForTest = null }
    }

    private fun envelope(text: String): String = JSONObject().put("candidates", JSONArray().put(JSONObject()
        .put("finishReason", "STOP").put("content", JSONObject().put("parts", JSONArray().put(JSONObject().put("text", text)))))).toString()
}
