package com.gemini.ocr.video.sync

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.common.api.ApiException
import com.google.android.gms.common.api.Status
import kotlinx.coroutines.runBlocking
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException

/** Offline Google callback/userinfo contracts; not a real Google account authorization. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoCloudAuthorizationTest {
    private lateinit var context:Context
    private lateinit var server:MockWebServer
    @Before fun setup(){
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).edit().clear().commit()
        runCatching{WorkManager.initialize(context,Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())}
        server=MockWebServer();server.start()
    }
    @After fun close(){server.shutdown()}
    @Suppress("DEPRECATION")
    private fun result(scopes:List<String> = listOf(VideoCloudSync.APPDATA_SCOPE),token:String?="offline-test-token")=
        AuthorizationResult(null,token,null,scopes,null,null)
    private fun sync(decoder:(Intent)->AuthorizationResult = {result()})=
        VideoCloudSync(context,userInfoUrl=server.url("/userinfo"),authorizationResultFromIntent=decoder)
    private suspend fun failure(block:suspend ()->Unit):IOException {
        try{block();fail("authorization must fail")}catch(e:IOException){return e}
        throw AssertionError("unreachable")
    }
    @Test fun cancelledActivityStillParsesGoogleDeveloperErrorAndPersistsSpecificCode()=runBlocking {
        var parsed=false
        val sync=sync{parsed=true;throw ApiException(Status(10,"sensitive-server-text"))}
        val error=failure{sync.completeAuthorization(Intent(),Activity.RESULT_CANCELED)}
        assertTrue(parsed)
        assertTrue(error.message.orEmpty().contains("callback/10"))
        assertTrue(error.message.orEmpty().contains(context.packageName))
        assertFalse(error.message.orEmpty().contains("sensitive-server-text"))
        assertTrue(sync.state.value.authorizationRequired)
        assertEquals(error.message,sync.state.value.message)
        assertTrue(sync.state.value.accountEmail.isBlank())
    }
    @Test fun emptyCancelledResultReportsCallbackCodeRatherThanLeavingStaleStatus()=runBlocking {
        val sync=sync()
        val error=failure{sync.completeAuthorization(null,Activity.RESULT_CANCELED)}
        assertTrue(error.message.orEmpty().contains("NO_RESULT_0"))
        assertEquals(error.message,sync.state.value.message)
        assertTrue(sync.state.value.authorizationRequired)
        assertEquals(0,server.requestCount)
    }
    @Test fun verifiedGoogleResultCommitsAccountAndDoesNotEnableUploads()=runBlocking {
        server.enqueue(MockResponse().setBody("""{"email":"verified@example.com"}"""))
        val sync=sync()
        sync.completeAuthorization(Intent(),Activity.RESULT_OK)
        assertEquals("verified@example.com",sync.state.value.accountEmail)
        assertFalse(sync.state.value.authorizationRequired)
        assertFalse(sync.state.value.enabled)
        assertTrue(sync.state.value.message.startsWith("Đã kết nối Google Drive"))
        assertEquals("Bearer offline-test-token",server.takeRequest().getHeader("Authorization"))
        val prefs=context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE)
        assertFalse(prefs.all.values.any{it.toString().contains("offline-test-token")})
    }
    @Test fun partialConsentWithoutDriveCannotBecomeConnectedOrContactUserinfo()=runBlocking {
        val sync=sync{result(scopes=listOf("openid","email"))}
        val error=failure{sync.completeAuthorization(Intent())}
        assertTrue(error.message.orEmpty().contains("DRIVE_SCOPE_MISSING"))
        assertTrue(sync.state.value.accountEmail.isBlank())
        assertEquals(0,server.requestCount)
    }
    @Test fun missingAccessTokenCannotBecomeConnected()=runBlocking {
        val sync=sync{result(token="")}
        assertTrue(failure{sync.completeAuthorization(Intent())}.message.orEmpty().contains("NO_ACCESS_TOKEN"))
        assertTrue(sync.state.value.accountEmail.isBlank())
        assertEquals(0,server.requestCount)
    }
    @Test fun userinfoHttpFailureShowsSafeStatusAndPreservesPreviouslyVerifiedAccount()=runBlocking {
        context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).edit()
            .putString("account","previous@example.com").commit()
        server.enqueue(MockResponse().setResponseCode(403).setBody("private-debug-body"))
        val sync=sync()
        val error=failure{sync.completeAuthorization(Intent())}
        assertTrue(error.message.orEmpty().contains("USERINFO_HTTP_403"))
        assertFalse(error.message.orEmpty().contains("private-debug-body"))
        assertEquals("previous@example.com",sync.state.value.accountEmail)
        assertTrue(sync.state.value.authorizationRequired)
    }
    @Test fun malformedUserinfoCannotCommitAnAccount()=runBlocking {
        server.enqueue(MockResponse().setBody("""{"sub":"not-an-email"}"""))
        val sync=sync()
        assertTrue(failure{sync.completeAuthorization(Intent())}.message.orEmpty().contains("USERINFO_INVALID"))
        assertTrue(sync.state.value.accountEmail.isBlank())
    }
    @Test fun oversizedUserinfoIsRejectedBeforeAccountCommit()=runBlocking {
        server.enqueue(MockResponse().setBody("x".repeat(65537)))
        val sync=sync()
        assertTrue(failure{sync.completeAuthorization(Intent())}.message.orEmpty().contains("USERINFO_TOO_LARGE"))
        assertTrue(sync.state.value.accountEmail.isBlank())
    }
}
