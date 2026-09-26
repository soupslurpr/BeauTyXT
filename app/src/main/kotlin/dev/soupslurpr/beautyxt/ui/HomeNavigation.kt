@file:OptIn(androidx.compose.material3.ExperimentalMaterial3ExpressiveApi::class)

package dev.soupslurpr.beautyxt.ui

import androidx.compose.animation.AnimatedContentTransitionScope.SlideDirection
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.IntOffset
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.NavEntryDecorator
import androidx.navigation3.runtime.rememberDecoratedNavEntries
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.scene.NavigationBackHandler
import androidx.navigation3.scene.SinglePaneSceneStrategy
import androidx.navigation3.scene.rememberNavigationEventState
import androidx.navigation3.scene.rememberSceneState
import androidx.navigation3.ui.NavDisplay
import androidx.navigationevent.NavigationEvent
import androidx.navigationevent.NavigationEventTransitionState.InProgress
import dev.soupslurpr.beautyxt.InitialDocumentAction
import java.io.Serializable
import java.util.UUID

private sealed interface HomeDestination : Serializable {
    data object Home : HomeDestination
    data object About : HomeDestination
    data object Notices : HomeDestination
    data class Document(val id: String, val action: InitialDocumentAction) : HomeDestination
}

private val HomeDestination.contentKey: String
    get() = (this as? HomeDestination.Document)?.id ?: toString()

/** Keeps Home's pages and document sessions in one task with independent entry lifetimes. */
@Composable
internal fun HomeNavigation(
    versionName: String,
    homeContent: @Composable (onAbout: () -> Unit, onDocument: (InitialDocumentAction) -> Unit) -> Unit
) {
    // Document keys contain only an opaque identity and a starting action, never text or URIs.
    var backStack by rememberSaveable { mutableStateOf(listOf<HomeDestination>(HomeDestination.Home)) }
    val documentBack = remember { DocumentNavigationBack() }
    val popDirections = remember { mutableStateMapOf<Any, SlideDirection>() }
    val movement = MaterialTheme.motionScheme.defaultSpatialSpec<IntOffset>()
    val predictiveMovement = tween<IntOffset>(durationMillis = 300, easing = LinearEasing)
    val shading = MaterialTheme.motionScheme.defaultEffectsSpec<Float>()
    val predictiveShading = tween<Float>(durationMillis = 300, easing = LinearEasing)

    fun navigate(from: HomeDestination, destination: HomeDestination) {
        if (backStack.last() == from) {
            backStack = backStack + destination
        }
    }

    fun goBack() {
        if (backStack.size > 1) {
            popDirections[backStack.last().contentKey] = SlideDirection.End
            backStack = backStack.dropLast(1)
        }
    }

    fun requestBack() {
        if (backStack.last() is HomeDestination.Document) documentBack.requestBack()
        else goBack()
    }

    fun openDocument(action: InitialDocumentAction) {
        if (backStack.last() == HomeDestination.Home) {
            backStack = backStack + HomeDestination.Document(UUID.randomUUID().toString(), action)
        }
    }

    val entries = rememberDecoratedNavEntries(
        backStack = backStack,
        entryDecorators = listOf(
            rememberSaveableStateHolderNavEntryDecorator(),
            rememberSessionEntryDecorator(),
            remember {
                NavEntryDecorator<HomeDestination>(
                    onPop = { key -> popDirections.remove(key) },
                    decorate = { entry -> entry.Content() }
                )
            }
        )
    ) { destination ->
        NavEntry(
            destination,
            contentKey = destination.contentKey
        ) {
            when (destination) {
                HomeDestination.Home -> homeContent(
                    { navigate(HomeDestination.Home, HomeDestination.About) },
                    ::openDocument
                )
                HomeDestination.About -> AboutScreen(
                    versionName = versionName,
                    onBack = ::goBack,
                    onOpenThirdPartyNotices = {
                        navigate(HomeDestination.About, HomeDestination.Notices)
                    }
                )
                HomeDestination.Notices -> ThirdPartyNoticesScreen(onBack = ::goBack)
                is HomeDestination.Document -> CompositionLocalProvider(
                    LocalDocumentNavigationBack provides documentBack
                ) {
                    HomeDocumentScreen(
                        initialAction = destination.action,
                        isCurrentEntry = { backStack.lastOrNull() == destination },
                        onClose = {
                            if (backStack.lastOrNull() == destination) goBack()
                        }
                    )
                }
            }
        }
    }

    val sceneState = rememberSceneState(
        entries = entries,
        sceneStrategies = remember { listOf(SinglePaneSceneStrategy<HomeDestination>()) },
        onBack = ::requestBack
    )
    val navigationState = rememberNavigationEventState(sceneState)
    // Completion publishes Idle before invoking its callback. Capture the edge in the
    // current callback while gesture progress is composed, rather than reading it later.
    val gestureDirection = when (
        (navigationState.transitionState as? InProgress)?.latestEvent?.swipeEdge
    ) {
        NavigationEvent.EDGE_LEFT -> SlideDirection.Right
        NavigationEvent.EDGE_RIGHT -> SlideDirection.Left
        else -> SlideDirection.End
    }
    NavigationBackHandler(
        sceneState = sceneState,
        state = navigationState,
        onBackCompleted = {
            val outgoing = backStack.last()
            requestBack()
            if (backStack.lastOrNull() != outgoing) {
                // Continue in the gesture's direction after release. Keep it per entry so
                // another Back cannot reverse an earlier page that is still exiting.
                popDirections[outgoing.contentKey] = gestureDirection
            }
        }
    )
    NavDisplay(
        sceneState = sceneState,
        navigationEventState = navigationState,
        // The lower page moves one-third as far and dims by 10%.
        // Black under its translucent layer provides the shading without fading
        // the foreground page or changing the underlying screen's layout.
        modifier = Modifier.fillMaxSize().background(Color.Black),
        transitionSpec = {
            slideIntoContainer(SlideDirection.Start, movement) togetherWith
                (slideOutOfContainer(SlideDirection.Start, movement) { it / 3 } +
                    fadeOut(shading, targetAlpha = .9f))
        },
        popTransitionSpec = {
            val direction = popDirections[initialState.entries.last().contentKey]
                ?: SlideDirection.End
            (slideIntoContainer(direction, movement) { it / 3 } +
                fadeIn(shading, initialAlpha = .9f)) togetherWith
                slideOutOfContainer(direction, movement)
        },
        predictivePopTransitionSpec = { edge ->
            val direction = if (edge == NavigationEvent.EDGE_RIGHT) {
                SlideDirection.Left
            } else {
                SlideDirection.Right
            }
            (slideIntoContainer(direction, predictiveMovement) { it / 3 } +
                fadeIn(predictiveShading, initialAlpha = .9f)) togetherWith
                slideOutOfContainer(direction, predictiveMovement)
        }
    )
}
