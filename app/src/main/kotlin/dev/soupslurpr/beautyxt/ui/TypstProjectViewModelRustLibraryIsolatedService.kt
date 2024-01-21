package dev.soupslurpr.beautyxt.ui

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.core.os.bundleOf
import dev.soupslurpr.beautyxt.ITypstProjectViewModelRustLibraryAidlInterface
import dev.soupslurpr.beautyxt.PathAndPfd
import dev.soupslurpr.beautyxt.bindings.RenderException
import dev.soupslurpr.beautyxt.bindings.TypstCustomSeverity
import dev.soupslurpr.beautyxt.bindings.TypstCustomTracepoint
import dev.soupslurpr.beautyxt.bindings.TypstProjectFilePathAndFd

class TypstProjectViewModelRustLibraryIsolatedService : Service() {
    private val binder = object : ITypstProjectViewModelRustLibraryAidlInterface.Stub() {
        override fun initializeTypstWorld() {
            return dev.soupslurpr.beautyxt.bindings.initializeTypstWorld()
        }

        override fun setMainTypstProjectFile(papfd: PathAndPfd?) {
            return dev.soupslurpr.beautyxt.bindings.setMainTypstProjectFile(
                TypstProjectFilePathAndFd(
                    papfd!!.path,
                    papfd.pfd.detachFd()
                )
            )
        }

        override fun addTypstProjectFiles(papfdList: MutableList<PathAndPfd>?) {
            return dev.soupslurpr.beautyxt.bindings.addTypstProjectFiles(
                papfdList!!.map { papfd ->
                    TypstProjectFilePathAndFd(
                        papfd.path,
                        papfd.pfd.detachFd()
                    )
                }
            )
        }

        override fun getTypstProjectFileText(path: String?): String {
            return dev.soupslurpr.beautyxt.bindings.getTypstProjectFileText(
                path!!
            )
        }

        override fun getTypstSvg(): Bundle {
            val bundle = bundleOf()

            try {
                bundle.putByteArray("svg", dev.soupslurpr.beautyxt.bindings.getTypstSvg())
            } catch (e: RenderException.VecCustomSourceDiagnostic) {
                e.customSourceDiagnostics.forEachIndexed { index, typstCustomSourceDiagnostic ->
                    bundle.putString(
                        "severity$index", when (typstCustomSourceDiagnostic.severity) {
                            TypstCustomSeverity.WARNING -> "WARNING"
                            TypstCustomSeverity.ERROR -> "ERROR"
                        }
                    )

                    bundle.putLong("span$index", typstCustomSourceDiagnostic.span.toLong())

                    bundle.putString("message$index", typstCustomSourceDiagnostic.message)

                    bundle.putInt("trace$index", typstCustomSourceDiagnostic.trace.size - 1)
                    typstCustomSourceDiagnostic.trace.forEachIndexed { traceIndex, typstCustomTracepoint ->
                        val prefix = "trace${index}name${traceIndex}"
                        when (typstCustomTracepoint) {
                            is TypstCustomTracepoint.Call -> {
                                bundle.putString(prefix, "Call")
                                bundle.putString("${prefix}string", typstCustomTracepoint.string)
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is TypstCustomTracepoint.Import -> {
                                bundle.putString(prefix, "Import")
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is TypstCustomTracepoint.Show -> {
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
            return dev.soupslurpr.beautyxt.bindings.getTypstPdf()
        }

        override fun updateTypstProjectFile(newText: String?, path: String?): String {
            return dev.soupslurpr.beautyxt.bindings.updateTypstProjectFile(newText!!, path!!)
        }

        override fun clearTypstProjectFiles() {
            return dev.soupslurpr.beautyxt.bindings.clearTypstProjectFiles()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}