package io.oleus.mobile

import java.util.UUID

/**
 * Minimal persistence abstraction so identity logic is unit-testable without
 * an Android Context (the production impl wraps SharedPreferences).
 */
internal interface IdentityStore {
    fun getString(key: String): String?
    fun putString(key: String, value: String)
    fun remove(key: String)
}

internal class SharedPrefsIdentityStore(
    private val prefs: android.content.SharedPreferences,
) : IdentityStore {
    override fun getString(key: String): String? = prefs.getString(key, null)
    override fun putString(key: String, value: String) { prefs.edit().putString(key, value).apply() }
    override fun remove(key: String) { prefs.edit().remove(key).apply() }
}

/**
 * Anonymous + identified ids for the current install.
 * `distinctId` is sent on every event; `identify` ties it to a known user;
 * `reset` forgets the user and rotates to a fresh anonymous id (logout).
 */
internal class OleusIdentity(private val store: IdentityStore) {
    private companion object {
        const val ANON_KEY = "anon_id"
        const val DISTINCT_KEY = "distinct_id"
    }

    @Volatile
    var anonId: String = store.getString(ANON_KEY) ?: UUID.randomUUID().toString()
        .also { store.putString(ANON_KEY, it) }
        private set

    @Volatile
    var distinctId: String = store.getString(DISTINCT_KEY) ?: anonId
        private set

    @Synchronized
    fun identify(id: String) {
        distinctId = id
        store.putString(DISTINCT_KEY, id)
    }

    @Synchronized
    fun reset() {
        val newAnon = UUID.randomUUID().toString()
        store.remove(DISTINCT_KEY)
        store.putString(ANON_KEY, newAnon)
        anonId = newAnon
        distinctId = newAnon
    }
}
