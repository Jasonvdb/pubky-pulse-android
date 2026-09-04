package org.pubky.pulse.android

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Deeper [Pulse] identity coverage complementing [PulseIdentityTest]: idempotent
 * setUser, user switching, clearUser before configure, session-id regeneration
 * on reconfigure, anon-id stability across login/logout cycles, and the
 * documented pre-configure persistence contract.
 *
 * Mirrors the Swift `Pulse` identity semantics: identity is "saved real user id,
 * otherwise the persistent anonymous id"; [Pulse.currentUserId] surfaces whichever
 * is active.
 */
@RunWith(RobolectricTestRunner::class)
class PulseIdentityDeepTest {

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
            endpoint = "https://ingest.pulse.pubky.org",
            apiKey = "pulse_client_test",
        )
    }

    // MARK: - setUser semantics

    @Test
    fun setUserIsIdempotentForTheSameIdentifier() {
        configure()
        Pulse.setUser("u-1")
        Pulse.setUser("u-1")
        assertEquals("u-1", Pulse.currentUserId)
        // Persisted exactly once → reconfigure resolves the same id.
        Pulse.resetForTesting()
        configure()
        assertEquals("u-1", Pulse.currentUserId)
    }

    @Test
    fun setUserSwitchesBetweenIdentifiers() {
        configure()
        Pulse.setUser("alice")
        assertEquals("alice", Pulse.currentUserId)
        Pulse.setUser("bob")
        assertEquals("bob", Pulse.currentUserId)
        // The most-recent id is the one persisted.
        Pulse.resetForTesting()
        configure()
        assertEquals("bob", Pulse.currentUserId)
    }

    @Test
    fun setUserAfterClearUserRelogsIn() {
        configure()
        Pulse.setUser("alice")
        Pulse.clearUser()
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
        Pulse.setUser("alice")
        assertEquals("alice", Pulse.currentUserId)
    }

    // MARK: - clearUser semantics

    @Test
    fun clearUserBeforeConfigureIsNoOp() {
        // Mirrors setUserBeforeConfigureIsNoOp in PulseIdentityTest: the surface is
        // gated on configured state, so an unconfigured clearUser can't throw or
        // mutate anything observable.
        Pulse.clearUser()
        Pulse.clearUser(newAnonymousId = true)
        assertNull(Pulse.currentUserId)
        // After configure the anon id resolves normally.
        configure()
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }

    @Test
    fun clearUserIsIdempotent() {
        configure()
        val anon = Pulse.currentUserId
        Pulse.setUser("alice")
        Pulse.clearUser()
        Pulse.clearUser()
        assertEquals(anon, Pulse.currentUserId)
    }

    @Test
    fun clearUserWithNewAnonymousIdThenPlainClearKeepsTheNewAnon() {
        configure()
        Pulse.setUser("alice")
        Pulse.clearUser(newAnonymousId = true)
        val freshAnon = Pulse.currentUserId
        assertNotNull(freshAnon)
        // A subsequent plain clearUser reverts to the *current* (fresh) anon id,
        // not the original — clearUser() reads s.anonymousId, which was updated.
        Pulse.setUser("bob")
        Pulse.clearUser()
        assertEquals(freshAnon, Pulse.currentUserId)
    }

    @Test
    fun resetAnonymousIdViaClearUserPersistsAcrossReconfigure() {
        configure()
        val original = Pulse.currentUserId
        Pulse.clearUser(newAnonymousId = true)
        val fresh = Pulse.currentUserId
        assertNotEquals(original, fresh)
        // The fresh anon id was written through to storage → reconfigure reads it.
        Pulse.resetForTesting()
        configure()
        assertEquals(fresh, Pulse.currentUserId)
    }

    // MARK: - Session id

    @Test
    fun sessionIdIsNonNullAndUuidShapedAfterConfigure() {
        configure()
        val session = Pulse.sessionId
        assertNotNull(session)
        assertEquals(36, session!!.length)
        // Canonical UUID round-trip.
        assertEquals(session, java.util.UUID.fromString(session).toString())
    }

    @Test
    fun reconfigureMintsAFreshSessionId() {
        configure()
        val first = Pulse.sessionId
        Pulse.resetForTesting()
        configure()
        val second = Pulse.sessionId
        assertNotNull(first)
        assertNotNull(second)
        // Each configure starts a new session (Swift sets s.sessionId = UUID()).
        assertNotEquals(first, second)
    }

    // MARK: - Anonymous id stability

    @Test
    fun anonymousIdSurvivesLoginLogoutCycleWithoutReset() {
        configure()
        val anon = Pulse.currentUserId
        Pulse.setUser("alice")
        Pulse.clearUser() // plain clear → same anon
        Pulse.setUser("bob")
        Pulse.clearUser() // plain clear → same anon
        assertEquals("plain login/logout cycles never re-mint the anon id", anon, Pulse.currentUserId)
    }

    @Test
    fun setUserDoesNotDisturbThePersistedAnonymousId() {
        configure()
        val anon = Pulse.currentUserId
        Pulse.setUser("alice")
        // Even with a real user active, the underlying anon id is preserved so a
        // later clearUser can revert to it.
        Pulse.clearUser()
        assertEquals(anon, Pulse.currentUserId)
    }

    // MARK: - Pre-configure persistence (Swift parity)

    @Test
    fun setUserBeforeConfigureResolvesToRealUserAfterConfigure() {
        // Swift's `setUser` persists unconditionally (only the server claim is
        // transport-gated), so a pre-configure setUser must win over the anon id
        // at the next configure. The Android port stashes the id and applies it
        // at configure() (where the persistence Context first exists).
        Pulse.setUser("pre-config-user")
        assertNull(Pulse.currentUserId) // no resolved state until configure
        configure()
        assertEquals("pre-config-user", Pulse.currentUserId)
    }

    @Test
    fun clearBeforeConfigureBeatsEarlierPreConfigureSetUser() {
        // Last write wins, and clear takes precedence: a pre-configure setUser
        // followed by a pre-configure clearUser resolves to the anon id.
        Pulse.setUser("pre-config-user")
        Pulse.clearUser()
        configure()
        assertTrue(Pulse.currentUserId!!.startsWith(IdentityStore.ANONYMOUS_ID_PREFIX))
    }
}
