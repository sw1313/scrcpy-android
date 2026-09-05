package com.scrcpyandroid.ui

import android.app.Activity
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.MotionEvent
import android.view.Surface
import android.view.TextureView
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.filled.CropSquare
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.automirrored.filled.VolumeDown
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SmallFloatingActionButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.scrcpyandroid.R
import com.scrcpyandroid.protocol.ScrcpyConstants
import com.scrcpyandroid.session.MirrorSessionService
import com.scrcpyandroid.session.SessionState
import com.scrcpyandroid.video.AspectFit
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.roundToInt

@Composable
fun MirrorScreen(
    sessionState: SessionState,
    service: MirrorSessionService?,
    onStop: () -> Unit,
    onBackToConnect: () -> Unit,
) {
    val streaming = sessionState as? SessionState.Streaming
    val videoW = streaming?.videoWidth?.takeIf { it > 0 } ?: service?.videoWidth ?: 0
    val videoH = streaming?.videoHeight?.takeIf { it > 0 } ?: service?.videoHeight ?: 0
    val view = LocalView.current

    DisposableEffect(Unit) {
        val window = (view.context as? Activity)?.window
        val controller = window?.let { WindowCompat.getInsetsController(it, view) }
        if (window != null && controller != null) {
            WindowCompat.setDecorFitsSystemWindows(window, false)
            controller.hide(WindowInsetsCompat.Type.systemBars())
            controller.systemBarsBehavior =
                WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        onDispose {
            if (window != null && controller != null) {
                controller.show(WindowInsetsCompat.Type.systemBars())
                WindowCompat.setDecorFitsSystemWindows(window, true)
            }
        }
    }

    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        if (service != null &&
            (sessionState is SessionState.Streaming ||
                sessionState is SessionState.Connecting ||
                sessionState is SessionState.Reconnecting)
        ) {
            MirrorTexture(
                service = service,
                videoWidth = videoW,
                videoHeight = videoH,
                touchEnabled = sessionState is SessionState.Streaming,
                modifier = Modifier.fillMaxSize(),
            )
        }

        when (sessionState) {
            is SessionState.Connecting, is SessionState.Reconnecting -> {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    CircularProgressIndicator(color = Color.White)
                    Text(
                        text = when (sessionState) {
                            is SessionState.Connecting -> stringResource(R.string.action_connecting)
                            is SessionState.Reconnecting -> sessionState.message
                            else -> ""
                        },
                        color = Color.White,
                        modifier = Modifier.padding(top = 12.dp),
                    )
                }
            }
            is SessionState.Error -> {
                Column(
                    modifier = Modifier
                        .align(Alignment.Center)
                        .padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(sessionState.message, color = Color.White)
                    TextButton(onClick = onBackToConnect) {
                        Text(stringResource(R.string.action_back))
                    }
                }
            }
            else -> Unit
        }

        // Only draws small hit-targets — does NOT cover the full screen, so TextureView
        // continues to receive multi-touch MotionEvents.
        NavBubble(
            maxWidthPx = constraints.maxWidth.toFloat(),
            maxHeightPx = constraints.maxHeight.toFloat(),
            onBack = { service?.pressBack() },
            onHome = { service?.pressHome() },
            onRecents = { service?.pressRecents() },
            onVolumeDown = { service?.pressVolumeDown() },
            onVolumeUp = { service?.pressVolumeUp() },
            onDisconnect = onStop,
        )
    }
}

/**
 * Scrcpy server expects plain ACTION_DOWN / ACTION_UP / ACTION_MOVE / ACTION_CANCEL.
 * It rewrites DOWN/UP to POINTER_* when multiple pointers are active.
 * Sending POINTER_* or leaving CANCEL without UP leaves stuck remote fingers.
 */
private class ScrcpyTouchDispatcher(
    private val service: MirrorSessionService,
) {
    private val lastPoints = ConcurrentHashMap<Long, Pair<Int, Int>>()

    @Volatile var videoWidth: Int = 0
    @Volatile var videoHeight: Int = 0

    fun onTouch(viewWidth: Int, viewHeight: Int, event: MotionEvent): Boolean {
        val vw = videoWidth
        val vh = videoHeight
        if (vw <= 0 || vh <= 0 || viewWidth <= 0 || viewHeight <= 0) return true

        val cw = viewWidth.toFloat()
        val ch = viewHeight.toFloat()

        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                val index = event.actionIndex
                dispatchDown(event, index, cw, ch, vw, vh)
            }
            MotionEvent.ACTION_MOVE -> {
                for (i in 0 until event.pointerCount) {
                    dispatchMove(event, i, cw, ch, vw, vh)
                }
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                dispatchUp(event, index, cw, ch, vw, vh)
            }
            MotionEvent.ACTION_CANCEL -> {
                // Always release with ACTION_UP so scrcpy PointersState cleans up.
                val ids = lastPoints.keys.toList()
                for (id in ids) {
                    val mapped = lastPoints.remove(id) ?: continue
                    service.injectTouch(
                        ScrcpyConstants.ACTION_UP,
                        id,
                        mapped.first,
                        mapped.second,
                        0f,
                    )
                }
            }
        }
        return true
    }

    fun releaseAll() {
        val ids = lastPoints.keys.toList()
        for (id in ids) {
            val mapped = lastPoints.remove(id) ?: continue
            service.injectTouch(ScrcpyConstants.ACTION_UP, id, mapped.first, mapped.second, 0f)
        }
    }

    private fun dispatchDown(
        event: MotionEvent,
        index: Int,
        cw: Float,
        ch: Float,
        vw: Int,
        vh: Int,
    ) {
        val id = event.getPointerId(index).toLong()
        val mapped = AspectFit.mapTouchToVideoClamped(
            event.getX(index), event.getY(index), cw, ch, vw, vh,
        )
        lastPoints[id] = mapped
        val pressure = event.getPressure(index).let { if (it <= 0f) 1f else it.coerceIn(0f, 1f) }
        // Always ACTION_DOWN — server upgrades to POINTER_DOWN when needed.
        service.injectTouch(ScrcpyConstants.ACTION_DOWN, id, mapped.first, mapped.second, pressure)
    }

    private fun dispatchMove(
        event: MotionEvent,
        index: Int,
        cw: Float,
        ch: Float,
        vw: Int,
        vh: Int,
    ) {
        val id = event.getPointerId(index).toLong()
        if (!lastPoints.containsKey(id)) return
        val mapped = AspectFit.mapTouchToVideoClamped(
            event.getX(index), event.getY(index), cw, ch, vw, vh,
        )
        lastPoints[id] = mapped
        val pressure = event.getPressure(index).let { if (it <= 0f) 1f else it.coerceIn(0f, 1f) }
        service.injectTouch(ScrcpyConstants.ACTION_MOVE, id, mapped.first, mapped.second, pressure)
    }

    private fun dispatchUp(
        event: MotionEvent,
        index: Int,
        cw: Float,
        ch: Float,
        vw: Int,
        vh: Int,
    ) {
        val id = event.getPointerId(index).toLong()
        val mapped = lastPoints.remove(id)
            ?: AspectFit.mapTouchToVideoClamped(
                event.getX(index), event.getY(index), cw, ch, vw, vh,
            )
        // Always ACTION_UP — server upgrades to POINTER_UP when needed.
        service.injectTouch(ScrcpyConstants.ACTION_UP, id, mapped.first, mapped.second, 0f)
    }
}

@Composable
private fun MirrorTexture(
    service: MirrorSessionService,
    videoWidth: Int,
    videoHeight: Int,
    touchEnabled: Boolean,
    modifier: Modifier = Modifier,
) {
    val surfaceHolder = remember { mutableStateOf<Surface?>(null) }
    val dispatcher = remember(service) { ScrcpyTouchDispatcher(service) }
    val touchEnabledState = rememberUpdatedState(touchEnabled)

    DisposableEffect(service) {
        onDispose {
            dispatcher.releaseAll()
            surfaceHolder.value?.release()
            surfaceHolder.value = null
            service.setSurface(null)
        }
    }

    AndroidView(
        modifier = modifier,
        factory = { context ->
            FrameLayout(context).apply {
                layoutParams = ViewGroup.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                val texture = TextureView(context).apply {
                    layoutParams = FrameLayout.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT,
                    )
                    isOpaque = false
                    surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                        override fun onSurfaceTextureAvailable(
                            surfaceTexture: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            surfaceHolder.value?.release()
                            val surface = Surface(surfaceTexture)
                            surfaceHolder.value = surface
                            service.setSurface(surface)
                            applyAspectTransform(this@apply, dispatcher.videoWidth, dispatcher.videoHeight)
                        }

                        override fun onSurfaceTextureSizeChanged(
                            surface: SurfaceTexture,
                            width: Int,
                            height: Int,
                        ) {
                            applyAspectTransform(this@apply, dispatcher.videoWidth, dispatcher.videoHeight)
                        }

                        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                            service.setSurface(null)
                            surfaceHolder.value?.release()
                            surfaceHolder.value = null
                            return true
                        }

                        override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit
                    }
                    setOnTouchListener { v, event ->
                        if (!touchEnabledState.value) return@setOnTouchListener false
                        dispatcher.onTouch(v.width, v.height, event)
                    }
                }
                addView(texture)
                tag = texture
            }
        },
        update = { frame ->
            dispatcher.videoWidth = videoWidth
            dispatcher.videoHeight = videoHeight
            val texture = frame.tag as? TextureView ?: return@AndroidView
            applyAspectTransform(texture, videoWidth, videoHeight)
            texture.setOnTouchListener { v, event ->
                if (!touchEnabledState.value) return@setOnTouchListener false
                dispatcher.onTouch(v.width, v.height, event)
            }
        },
    )
}

private fun applyAspectTransform(view: TextureView, videoWidth: Int, videoHeight: Int) {
    if (videoWidth <= 0 || videoHeight <= 0 || view.width == 0 || view.height == 0) {
        view.setTransform(Matrix())
        return
    }
    val rect = AspectFit.compute(
        view.width.toFloat(),
        view.height.toFloat(),
        videoWidth.toFloat(),
        videoHeight.toFloat(),
    )
    val matrix = Matrix()
    matrix.setScale(rect.width / view.width, rect.height / view.height)
    matrix.postTranslate(rect.left, rect.top)
    view.setTransform(matrix)
}

@Composable
fun NavBubble(
    maxWidthPx: Float,
    maxHeightPx: Float,
    onBack: () -> Unit,
    onHome: () -> Unit,
    onRecents: () -> Unit,
    onVolumeDown: () -> Unit,
    onVolumeUp: () -> Unit,
    onDisconnect: () -> Unit,
) {
    val density = LocalDensity.current
    val maxX = maxWidthPx
    val maxY = maxHeightPx
    val bubbleSizePx = with(density) { 56.dp.toPx() }
    val edgePad = with(density) { 12.dp.toPx() }
    val gapPx = with(density) { 8.dp.toPx() }
    // 6 buttons: back/home/recents/vol-/vol+/disconnect
    val menuWidthPx = with(density) { (40.dp * 6 + 6.dp * 5 + 12.dp).toPx() }
    val menuHeightPx = with(density) { 52.dp.toPx() }

    var offset by remember(maxX, maxY) {
        mutableStateOf(
            Offset(
                (maxX - bubbleSizePx - edgePad).coerceAtLeast(edgePad),
                (maxY * 0.55f).coerceIn(edgePad, (maxY - bubbleSizePx - edgePad).coerceAtLeast(edgePad)),
            ),
        )
    }
    var expanded by remember { mutableStateOf(false) }

    val onRight = offset.x + bubbleSizePx / 2f >= maxX / 2f
    val spaceBelow = maxY - (offset.y + bubbleSizePx)
    val expandDown = spaceBelow >= menuHeightPx + gapPx + edgePad

    fun snapToEdge(current: Offset): Offset {
        val x = if (current.x + bubbleSizePx / 2f < maxX / 2f) edgePad else maxX - bubbleSizePx - edgePad
        val y = current.y.coerceIn(edgePad, (maxY - bubbleSizePx - edgePad).coerceAtLeast(edgePad))
        return Offset(x, y)
    }

    val menuX = if (onRight) {
        (offset.x + bubbleSizePx - menuWidthPx).coerceIn(edgePad, maxX - menuWidthPx - edgePad)
    } else {
        offset.x.coerceIn(edgePad, maxX - menuWidthPx - edgePad)
    }
    val menuY = if (expandDown) {
        offset.y + bubbleSizePx + gapPx
    } else {
        offset.y - menuHeightPx - gapPx
    }.coerceIn(edgePad, (maxY - menuHeightPx - edgePad).coerceAtLeast(edgePad))

    if (expanded) {
        Row(
            modifier = Modifier
                .offset { IntOffset(menuX.roundToInt(), menuY.roundToInt()) }
                .background(Color.Black.copy(alpha = 0.45f), CircleShape)
                .padding(horizontal = 6.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            SmallFloatingActionButton(
                onClick = onBack,
                containerColor = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.size(40.dp),
            ) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = stringResource(R.string.nav_back)) }
            SmallFloatingActionButton(
                onClick = onHome,
                containerColor = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 6.dp).size(40.dp),
            ) { Icon(Icons.Default.Home, contentDescription = stringResource(R.string.nav_home)) }
            SmallFloatingActionButton(
                onClick = onRecents,
                containerColor = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 6.dp).size(40.dp),
            ) { Icon(Icons.Default.CropSquare, contentDescription = stringResource(R.string.nav_recents)) }
            SmallFloatingActionButton(
                onClick = onVolumeDown,
                containerColor = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 6.dp).size(40.dp),
            ) { Icon(Icons.AutoMirrored.Filled.VolumeDown, contentDescription = stringResource(R.string.nav_vol_down)) }
            SmallFloatingActionButton(
                onClick = onVolumeUp,
                containerColor = Color.White.copy(alpha = 0.85f),
                modifier = Modifier.padding(start = 6.dp).size(40.dp),
            ) { Icon(Icons.AutoMirrored.Filled.VolumeUp, contentDescription = stringResource(R.string.nav_vol_up)) }
            SmallFloatingActionButton(
                onClick = {
                    expanded = false
                    onDisconnect()
                },
                containerColor = Color(0xFFFFCDD2).copy(alpha = 0.9f),
                modifier = Modifier.padding(start = 6.dp).size(40.dp),
            ) {
                Icon(
                    Icons.Default.Circle,
                    contentDescription = stringResource(R.string.nav_disconnect),
                    tint = Color.Red,
                )
            }
        }
    }

    Box(
        modifier = Modifier
            .offset { IntOffset(offset.x.roundToInt(), offset.y.roundToInt()) }
            .size(56.dp)
            .pointerInput(maxX, maxY) {
                detectDragGestures(
                    onDragEnd = {
                        offset = snapToEdge(offset)
                        expanded = false
                    },
                    onDrag = { change, dragAmount ->
                        change.consume()
                        expanded = false
                        offset = Offset(
                            (offset.x + dragAmount.x).coerceIn(edgePad, maxX - bubbleSizePx - edgePad),
                            (offset.y + dragAmount.y).coerceIn(edgePad, maxY - bubbleSizePx - edgePad),
                        )
                    },
                )
            },
    ) {
        FloatingActionButton(
            onClick = { expanded = !expanded },
            containerColor = Color(0xFF1B6B4A).copy(alpha = 0.72f),
            contentColor = Color.White,
            modifier = Modifier.fillMaxSize(),
        ) {
            Icon(
                if (expanded) Icons.Default.Menu else Icons.Default.Circle,
                contentDescription = stringResource(R.string.nav_bubble),
            )
        }
    }
}
