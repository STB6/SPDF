package com.stb6.spdf.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class SettingsWriteLifetimeTest {
    @Test fun leavingPageDoesNotCancelStartedWrite() = runBlocking {
        val owner = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val page = CoroutineScope(Job() + Dispatchers.Unconfined)
        val gate = CompletableDeferred<Unit>()
        val began = CompletableDeferred<Unit>()
        val values = MutableStateFlow<Preferences>(emptyPreferences())
        val store = object : DataStore<Preferences> {
            override val data = values
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                began.complete(Unit)
                gate.await()
                return transform(values.value).also { values.value = it }
            }
        }
        val repository = DataStoreSettingsRepository(store, owner)
        lateinit var commit: Deferred<ReaderSettings?>
        try {
            page.launch { commit = repository.update { it.copy(pageLabelHalfSeconds = 4) }; commit.await() }
            began.await()
            page.cancel()
            assertTrue(commit.isActive)
            gate.complete(Unit)
            assertEquals(4, commit.await()!!.pageLabelHalfSeconds)
        } finally { page.cancel(); owner.cancel() }
    }
}
