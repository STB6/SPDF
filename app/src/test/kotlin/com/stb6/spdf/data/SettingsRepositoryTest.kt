package com.stb6.spdf.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.emptyPreferences
import java.io.IOException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test

class SettingsRepositoryTest {
    @Test fun readFailureUsesDefaultsWithoutRetryingOrWriting() = runBlocking {
        var reads = 0
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { reads++; throw IOException("Read failed") }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences =
                error("Defaults must not overwrite stored preferences")
        }
        val repository = DataStoreSettingsRepository(store, this)
        assertEquals(ReaderSettings(), repository.settings.first())
        assertEquals(1, reads)
    }

    @Test fun failedWriteReturnsNoReceiptAndPreservesStoredValues() = runBlocking {
        val current = MutableStateFlow(emptyPreferences())
        var fail = true
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = current
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences {
                if (fail) throw IOException("Write failed")
                return transform(current.value).also { current.value = it }
            }
        }
        val repository = DataStoreSettingsRepository(store, this)
        val initial = repository.settings.first()
        assertNull(repository.update { it.copy(keepScreenOn = !initial.keepScreenOn) }.await())
        assertEquals(initial, repository.settings.first())
        fail = false
        assertNotNull(repository.update { it.copy(keepScreenOn = !initial.keepScreenOn) }.await())
        assertEquals(!initial.keepScreenOn, repository.settings.first().keepScreenOn)
    }

    @Test fun programmingErrorsAreNotTreatedAsMissingPreferences() = runBlocking {
        val failure = IllegalArgumentException("Invalid store")
        val store = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw failure }
            override suspend fun updateData(transform: suspend (Preferences) -> Preferences): Preferences = error("Not used")
        }
        try {
            DataStoreSettingsRepository(store, this).settings.first()
            fail("Expected the original error")
        } catch (actual: IllegalArgumentException) {
            assertSame(failure, actual)
        }
    }
}
