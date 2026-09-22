package dev.soupslurpr.beautyxt.importing

import android.content.Context
import android.content.Intent
import dev.soupslurpr.beautyxt.ipc.NativeServiceNames

/** Targets the native manifest component, which has no Kotlin Service class. */
internal fun importServiceIntent(context: Context): Intent = Intent().setClassName(
    context,
    NativeServiceNames.IMPORT
)
