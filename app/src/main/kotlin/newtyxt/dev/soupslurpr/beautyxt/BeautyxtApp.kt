package dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.LocalActivity
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.rememberTopAppBarState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navDeepLink
import dev.soupslurpr.beautyxt.R
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.EditorScreen
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.EditorViewModel
import dev.soupslurpr.beautyxt.newtyxt.dev.soupslurpr.beautyxt.ui.HomeScreen
import kotlinx.serialization.Serializable

@Serializable
object Home

@Serializable
object Editor
//@Serializable
//data class Editor(val uriString: String) {
//    companion object {
//        fun from(savedStateHandle: SavedStateHandle) = savedStateHandle.toRoute<Editor>()
//    }
//}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BeautyxtApp(
    navController: NavHostController = rememberNavController()
) {
    val topAppBarScrollBehavior =
        TopAppBarDefaults.enterAlwaysScrollBehavior(rememberTopAppBarState())

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "NewTyXT! Yeah, title support needs to be implemented",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                },
                navigationIcon = {
                    if (navController.previousBackStackEntry != null) {
                        IconButton(onClick = { navController.navigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = stringResource(id = R.string.navigate_up_button_description)
                            )
                        }
                    }
                },
                actions = {},
                scrollBehavior = topAppBarScrollBehavior
            )
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Home,
            // TODO: remove this, it's just until things settle down
            modifier = Modifier.padding(innerPadding)
        ) {
            composable<Home> {
                HomeScreen()
            }
            composable<Editor>(
                deepLinks = listOf(
                    navDeepLink {
                        this.action = Intent.ACTION_VIEW
                        this.mimeType = "text/*"
                    },
                    navDeepLink {
                        this.action = Intent.ACTION_EDIT
                        this.mimeType = "text/*"
                    }
                )
            ) {
                val viewModel = hiltViewModel<EditorViewModel>()
                EditorScreen(LocalActivity.current?.intent?.data ?: Uri.EMPTY, viewModel)
            }
        }
    }
}