package dev.soupslurpr.beautyxt.ipc

import java.util.UUID

/** Gives each binding its own isolated instance, including across application restarts. */
internal fun newIsolatedServiceInstanceName(): String =
    "worker_${UUID.randomUUID().toString().replace('-', '_')}"
