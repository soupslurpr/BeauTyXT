package dev.soupslurpr.beautyxt.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.toMutableStateList
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import coil.compose.AsyncImage
import coil.decode.SvgDecoder
import coil.request.ImageRequest
import dev.soupslurpr.beautyxt.constants.mimeTypePdf
import dev.soupslurpr.beautyxt.getProjectFileText
import dev.soupslurpr.beautyxt.testGetMainSvg
import dev.soupslurpr.beautyxt.updateProjectFile
import kotlinx.coroutines.Dispatchers

@Composable
fun TypstProjectScreen(
    typstProjectViewModel: TypstProjectViewModel,
) {
    val context = LocalContext.current

    val exportAsPdfFileLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.CreateDocument(
            mimeTypePdf
        )
    ) {
        if (it != null) {
            typstProjectViewModel.testExportDocumentToPdf(it, context)
        }
    }

    val svgs = rememberSaveable(
        saver = listSaver(
            save = { snapshotStateList ->
                if (snapshotStateList.isNotEmpty()) {
                    val firstElement = snapshotStateList.first()
                    if (!canBeSaved(firstElement)) {
                        throw IllegalStateException(
                            "${firstElement.javaClass} can't be saved! By default " +
                                    "everything which can be stored in the Bundle class can be saved."
                        )
                    }
                }
                snapshotStateList.toList()
            },
            restore = { saveableList ->
                saveableList.toMutableStateList()
            }
        )
    ) {
        mutableStateListOf<ByteArray>()
    }

    val imageRequestBuilder = ImageRequest.Builder(context = context)
        .decoderFactory(SvgDecoder.Factory())
        .decoderDispatcher(Dispatchers.IO)

    val svgPreviewVerticalScroll = rememberScrollState()

    var content by remember { mutableStateOf(getProjectFileText("/main.typ")) }

    Column(
        modifier = Modifier.imePadding()
    ) {
        LazyColumn {
            item {
                TextField(
                    value = content,
                    onValueChange = {
                        content = updateProjectFile(it, "/main.typ")

                        // We have to get it twice or it doesn't work, might be a bug in the Rust code, but its weird
                        // how it doesn't happen when doing it once in a button.
                        var newSvgs = try {
                            testGetMainSvg()
                        } catch (e: RuntimeException) {
                            throw RuntimeException(e)
                        }
                        newSvgs = try {
                            testGetMainSvg()
                        } catch (e: RuntimeException) {
                            throw RuntimeException(e)
                        }
                        svgs.clear()
                        svgs.addAll(newSvgs)
                    }
                )
            }
            item {
                Button(
                    onClick = {
                        exportAsPdfFileLauncher.launch("")
                    },
                    content = {
                        Text(
                            "Export PDF"
                        )
                    }
                )
            }

            item {
                Button(
                    onClick = {
                        val newSvgs = testGetMainSvg()
                        svgs.clear()
                        svgs.addAll(newSvgs)
                    },
                    content = {
                        Text(
                            "Show SVG"
                        )
                    }
                )
            }
        }
        LazyColumn {
            item {
                svgs.forEachIndexed { index, svg ->
                    AsyncImage(
                        model = imageRequestBuilder.data(svg).build(),
                        contentDescription = null,
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }
        }
    }
}

@Composable
fun ScrollableSvgDocumentPreview(svgs: MutableList<ByteArray>) {
    val context = LocalContext.current

    val imageRequestBuilder = ImageRequest.Builder(context = context)
        .decoderFactory(SvgDecoder.Factory())
        .decoderDispatcher(Dispatchers.IO)

    LazyColumn(
        modifier = Modifier.verticalScroll(rememberScrollState()).fillMaxSize(),
    ) {
        items(svgs.size) {
            svgs.forEach { svg ->
                println("PROCESSING")
                AsyncImage(
                    model = imageRequestBuilder.data(svg).build(),
                    contentDescription = null,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        }
    }
}