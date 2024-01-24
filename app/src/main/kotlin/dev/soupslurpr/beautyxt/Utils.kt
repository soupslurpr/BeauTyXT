package dev.soupslurpr.beautyxt

import java.security.MessageDigest

fun returnHashSha256(byteArray: ByteArray): String {
    return MessageDigest
        .getInstance("SHA-256")
        .digest(byteArray)
        .joinToString("") {
            "%02x".format(it)
        }
}
