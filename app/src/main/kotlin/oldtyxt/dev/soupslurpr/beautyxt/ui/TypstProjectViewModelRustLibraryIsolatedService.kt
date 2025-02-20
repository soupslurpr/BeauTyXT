package oldtyxt.dev.soupslurpr.beautyxt.ui

import android.app.Service
import android.content.Intent
import android.os.Bundle
import android.os.IBinder
import androidx.core.os.bundleOf
import oldtyxt.dev.soupslurpr.beautyxt.ITypstProjectViewModelRustLibraryAidlInterface
import oldtyxt.dev.soupslurpr.beautyxt.PathAndPfd

class TypstProjectViewModelRustLibraryIsolatedService : Service() {
    private val binder = object : ITypstProjectViewModelRustLibraryAidlInterface.Stub() {
        override fun initializeTypstWorld() {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.initializeTypstWorld()
        }

        override fun setMainTypstProjectFile(papfd: PathAndPfd?) {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.setMainTypstProjectFile(
                oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstProjectFilePathAndFd(
                    papfd!!.path,
                    papfd.pfd.detachFd()
                )
            )
        }

        override fun addTypstProjectFiles(papfdList: MutableList<PathAndPfd>?) {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.addTypstProjectFiles(
                papfdList!!.map { papfd ->
                    oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstProjectFilePathAndFd(
                        papfd.path,
                        papfd.pfd.detachFd()
                    )
                }
            )
        }

        override fun getTypstProjectFileText(path: String?): String {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.getTypstProjectFileText(
                path!!
            )
        }

        override fun getTypstSvg(): Bundle {
            val bundle = bundleOf()

            try {
                bundle.putByteArray("svg", oldtyxt.uniffi.beautyxt_rs_typst_bindings.getTypstSvg())
            } catch (e: oldtyxt.uniffi.beautyxt_rs_typst_bindings.RenderException.VecCustomSourceDiagnostic) {
                e.customSourceDiagnostics.forEachIndexed { index, typstCustomSourceDiagnostic ->
                    bundle.putString(
                        "severity$index", when (typstCustomSourceDiagnostic.severity) {
                            oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstCustomSeverity.WARNING -> "WARNING"
                            oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstCustomSeverity.ERROR -> "ERROR"
                        }
                    )

                    bundle.putLong("span$index", typstCustomSourceDiagnostic.span.toLong())

                    bundle.putString("message$index", typstCustomSourceDiagnostic.message)

                    bundle.putInt("trace$index", typstCustomSourceDiagnostic.trace.size - 1)
                    typstCustomSourceDiagnostic.trace.forEachIndexed { traceIndex, typstCustomTracepoint ->
                        val prefix = "trace${index}name${traceIndex}"
                        when (typstCustomTracepoint) {
                            is oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstCustomTracepoint.Call -> {
                                bundle.putString(prefix, "Call")
                                bundle.putString("${prefix}string", typstCustomTracepoint.string)
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstCustomTracepoint.Import -> {
                                bundle.putString(prefix, "Import")
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }

                            is oldtyxt.uniffi.beautyxt_rs_typst_bindings.TypstCustomTracepoint.Show -> {
                                bundle.putString(prefix, "Show")
                                bundle.putString("${prefix}string", typstCustomTracepoint.string)
                                bundle.putLong("${prefix}span", typstCustomTracepoint.span.toLong())
                            }
                        }
                    }

                    bundle.putStringArrayList(
                        "hints$index",
                        ArrayList(typstCustomSourceDiagnostic.hints)
                    )
                }
            }

            return bundle
        }

        override fun getTypstPdf(): ByteArray {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.getTypstPdf()
        }

        override fun updateTypstProjectFile(newText: String?, path: String?): String {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.updateTypstProjectFile(
                newText!!,
                path!!
            )
        }

        override fun clearTypstProjectFiles() {
            return oldtyxt.uniffi.beautyxt_rs_typst_bindings.clearTypstProjectFiles()
        }
    }

    override fun onBind(intent: Intent?): IBinder {
        return binder
    }
}