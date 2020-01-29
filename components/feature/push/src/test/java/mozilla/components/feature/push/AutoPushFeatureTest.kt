/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package mozilla.components.feature.push

import android.content.Context
import android.content.SharedPreferences
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.runBlockingTest
import mozilla.appservices.push.KeyInfo
import mozilla.appservices.push.SubscriptionInfo
import mozilla.appservices.push.SubscriptionResponse
import mozilla.components.concept.push.Bus
import mozilla.components.concept.push.EncryptedPushMessage
import mozilla.components.concept.push.PushError
import mozilla.components.concept.push.PushService
import mozilla.components.feature.push.AutoPushFeature.Companion.LAST_VERIFIED
import mozilla.components.feature.push.AutoPushFeature.Companion.PERIODIC_INTERVAL_MILLISECONDS
import mozilla.components.feature.push.AutoPushFeature.Companion.PREFERENCE_NAME
import mozilla.components.feature.push.AutoPushFeature.Companion.PREF_TOKEN
import mozilla.components.lib.crash.CrashReporter
import mozilla.components.support.test.any
import mozilla.components.support.test.mock
import mozilla.components.support.test.robolectric.testContext
import mozilla.components.support.test.whenever
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.`when`
import org.mockito.Mockito.never
import org.mockito.Mockito.spy
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.mockito.Mockito.verifyZeroInteractions

@ExperimentalCoroutinesApi
@RunWith(AndroidJUnit4::class)
class AutoPushFeatureTest {

    private var lastVerified: Long
        get() = preference(testContext).getLong(LAST_VERIFIED, System.currentTimeMillis())
        set(value) = preference(testContext).edit().putLong(LAST_VERIFIED, value).apply()

    @Before
    fun setup() {
        lastVerified = 0L
    }

    @Test
    fun `initialize starts push service`() {
        val service: PushService = mock()
        val config = PushConfig("push-test")
        val feature = spy(AutoPushFeature(testContext, service, config))

        feature.initialize()

        verify(service).start(testContext)

        verifyNoMoreInteractions(service)
    }

    @Test
    fun `updateToken not called if no token in prefs`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection())

        spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        verify(connection, never()).updateToken(anyString())
    }

    @Test
    fun `updateToken called if token is in prefs`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection())

        preference(testContext).edit().putString(PREF_TOKEN, "token").apply()

        AutoPushFeature(
            testContext, mock(), mock(), connection = connection,
            coroutineContext = coroutineContext
        )

        verify(connection).updateToken("token")
    }

    @Test
    fun `shutdown stops service and unsubscribes all`() = runBlockingTest {
        val service: PushService = mock()
        val connection: PushConnection = mock()
        whenever(connection.isInitialized()).thenReturn(true)

        AutoPushFeature(testContext, service, mock(), coroutineContext, connection).also {
            it.shutdown()
        }

        verify(service).stop()
        verify(connection).unsubscribeAll()
    }

    @Test
    fun `onNewToken updates connection and saves pref`() = runBlockingTest {
        val connection: PushConnection = mock()
        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        feature.onNewToken("token")

        verify(connection).updateToken("token")

        val pref = preference(testContext).getString(PREF_TOKEN, null)
        assertNotNull(pref)
        assertEquals("token", pref)
    }

    @Test
    fun `onNewToken updates subscriptions if token does not already exists`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection(true))
        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        feature.onNewToken("token")
        verify(feature, times(1)).subscribeAll()

        feature.onNewToken("token")
        verify(feature, times(1)).subscribeAll()
    }

    @Test
    fun `onMessageReceived decrypts message and notifies observers`() = runBlockingTest {
        val connection: PushConnection = mock()
        val encryptedMessage: EncryptedPushMessage = mock()
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(connection.isInitialized()).thenReturn(true)
        whenever(encryptedMessage.channelId).thenReturn("992a0f0542383f1ea5ef51b7cf4ae6c4")
        whenever(connection.decrypt(any(), any(), any(), any(), any())).thenReturn("test".toByteArray())

        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        feature.registerForPushMessages(PushType.Services, object : Bus.Observer<PushType, String> {
            override fun onEvent(type: PushType, message: String) {
                assertEquals("test", message)
            }
        }, owner, true)

        feature.onMessageReceived(encryptedMessage)
    }

    @Test
    fun `onMessageReceived handles unknown channelId`() = runBlockingTest {
        val connection: PushConnection = mock()
        val encryptedMessage: EncryptedPushMessage = mock()
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(connection.isInitialized()).thenReturn(true)
        whenever(encryptedMessage.channelId).thenReturn("whatisachannelidanyway")
        whenever(connection.decrypt(any(), any(), any(), any(), any())).thenReturn("test".toByteArray())

        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        val pushServiceObserver: Bus.Observer<PushType, String> = mock()
        val webpushServiceObserver: Bus.Observer<PushType, String> = mock()
        feature.registerForPushMessages(PushType.Services, pushServiceObserver)
        feature.registerForPushMessages(PushType.WebPush, webpushServiceObserver)

        // Shouldn't crash!
        feature.onMessageReceived(encryptedMessage)

        // Observers don't need to know about garbage we received.
        verifyZeroInteractions(pushServiceObserver)
        verifyZeroInteractions(webpushServiceObserver)
    }

    @Test
    fun `subscribeForType notifies observers`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection(true))
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        feature.registerForSubscriptions(object : PushSubscriptionObserver {
            override fun onSubscriptionAvailable(subscription: AutoPushSubscription) {
                assertEquals(PushType.Services, subscription.type)
            }
        }, owner, true)

        feature.subscribeForType(PushType.Services)
    }

    @Test
    fun `unsubscribeForType calls rust layer`() = runBlockingTest {
        val connection: PushConnection = mock()
        spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))
            .unsubscribeForType(PushType.Services)

        verify(connection, never()).unsubscribe(anyString())

        whenever(connection.isInitialized()).thenReturn(true)

        spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))
            .unsubscribeForType(PushType.Services)

        verify(connection).unsubscribe(anyString())
    }

    @Test
    fun `subscribeAll notifies observers`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection(true))
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)
        whenever(connection.isInitialized()).thenReturn(true)

        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))

        feature.registerForSubscriptions(object : PushSubscriptionObserver {
            override fun onSubscriptionAvailable(subscription: AutoPushSubscription) {
                assertEquals(PushType.Services, subscription.type)
            }
        }, owner, true)

        feature.subscribeAll()
    }

    @Test
    fun `forceRegistrationRenewal deletes pref and calls service`() = runBlockingTest {
        val service: PushService = mock()
        val feature = spy(AutoPushFeature(testContext, service, mock(), coroutineContext, mock()))

        feature.renewRegistration()

        verify(service).deleteToken()
        verify(service).start(testContext)

        val pref = preference(testContext).getString(PREF_TOKEN, null)
        assertNull(pref)
    }

    @Test
    fun `verifyActiveSubscriptions notifies subscribers`() = runBlockingTest {
        val connection: PushConnection = spy(TestPushConnection(true))
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        val observers: PushSubscriptionObserver = mock()
        val feature = spy(AutoPushFeature(testContext, mock(), mock(), coroutineContext, connection))
        whenever(owner.lifecycle).thenReturn(lifecycle)
        whenever(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        feature.registerForSubscriptions(observers)

        // When there are NO subscription updates, observers should not be notified.
        feature.verifyActiveSubscriptions()

        verify(observers, never()).onSubscriptionAvailable(any())

        // When there are subscription updates, observers should not be notified.
        whenever(connection.verifyConnection()).thenReturn(true)
        feature.verifyActiveSubscriptions()

        verify(observers, times(2)).onSubscriptionAvailable(any())
    }

    @Test
    fun `initialize executes verifyActiveSubscriptions after interval`() = runBlockingTest {
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
                connection = mock()
            )
        )

        lastVerified = System.currentTimeMillis() - VERIFY_NOW

        feature.initialize()

        verify(feature).tryVerifySubscriptions()
    }

    @Test
    fun `initialize does not execute verifyActiveSubscription before interval`() = runBlockingTest {
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
                connection = mock()
            )
        )

        lastVerified = System.currentTimeMillis() - SKIP_INTERVAL

        feature.initialize()

        verify(feature, never()).verifyActiveSubscriptions()
    }

    @Test
    fun `verifySubscriptions notifies observers`() = runBlockingTest {
        val owner: LifecycleOwner = mock()
        val lifecycle: Lifecycle = mock()
        val native: PushConnection = TestPushConnection(true)
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
                connection = native
            )
        )
        `when`(owner.lifecycle).thenReturn(lifecycle)
        `when`(lifecycle.currentState).thenReturn(Lifecycle.State.STARTED)

        feature.registerForSubscriptions(object : PushSubscriptionObserver {
            override fun onSubscriptionAvailable(subscription: AutoPushSubscription) {
                assertEquals("https://fool", subscription.endpoint)
            }
        }, owner, false)

        feature.verifyActiveSubscriptions()
    }

    @Test
    fun `crash reporter is notified of errors`() = runBlockingTest {
        val native: PushConnection = TestPushConnection(true)
        val crashReporter: CrashReporter = mock()
        val feature = spy(
            AutoPushFeature(
                context = testContext,
                service = mock(),
                config = mock(),
                coroutineContext = coroutineContext,
                connection = native,
                crashReporter = crashReporter
            )
        )
        feature.onError(PushError.Rust(PushError.MalformedMessage("Bad things happened!")))

        verify(crashReporter).submitCaughtException(any<PushError.Rust>())
    }

    companion object {
        private fun preference(context: Context): SharedPreferences {
            return context.getSharedPreferences(PREFERENCE_NAME, Context.MODE_PRIVATE)
        }

        private const val SKIP_INTERVAL = 23 * 60 * 60 * 1000L // 23 hours; less than interval
        private const val VERIFY_NOW = PERIODIC_INTERVAL_MILLISECONDS + (10 * 60 * 1000) // interval + 10 mins
    }

    class TestPushConnection(private val init: Boolean = false) : PushConnection {
        override suspend fun subscribe(channelId: String, scope: String) =
            SubscriptionResponse(
                channelId,
                SubscriptionInfo("https://foo", KeyInfo("auth", "p256dh"))
            )

        override suspend fun unsubscribe(channelId: String): Boolean = true

        override suspend fun unsubscribeAll(): Boolean = true

        override suspend fun updateToken(token: String) = true

        override suspend fun verifyConnection(): Boolean = false

        override fun decrypt(
            channelId: String,
            body: String,
            encoding: String,
            salt: String,
            cryptoKey: String
        ): ByteArray {
            TODO("not implemented")
        }

        override fun isInitialized() = init

        override fun close() {}
    }
}
