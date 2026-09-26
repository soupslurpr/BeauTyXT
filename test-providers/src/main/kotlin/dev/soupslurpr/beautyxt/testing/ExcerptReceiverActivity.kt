package dev.soupslurpr.beautyxt.testing

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import android.os.Process
import android.provider.OpenableColumns
import android.system.Os
import android.system.OsConstants
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import dev.soupslurpr.beautyxt.testproviders.R
import java.security.MessageDigest

/** A separate-UID receiver with deliberately delayed reads and an independently held descriptor. */
class ExcerptReceiverActivity : Activity() {
    private lateinit var status: TextView
    private var held: ParcelFileDescriptor? = null
    private var incarnation = 0
    private val source: Uri get() = checkNotNull(intent.getParcelableExtra(Intent.EXTRA_STREAM, Uri::class.java))

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        incarnation = (savedInstanceState?.getInt("incarnation") ?: -1) + 1
        title = getString(R.string.excerpt_receiver)
        val column = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        status = TextView(this).apply { text = getString(R.string.excerpt_receiver_ready, Process.myUid()) + "; instance $incarnation" }
        column.addView(status)
        fun button(label: Int, action: () -> Unit) {
            column.addView(Button(this).apply {
                setText(label)
                setOnClickListener { action() }
            })
        }
        button(R.string.excerpt_read) { readNew() }
        button(R.string.excerpt_recreate, ::recreate)
        button(R.string.excerpt_hold) {
            held?.close()
            held = contentResolver.openFileDescriptor(source, "r")
            status.setText(R.string.excerpt_handle_open)
        }
        button(R.string.excerpt_read_held) {
            val input = checkNotNull(held)
            Os.lseek(input.fileDescriptor, 0, OsConstants.SEEK_SET)
            ParcelFileDescriptor.dup(input.fileDescriptor).use { showBytes(it, "held") }
        }
        button(R.string.excerpt_receiver_finish, ::finish)
        setContentView(column)
    }

    private fun readNew() {
        try {
            val mime = contentResolver.getType(source) ?: throw java.io.FileNotFoundException()
            val name = (contentResolver.query(source, null, null, null, null)
                ?: throw java.io.FileNotFoundException()).use {
                check(it.moveToFirst())
                it.getString(it.getColumnIndexOrThrow(OpenableColumns.DISPLAY_NAME))
            }
            val writeDenied = try {
                contentResolver.openFileDescriptor(source, "rw")?.close()
                false
            } catch (_: SecurityException) { true }
            catch (_: java.io.FileNotFoundException) { true }
            check(writeDenied) { "Receiver gained write access" }
            checkNotNull(contentResolver.openFileDescriptor(source, "r")).use { first ->
                Os.lseek(first.fileDescriptor, 5, OsConstants.SEEK_SET)
                checkNotNull(contentResolver.openFileDescriptor(source, "r")).use { second ->
                    showBytes(second, "$mime / $name / read-only")
                }
            }
        } catch (_: SecurityException) {
            status.setText(R.string.excerpt_read_denied)
        } catch (_: java.io.FileNotFoundException) {
            status.setText(R.string.excerpt_read_denied)
        }
    }

    private fun showBytes(input: ParcelFileDescriptor, detail: String) {
        check(Os.fcntlInt(input.fileDescriptor, OsConstants.F_GETFL, 0) and OsConstants.O_ACCMODE == OsConstants.O_RDONLY)
        val bytes = ParcelFileDescriptor.AutoCloseInputStream(input).use { it.readNBytes(2 * 1024 * 1024 + 1) }
        check(bytes.size <= 2 * 1024 * 1024) { "Fixture only accepts small synthetic excerpts" }
        val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it.toInt() and 255) }
        status.text = getString(R.string.excerpt_read_result, bytes.size, digest, Process.myUid(), detail)
    }

    override fun onDestroy() {
        held?.close()
        held = null
        super.onDestroy()
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putInt("incarnation", incarnation)
        super.onSaveInstanceState(outState)
    }
}
