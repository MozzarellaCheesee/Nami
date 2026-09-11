package dev.nami.feature.library

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.compose.ui.viewinterop.AndroidView
import com.google.zxing.BarcodeFormat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory
import dev.nami.core.designsystem.NamiColors

/** Группа G "сеть" - своя обёртка вокруг zxing-android-embedded's BarcodeView (не готовая
 * CaptureActivity библиотеки, у неё чужой вид) - камера и декодирование из библиотеки, рамка и
 * весь остальной интерфейс свои, в цветах приложения. */
@Composable
fun LocalShareScanScreen(onBack: () -> Unit, onResult: (String) -> Unit) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        hasCameraPermission = granted
    }
    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        if (hasCameraPermission) {
            AndroidView(
                factory = { ctx ->
                    BarcodeView(ctx).apply {
                        decoderFactory = DefaultDecoderFactory(listOf(BarcodeFormat.QR_CODE))
                        cameraSettings.isAutoFocusEnabled = true
                        decodeContinuous(object : BarcodeCallback {
                            override fun barcodeResult(result: BarcodeResult) {
                                val text = result.text ?: return
                                pause()
                                onResult(text)
                                onBack()
                            }
                            override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
                        })
                        resume()
                    }
                },
                modifier = Modifier.fillMaxSize(),
                onRelease = { it.pause() },
            )
            // Свой прицел вместо стандартного зелёного лазера библиотеки - просто уголки рамки в
            // фирменном цвете, camera preview снизу уже сам по себе достаточно "живой".
            Canvas(modifier = Modifier.fillMaxSize()) {
                val frameSize = size.minDimension * 0.62f
                val left = (size.width - frameSize) / 2
                val top = (size.height - frameSize) / 2
                val corner = frameSize * 0.12f
                val stroke = Stroke(width = 6.dp.toPx())
                val color = NamiColors.Shu
                // Four L-shaped corner brackets - drawn as pairs of short lines, not a full
                // rounded-rect outline, so the eye reads "viewfinder", not "just a rectangle".
                listOf(
                    Offset(left, top) to Offset(1f, 1f),
                    Offset(left + frameSize, top) to Offset(-1f, 1f),
                    Offset(left, top + frameSize) to Offset(1f, -1f),
                    Offset(left + frameSize, top + frameSize) to Offset(-1f, -1f),
                ).forEach { (corner_, dir) ->
                    drawLine(color, corner_, corner_ + Offset(corner * dir.x, 0f), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                    drawLine(color, corner_, corner_ + Offset(0f, corner * dir.y), stroke.width, cap = androidx.compose.ui.graphics.StrokeCap.Round)
                }
            }
        } else {
            Text(
                "Нужен доступ к камере, чтобы сканировать QR",
                color = NamiColors.Paper70,
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.align(Alignment.Center).padding(32.dp),
            )
        }

        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
            IconButton(onClick = onBack) {
                Icon(Icons.Outlined.ArrowBack, contentDescription = "Назад", tint = NamiColors.Paper100)
            }
            Text("Сканировать QR", color = NamiColors.Paper100, style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 8.dp))
        }

        Text(
            "Наведи камеру на QR-код на другом устройстве",
            color = NamiColors.Paper70,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.align(Alignment.BottomCenter).padding(32.dp),
        )
    }
}
