package com.wjbyt.j2h.storage

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore by preferencesDataStore(name = "tree_uris")
private val KEY_URIS = stringSetPreferencesKey("uris")

class TreeUriStore(private val context: Context) {

    val uris: Flow<List<Uri>> = context.dataStore.data.map { prefs ->
        prefs[KEY_URIS].orEmpty().map(Uri::parse)
    }

    suspend fun add(uri: Uri) {
        // Persist read+write permission across reboots.
        try {
            context.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) { /* already taken */ }
        context.dataStore.edit { prefs ->
            val s = (prefs[KEY_URIS].orEmpty() + uri.toString()).toMutableSet()
            prefs[KEY_URIS] = s
        }
    }

    suspend fun remove(uri: Uri) {
        try {
            context.contentResolver.releasePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
        } catch (_: Exception) {}
        context.dataStore.edit { prefs ->
            val s = prefs[KEY_URIS].orEmpty().toMutableSet()
            s.remove(uri.toString())
            prefs[KEY_URIS] = s
        }
    }

    suspend fun snapshot(): List<Uri> = uris.first()
}
