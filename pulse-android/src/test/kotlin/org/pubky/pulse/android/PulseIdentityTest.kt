package org.pubky.pulse.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Verifies the [Pulse] identity surface wired in Phase 3 under Robolectric:
 * configure resolves a persistent anonymous id, [Pulse.currentUserId] reflects the
 * resolved id (real user id once set, otherwise anonymous), and
 * [Pulse.setUser] / [Pulse.clearUser] persist + flip the resolved id. Mirrors the
 * Swift `Pulse` identity semantics.
 */
@RunWith(RobolectricTestRunner::class)
class PulseIdentityTest {

    private val context: Context
        get() = ApplicationProvider.getApplicationContext()

    private fun clearSdkPrefs() {
        context.getSharedPreferences(IdentityStore.PREFS_NAME, Context.MODE_PRIVATE)
            .edit().clear().apply()
    }

    @Before
    fun setUp() {
        Pulse.resetForTesting()
        clearSdkPrefs()
    }

    @After
    fun tearDown() {
        Pulse.resetForTesting()
        clearSdkPrefs()
    }

    private fun configure() {
        Pulse.configure(
            context = context,
            endpoint = "https://ingest.example.com",
            apiKey = "pulse_client_test",
        )
    }

    @Test
    fun currentUserIdAndSessionIdAreNullBeforeConfigure() {
        assertNull(Pulse.currentUserId)
        assertNull(Pulse.sessionId)
    }

    @Test
    fun configureResolvesAnonymousIdAsCurrentUser() {
        configure()
        val id = Pulse.currentUserId
        assertNotNull(id)
        assertTrue(
            "current user id should be the anonymous id pre-login, got: $id",
            id!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX),
        )
        assertNotNull(Pulse.sessionId)
    }

    @Test
    fun anonymousIdIsStableAcrossReconfigure() {
        configure()
        val first = Pulse.currentUserId
        Pulse.resetForTesting()
        configure()
        val second = Pulse.currentUserId
        // The anonymous id is persisted, so a second configure resolves the
        // same id (mirrors Swift reading from the Keychain on each launch).
        assertEquals(first, second)
    }

    @Test
    fun setUserBecomesCurrentUserAndPersists() {
        configure()
        Pulse.setUser("real-user-42")
        assertEquals("real-user-42", Pulse.currentUserId)

        // Persisted: a reconfigure prefers the saved real user id over anon.
        Pulse.resetForTesting()
        configure()
        assertEquals("real-user-42", Pulse.currentUserId)
    }

    @Test
    fun clearUserRevertsToAnonymousIdWithoutResettingIt() {
        configure()
        val anonBefore = Pulse.currentUserId
        Pulse.setUser("real-user-42")
        Pulse.clearUser()
        // Reverts to the same anonymous id (not a fresh one).
        assertEquals(anonBefore, Pulse.currentUserId)
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun clearUserWithNewAnonymousIdMintsFreshId() {
        configure()
        val anonBefore = Pulse.currentUserId
        Pulse.setUser("real-user-42")
        Pulse.clearUser(newAnonymousId = true)
        val anonAfter = Pulse.currentUserId
        assertNotNull(anonAfter)
        assertTrue(anonAfter!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
        assertNotEquals(
            "clearUser(newAnonymousId = true) must mint a distinct anonymous id",
            anonBefore,
            anonAfter,
        )
    }

    @Test
    fun clearUserDoesNotRestoreSavedUserOnReconfigure() {
        configure()
        Pulse.setUser("real-user-42")
        Pulse.clearUser()
        // The saved user id was cleared from storage, so a reconfigure resolves
        // back to the anonymous id (logout survives relaunch).
        Pulse.resetForTesting()
        configure()
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun setUserBeforeConfigurePersistsIntoNextSession() {
        // Mirrors Swift: Pulse.setUser persists unconditionally, so a pre-configure
        // setUser must survive into the next configure() and win over the anon id.
        Pulse.setUser("real-user-42")
        // currentUserId is still null pre-configure (no resolved state yet).
        assertNull(Pulse.currentUserId)
        configure()
        assertEquals("real-user-42", Pulse.currentUserId)
    }

    @Test
    fun clearUserBeforeConfigurePersistsLogoutIntoNextSession() {
        // Establish a saved real user id, then log out before a fresh configure.
        configure()
        Pulse.setUser("real-user-42")
        Pulse.resetForTesting()
        Pulse.clearUser()
        configure()
        // The pre-configure logout was applied: resolution falls back to anon.
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }
}
