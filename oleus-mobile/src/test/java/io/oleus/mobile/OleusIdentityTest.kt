package io.oleus.mobile

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** In-memory IdentityStore so identity logic is testable without an Android Context. */
private class FakeStore(initial: Map<String, String> = emptyMap()) : IdentityStore {
    val data = initial.toMutableMap()
    override fun getString(key: String): String? = data[key]
    override fun putString(key: String, value: String) { data[key] = value }
    override fun remove(key: String) { data.remove(key) }
}

class OleusIdentityTest {

    @Test fun anonIdIsGeneratedAndPersisted() {
        val store = FakeStore()
        val id = OleusIdentity(store)
        assertTrue(id.anonId.isNotEmpty())
        assertEquals(id.anonId, store.getString("anon_id"))
    }

    @Test fun distinctIdDefaultsToAnonBeforeIdentify() {
        val id = OleusIdentity(FakeStore())
        assertEquals(id.anonId, id.distinctId)
    }

    @Test fun existingAnonIdIsReused() {
        val store = FakeStore(mapOf("anon_id" to "anon-persisted"))
        assertEquals("anon-persisted", OleusIdentity(store).anonId)
    }

    @Test fun identifySwitchesDistinctIdButKeepsAnon() {
        val store = FakeStore()
        val id = OleusIdentity(store)
        val anon = id.anonId
        id.identify("user-1")
        assertEquals("user-1", id.distinctId)
        assertEquals("user-1", store.getString("distinct_id"))
        assertEquals(anon, id.anonId)
    }

    @Test fun resetRotatesAnonAndClearsIdentity() {
        val store = FakeStore()
        val id = OleusIdentity(store)
        val anon = id.anonId
        id.identify("user-1")
        id.reset()
        assertNotEquals(anon, id.anonId)
        assertEquals(id.anonId, id.distinctId)
        assertEquals(null, store.getString("distinct_id"))
    }
}
