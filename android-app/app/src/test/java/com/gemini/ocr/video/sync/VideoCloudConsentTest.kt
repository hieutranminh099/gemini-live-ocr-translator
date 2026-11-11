package com.gemini.ocr.video.sync

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** Account routing is a consent boundary; these tests do not fake a successful Google sign-in. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28])
class VideoCloudConsentTest{
    private lateinit var context:Context
    @Before fun setup(){
        context=ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).edit().clear().commit()
        runCatching{WorkManager.initialize(context,Configuration.Builder().setMinimumLoggingLevel(android.util.Log.ERROR).build())}
    }
    @Test fun cloudUploadIsDisabledBeforeExplicitUserChoice(){
        val sync=VideoCloudSync(context)
        assertFalse(sync.state.value.enabled)
        assertTrue(sync.state.value.accountEmail.isBlank())
        assertTrue(sync.state.value.wifiOnly)
    }
    @Test fun switchingVerifiedAccountCannotInheritConsentToUploadPrivateLocalProjects(){
        context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).edit()
            .putString("account","first@example.com").putBoolean("enabled",true).commit()
        val sync=VideoCloudSync(context)
        sync.rememberVerifiedAccount("second@example.com")
        assertEquals("second@example.com",sync.state.value.accountEmail)
        assertFalse(sync.state.value.enabled)
        assertFalse(context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).getBoolean("enabled",true))
    }
    @Test fun renewingTheSameVerifiedAccountPreservesAnExistingUploadChoice(){
        context.getSharedPreferences("video-google-sync",Context.MODE_PRIVATE).edit()
            .putString("account","same@example.com").putBoolean("enabled",true).commit()
        val sync=VideoCloudSync(context)
        sync.rememberVerifiedAccount("same@example.com")
        assertTrue(sync.state.value.enabled)
        assertEquals("same@example.com",sync.state.value.accountEmail)
    }
    @Test fun logoutAndReconnectToSameAccountCannotReviveAnOldPlaybackSession(){
        val sync=VideoCloudSync(context)
        sync.rememberVerifiedAccount("same@example.com")
        val oldSession=sync.playbackAccountGuard("same@example.com")
        oldSession()
        sync.disconnect();sync.rememberVerifiedAccount("same@example.com")
        try{oldSession();fail("old player must remain invalid after reconnect")}
        catch(e:DrivePlaybackException){assertEquals(DrivePlaybackFailure.ACCOUNT,e.failure)}
        sync.playbackAccountGuard("same@example.com")()
    }
    @Test fun playbackStopsWhenAccountRequiresAuthorizationEvenBeforeAccountEmailChanges(){
        val sync=VideoCloudSync(context)
        sync.rememberVerifiedAccount("same@example.com")
        val session=sync.playbackAccountGuard("same@example.com")
        sync.status("Phiên hết hạn",needsAuth=true)
        try{session();fail("must stop using an unauthorized session")}
        catch(e:DrivePlaybackException){assertEquals(DrivePlaybackFailure.AUTH,e.failure)}
    }
}
