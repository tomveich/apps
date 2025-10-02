package io.github.hristogochev.vortex.screen
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.DecayAnimationSpec
import androidx.compose.animation.core.Spring.StiffnessLow
import androidx.compose.animation.core.SpringSpec
import androidx.compose.animation.core.exponentialDecay
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.AnchoredDraggableState
import androidx.compose.foundation.gestures.DraggableAnchors
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.TargetedFlingBehavior
import androidx.compose.foundation.gestures.anchoredDraggable
import androidx.compose.foundation.gestures.animateTo
import androidx.compose.foundation.gestures.snapTo
import androidx.compose.foundation.gestures.snapping.SnapLayoutInfoProvider
import androidx.compose.foundation.gestures.snapping.snapFlingBehavior
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.snapshots.SnapshotStateList
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.zIndex
import io.github.hristogochev.vortex.navigator.Navigator
import io.github.hristogochev.vortex.screen.CurrentScreenNoTransitionsDisposable
import io.github.hristogochev.vortex.screen.Screen
import io.github.hristogochev.vortex.screen.render
import io.github.hristogochev.vortex.stack.StackEvent
import kotlinx.coroutines.flow.first
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Displays the current screen of a [Navigator] with an iOS-like swipe transition.
 *
 * Accepts specifications for the draggable region of transition.
 *
 * Ignores the override transitions for all screens rendered.
 */
@Composable
public fun NewCurrentScreenIOSSwipe(
    navigator: Navigator,
    draggableHandlePadding: PaddingValues = PaddingValues(top = 80.dp),
    draggableHandleWidth: Dp = 16.dp,
    draggableHandleFillMaxHeight: Boolean = true,
    content: @Composable (Screen) -> Unit = { it.Content() },
) {

    CurrentScreenNoTransitionsDisposable(navigator)

    BoxWithConstraints(Modifier.fillMaxSize()) {
        val maxWidthPx = constraints.maxWidth.toFloat()
        val maxWidthPxUpdated by rememberUpdatedState(maxWidthPx)

        val anchors by remember(maxWidthPx) {
            derivedStateOf {
                DraggableAnchors {
                    DismissValue.Start at 0f
                    DismissValue.End at maxWidthPx
                }
            }
        }

        val anchoredDraggableState = remember {
            AnchoredDraggableState(
                initialValue = DismissValue.Start,
                anchors = anchors
            )
        }


        val flingBehavior = iosFlingBehavior(
            state = anchoredDraggableState,
            positionalThreshold = { distance -> distance * 0.4f }, // Threshold for snapping
            velocityThreshold = { maxWidthPxUpdated / 3 }, // Minimum velocity to
            snapAnimationSpec = SpringSpec(stiffness = StiffnessLow),
            decayAnimationSpec = exponentialDecay(),
        )

        LaunchedEffect(anchors) {
            anchoredDraggableState.updateAnchors(anchors)
        }

        val last2NavigatorItems by remember(navigator.items) {
            derivedStateOf {
                navigator.items.takeLast(2)
            }
        }

        val screens = remember {
            when (last2NavigatorItems.size) {
                1 -> mutableStateListOf(last2NavigatorItems[0])
                else -> mutableStateListOf(last2NavigatorItems[0], last2NavigatorItems[1])
            }
        }

        val lastEventUpdated by rememberUpdatedState(navigator.lastEvent)

        var autoSwipe by remember { mutableStateOf(false) }

        val offset = anchoredDraggableState.offset

        LaunchedEffect(last2NavigatorItems) {
            when (lastEventUpdated) {
                StackEvent.Push -> {
                    autoSwipe = true

                    if (screens.size == 2) {
                        screens.removeAt(0)
                    }

                    anchoredDraggableState.snapTo(DismissValue.End)

                    val last2 = listOf(screens.last(), last2NavigatorItems.last())

                    syncScreens(last2, screens)

                    snapshotFlow { autoSwipe }
                        .first { !autoSwipe }

                    anchoredDraggableState.animateTo(
                        DismissValue.Start,
                        SpringSpec(stiffness = StiffnessLow)
                    )

                    syncScreens(last2NavigatorItems, screens)
                }

                // ✅ FIX: Replaced the entire logic for the 'Replace' event.
                StackEvent.Replace -> {
                    // For a 'replaceAll' operation, history is destroyed.
                    // We must not create a synthetic two-screen state.
                    // Instead, we perform a clean reset to the new single-screen state.

                    // 1. Get the new screen, which is the only one in the navigator's stack.
                    val newScreen = last2NavigatorItems.last()

                    // 2. Clear our internal screen list completely.
                    screens.clear()

                    // 3. Add the new screen. Our internal state now matches the navigator.
                    screens.add(newScreen)

                    // 4. Force the swipe gesture to reset to its default (non-swiped) position.
                    //    This prevents any lingering offset from previous states.
                    anchoredDraggableState.snapTo(DismissValue.Start)
                }

                StackEvent.Pop -> {
                    autoSwipe = true

                    val last2 = listOf(last2NavigatorItems.last(), screens.last())

                    syncScreens(last2, screens)

                    anchoredDraggableState.animateTo(
                        DismissValue.End,
                        SpringSpec(stiffness = StiffnessLow)
                    )

                    snapshotFlow { autoSwipe }
                        .first { !autoSwipe }

                    anchoredDraggableState.snapTo(DismissValue.Start)

                    syncScreens(last2NavigatorItems, screens)
                }

                StackEvent.PopGesture -> {
                    syncScreens(last2NavigatorItems, screens)
                    anchoredDraggableState.snapTo(DismissValue.Start)
                }

                StackEvent.Idle -> {
                    syncScreens(last2NavigatorItems, screens)
                }
            }
        }

        LaunchedEffect(offset) {
            // Only 1 screen, no popping, no nothing
            if (screens.size == 1) return@LaunchedEffect

            // Offset is not at the end, no popping, no nothing
            if (offset != maxWidthPxUpdated) return@LaunchedEffect

            // There are 2 screens and the offset reached the end

            // The offset was reached by a button click, therefore the button click took care of the popping
            if (autoSwipe) {
                autoSwipe = false
                return@LaunchedEffect
            }

            // The offset was reached manually (by swiping), trigger pop gesture as to not trigger normal pop events
            navigator.popGesture()
        }

        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            screens.forEachIndexed { index, screen ->

                val offsetModifier = when {
                    // Can't move if there is only 1 screen
                    screens.size == 1 -> Modifier
                    // Screen in background should be dimmed and slightly moved to imitate ios behaviour
                    screens.size == 2 && index == 0 -> Modifier
                        .offset {
                            val offsetX =
                                calculateBackgroundScreenOffset(offset, maxWidthPxUpdated)
                            IntOffset(offsetX.roundToInt(), 0)
                        }
                        .drawWithContent {
                            // Draw the original background content
                            drawContent()
                            // Draw dimming
                            // Calculate the transition fraction (0 when foreground fully covers, 1 when fully swiped away)
                            val transitionFraction =
                                (offset / maxWidthPxUpdated).coerceIn(0f, 1f)
                            // Draw a shadow rectangle on top
                            drawRect(
                                color = Color.Black,
                                alpha = 0.25f - (transitionFraction * 0.25f)
                            )
                        }
                    // Top screen should be able to have offset while dragging
                    screens.size == 2 && index == 1 -> Modifier.offset {
                        IntOffset(offset.roundToInt(), 0)
                    }

                    else -> error("Screen size must be either 1 or 2")
                }

                val draggableModifier = when {
                    screens.size == 1 -> Modifier
                    screens.size == 2 && index == 0 -> Modifier
                    screens.size == 2 && index == 1 -> Modifier.anchoredDraggable(
                        state = anchoredDraggableState,
                        orientation = Orientation.Horizontal,
                        flingBehavior = flingBehavior
                    )

                    else -> error("Screen size must be either 1 or 2")
                }

                val render by remember(screens.size, index, offset, maxWidthPxUpdated) {
                    derivedStateOf {
                        when {
                            // Always render if only 1 screen
                            screens.size == 1 -> true
                            // Render the bottom screen only if the top has no offset
                            screens.size == 2 && index == 0 -> offset != 0f
                            // Render the top screen only if its not at max offset
                            screens.size == 2 && index == 1 -> offset != maxWidthPxUpdated
                            else -> error("Screen size must be either 1 or 2")
                        }
                    }
                }

                val peeking by remember(screens.size, index) {
                    derivedStateOf {
                        when {
                            // Can't be peeking if there is only 1 screen
                            screens.size == 1 -> false
                            // Current screen is bottom screen, so peeking
                            screens.size == 2 && index == 0 -> true
                            // Current screen is top screen, so no peeking
                            screens.size == 2 && index == 1 -> false
                            else -> error("Screen size must be either 1 or 2")
                        }
                    }
                }

                val moving by remember(offset, maxWidthPxUpdated) {
                    derivedStateOf {
                        offset > 0f && offset < maxWidthPxUpdated
                    }
                }

                key(screen.key) {
                    if (render) {
                        screen.render {
                            Box(
                                Modifier.fillMaxSize().zIndex(index.toFloat()).then(offsetModifier)
                            ) {
                                Box(modifier = Modifier.fillMaxSize()) {
                                    Box(
                                        modifier = Modifier.zIndex(0f).fillMaxSize()
                                    ) {
                                        content(it)
                                    }
                                    if (peeking || moving) {
                                        val interactionSource =
                                            remember { MutableInteractionSource() }
                                        Box(
                                            modifier = Modifier
                                                .zIndex(1f)
                                                .fillMaxSize()
                                                .clickable(
                                                    interactionSource = interactionSource,
                                                    indication = null,
                                                    onClick = {}
                                                ),
                                        )
                                    }
                                }
                                Box(
                                    Modifier
                                        .let {
                                            if (draggableHandleFillMaxHeight) it.fillMaxHeight() else it
                                        }
                                        .width(draggableHandleWidth)
                                        .padding(draggableHandlePadding)
                                        .then(draggableModifier)
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

private enum class DismissValue {
    Start,
    End,
}


private fun syncScreens(source: List<Screen>, state: SnapshotStateList<Screen>) {
    if (source.size !in 1..2 || state.size !in 1..2) return

    if (source.size == 1) {
        val target = source[0]
        when (state.size) {
            1 -> {
                if (state[0].key != target.key) {
                    state[0] = target
                }
            }

            2 -> {
                if (state[0].key == target.key && state[1].key != target.key) {
                    state.removeAt(1)
                } else if (state[1].key == target.key && state[0].key != target.key) {
                    // ✅ IMPROVEMENT: Simplified this logic for clarity and robustness.
                    val preservedScreen = state[1]
                    state.clear()
                    state.add(preservedScreen)
                } else {
                    state[0] = target
                    state.removeAt(1)
                }
            }
        }
        return
    }

    val target0 = source[0]
    val target1 = source[1]

    if (state.size == 1) {
        when (state[0].key) {
            target0.key -> {
                state.add(target1)
            }

            target1.key -> {
                state.add(0, target0)
            }

            else -> {
                state[0] = target0
                state.add(target1)
            }
        }
        return
    }

    if (state[0].key != target0.key && state[1].key == target0.key) {
        state.swap(0, 1)
    }
    if (state[0].key != target0.key) {
        state[0] = target0
    }
    if (state[1].key != target1.key) {
        state[1] = target1
    }
}

private fun <T> SnapshotStateList<T>.swap(i: Int, j: Int) {
    if (i == j) return
    val temp = this[i]
    this[i] = this[j]
    this[j] = temp
}

private fun calculateBackgroundScreenOffset(
    currentOffset: Float,
    screenWidth: Float,
    maxDisplacementFraction: Float = 0.3f,
): Float {
    val maxDisplacement = screenWidth * maxDisplacementFraction
    val fraction = (currentOffset / screenWidth).coerceIn(0f, 1f)
    return -maxDisplacement + fraction * maxDisplacement
}

@Composable
private fun <T> iosFlingBehavior(
    state: AnchoredDraggableState<T>,
    positionalThreshold: (totalDistance: Float) -> Float,
    velocityThreshold: () -> Float,
    snapAnimationSpec: AnimationSpec<Float>,
    decayAnimationSpec: DecayAnimationSpec<Float>,
): TargetedFlingBehavior {
    return remember(
        state,
        positionalThreshold,
        velocityThreshold,
        snapAnimationSpec,
        decayAnimationSpec
    ) {
        anchoredIOSDraggableFlingBehavior(
            state = state,
            positionalThreshold = positionalThreshold,
            velocityThreshold = velocityThreshold,
            snapAnimationSpec = snapAnimationSpec,
            decayAnimationSpec = decayAnimationSpec
        )
    }
}

private fun <T> anchoredIOSDraggableFlingBehavior(
    state: AnchoredDraggableState<T>,
    positionalThreshold: (totalDistance: Float) -> Float,
    velocityThreshold: () -> Float,
    snapAnimationSpec: AnimationSpec<Float>,
    decayAnimationSpec: DecayAnimationSpec<Float>,
): TargetedFlingBehavior =
    snapFlingBehavior(
        decayAnimationSpec = decayAnimationSpec,
        snapAnimationSpec = snapAnimationSpec,
        snapLayoutInfoProvider =
            anchoredDraggableLayoutInfoProvider(
                state = state,
                positionalThreshold = positionalThreshold,
                velocityThreshold = velocityThreshold
            )
    )

private fun <T> anchoredDraggableLayoutInfoProvider(
    state: AnchoredDraggableState<T>,
    positionalThreshold: (totalDistance: Float) -> Float,
    velocityThreshold: () -> Float,
): SnapLayoutInfoProvider =
    object : SnapLayoutInfoProvider {

        override fun calculateApproachOffset(velocity: Float, decayOffset: Float) = 0f

        override fun calculateSnapOffset(velocity: Float): Float {
            val currentOffset = state.requireOffset()
            val target =
                state.anchors.computeTarget(
                    currentOffset = currentOffset,
                    velocity = velocity,
                    positionalThreshold = positionalThreshold,
                    velocityThreshold = velocityThreshold
                )
            return state.anchors.positionOf(target) - currentOffset
        }
    }

private fun <T> DraggableAnchors<T>.computeTarget(
    currentOffset: Float,
    velocity: Float,
    positionalThreshold: (totalDistance: Float) -> Float,
    velocityThreshold: () -> Float,
): T {
    val currentAnchors = this
    require(!currentOffset.isNaN()) { "The offset provided to computeTarget must not be NaN." }
    val isMoving = abs(velocity) > 0.0f
    val isMovingForward = isMoving && velocity > 0f
    return if (!isMoving) {
        currentAnchors.closestAnchor(currentOffset)!!
    } else if (abs(velocity) >= abs(velocityThreshold())) {
        currentAnchors.closestAnchor(currentOffset, searchUpwards = isMovingForward)!!
    } else {
        val left = currentAnchors.closestAnchor(currentOffset, false)!!
        val leftAnchorPosition = currentAnchors.positionOf(left)
        val right = currentAnchors.closestAnchor(currentOffset, true)!!
        val rightAnchorPosition = currentAnchors.positionOf(right)
        val distance = abs(leftAnchorPosition - rightAnchorPosition)
        val relativeThreshold = abs(positionalThreshold(distance))
        val closestAnchorFromStart =
            if (isMovingForward) leftAnchorPosition else rightAnchorPosition
        val relativePosition = abs(closestAnchorFromStart - currentOffset)
        when (relativePosition >= relativeThreshold) {
            true -> if (isMovingForward) right else left
            false -> if (isMovingForward) left else right
        }
    }
}