package oldtyxt.dev.soupslurpr.beautyxt

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.preferencesDataStore

val Context.oldtyxtDataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")