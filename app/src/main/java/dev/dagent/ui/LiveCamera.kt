package dev.dagent.ui

import android.content.Context
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume

/** Holds the live camera's still-capture use case so voice mode can grab a frame per question. */
class LiveCameraState {
    internal var capture: ImageCapture? = null

    /** Takes a photo of what the camera sees now; null if the camera isn't ready. */
    suspend fun snap(dir: File): File? {
        val ic = capture ?: return null
        val context = contextRef ?: return null
        dir.mkdirs()
        val file = File(dir, "live_${System.currentTimeMillis()}.jpg")
        return suspendCancellableCoroutine { cont ->
            ic.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(context),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        if (cont.isActive) cont.resume(file.takeIf { it.isFile && it.length() > 0 })
                    }

                    override fun onError(exception: ImageCaptureException) {
                        if (cont.isActive) cont.resume(null)
                    }
                }
            )
        }
    }

    internal var contextRef: Context? = null
}

/** Live camera preview (CameraX). [front] picks the selfie camera. Needs CAMERA permission. */
@Composable
fun LiveCamera(state: LiveCameraState, front: Boolean, modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(front, owner) {
        state.contextRef = context.applicationContext
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        future.addListener({
            val p = runCatching { future.get() }.getOrNull() ?: return@addListener
            provider = p
            val preview = Preview.Builder().build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
            val capture = ImageCapture.Builder()
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .build()
            val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
            runCatching {
                p.unbindAll()
                p.bindToLifecycle(owner, selector, preview, capture)
                state.capture = capture
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            state.capture = null
            runCatching { provider?.unbindAll() }
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
