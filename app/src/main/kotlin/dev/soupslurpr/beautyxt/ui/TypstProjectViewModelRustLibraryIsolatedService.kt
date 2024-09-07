package dev.soupslurpr.beautyxt.ui

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.core.os.bundleOf
import dev.soupslurpr.beautyxt.ITypstProjectViewModelRustLibraryAidlInterface
import dev.soupslurpr.beautyxt.PathAndPfd

class TypstProjectViewModelRustLibraryIsolatedService : Service() {
    private val binder = object : ITypstProjectViewModelRustLibraryAidlInterface.Stub() {
        override fun initializeTypstWorld() {
            return uniffi.beautyxt_rs_typst.initializeTypstWorld()
        }

        override fun setMainTypstProjectFile(papfd: PathAndPfd?) {
            return uniffi.beautyxt_rs_typst.setMainTypstProjectFile(
                uniffi.beautyxt_rs_typst.TypstProjectFilePathAndFd(
                    papfd!!.path,
                    papfd.pfd.detachFd()
                )
            )
        }

        override fun addTypstProjectFiles(papfdList: MutableList<PathAndPfd>?) {
            return uniffi.beautyxt_rs_typst.addTypstProjectFiles(
                papfdList!!.map { papfd ->
                    uniffi.beautyxt_rs_typst.TypstProjectFilePathAndFd(
                        papfd.path,
                        papfd.pfd.detachFd()
                    )
                }
            )
        }

        override fun getTypstProjectFileText(path: String?): String {
            return uniffi.beautyxt_rs_typst.getTypstProjectFileText(
                path!!
            )
        }

        override fun getTypstSvg(): Bundle {
            val bundle = bundleOf()

            try {
                bundle.putByteArray("svg", uniffi.beautyxt_rs_typst.getTypstSvg())
            } catch (e: uniffi.beautyxt_rs_typst.RenderException.VecCustomSourceDiagnostic) {
                e.customSourceDiagnostics.forEachIndexed { index, typstCustomSourceDiagnostic ->
                    bundle.putString(
                        "severity$index", when (typstCustomSourceDiagnostic.severity) {
                            uniffi.beautyxt_rs_typst.TypstCustomSeverity.WARNING -> "WARNING"
                            uniffi.beautyxt_rs_typst.TypstCustomSeverity.ERROR -> "ERROR"
                        }
                    )

                    bundle.putLong("span$index", typstCustomSourceDiagnostic.span.toLong())

                    bundle.putString("message$index", typstCustomSourceDiagnostic.message)

                    bundle.putInt("trace$index", typstCustomSourceDiagnostic.trace.size - 1)
                    typstCustomSourceDiagnostic.trace.forEachIndexed { traceIndex, typstCustomTracepoint ->
                        val prefix = "trace${index}name${traceIndex}"
                        when (typstCustomTracepoint) {
                            is uniffi.beautyxt_rs_typst.TypstCustomTracepoint.Call -> {
                                bundle.putString(prefix, "Call")
                                bundle.putString("${prefix}string", typstCustomTracepoint.string)
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is uniffi.beautyxt_rs_typst.TypstCustomTracepoint.Import -> {
                                bundle.putString(prefix, "Import")
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is uniffi.beautyxt_rs_typst.TypstCustomTracepoint.Show -> {
                                bundle.putString(prefix, "Show")
                                bundle.putString("${prefix}string", typstCustomTracepoint.string)
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }
                        }
                    }

                    bundle.putStringArrayList("hints$index", ArrayList(typstCustomSourceDiagnostic.hints))
                }
            }

            return bundle
        }

        override fun getTypstPdf(): ByteArray {
            return uniffi.beautyxt_rs_typst.getTypstPdf()
        }

        override fun updateTypstProjectFile(newText: String?, path: String?): String {
            return uniffi.beautyxt_rs_typst.updateTypstProjectFile(newText!!, path!!)
        }

        override fun clearTypstProjectFiles() {
            return uniffi.beautyxt_rs_typst.clearTypstProjectFiles()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}