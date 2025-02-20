package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

// TODO: is this the right approach for preferences?
val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "preferences")