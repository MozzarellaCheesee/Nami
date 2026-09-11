package dev.nami.feature.player

import android.Manifest
import android.content.pm.PackageManager
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import com.google.zxing.ResultPoint
import com.journeyapps.barcodescanner.BarcodeCallback
import com.journeyapps.barcodescanner.BarcodeResult
import com.journeyapps.barcodescanner.BarcodeView
import dev.nami.core.designsystem.NamiColors

/**
 * Полноэкранный диалог сканирования QR-кода камерой прямо внутри приложения.
 * Использует камеру устройства и декодирует приглашение в Джем на лету.
 */
@Composable
fun JamCameraScannerDialog(
    onDismiss: () -> Unit,
    onQrScanned: (String) -> Unit,
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (!granted) {
            Toast.makeText(context, "Для сканирования QR-кода необходим доступ к камере", Toast.LENGTH_SHORT).show()
            onDismiss()
        }
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(
            usePlatformDefaultWidth = false,
            dismissOnBackPress = true,
            dismissOnClickOutside = false,
        ),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
        ) {
            if (hasCameraPermission) {
                AndroidView(
                    factory = { ctx ->
                        BarcodeView(ctx).apply {
                            decodeContinuous(object : BarcodeCallback {
                                override fun barcodeResult(result: BarcodeResult) {
                                    val text = result.text ?: return
                                    pause()
                                    onQrScanned(text)
                                    onDismiss()
                                }

                                override fun possibleResultPoints(resultPoints: MutableList<ResultPoint>) = Unit
                            })
                            resume()
                        }
                    },
                    modifier = Modifier.fillMaxSize(),
                    onRelease = { it.pause() },
                )

                // Стилизованная рамка видоискателя в цветах Nami
                Canvas(modifier = Modifier.fillMaxSize()) {
                    val frameSize = size.minDimension * 0.65f
                    val left = (size.width - frameSize) / 2
                    val top = (size.height - frameSize) / 2
                    val corner = frameSize * 0.12f
                    val stroke = Stroke(width = 5.dp.toPx())
                    val color = NamiColors.Shu

                    listOf(
                        Offset(left, top) to Offset(1f, 1f),
                        Offset(left + frameSize, top) to Offset(-1f, 1f),
                        Offset(left, top + frameSize) to Offset(1f, -1f),
                        Offset(left + frameSize, top + frameSize) to Offset(-1f, -1f),
                    ).forEach { (c, dir) ->
                        drawLine(color, c, c + Offset(corner * dir.x, 0f), stroke.width, cap = StrokeCap.Round)
                        drawLine(color, c, c + Offset(0f, corner * dir.y), stroke.width, cap = StrokeCap.Round)
                    }
                }
            }

            // Верхняя панель с кнопкой закрытия
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 24.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.5f)),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = "Закрыть",
                        tint = Color.White,
                    )
                }

                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = "Сканирование QR",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                    color = Color.White,
                )

                Spacer(modifier = Modifier.weight(1f))

                Spacer(modifier = Modifier.size(44.dp))
            }

            // Нижняя подсказка
            Box(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 48.dp, start = 24.dp, end = 24.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.7f))
                    .padding(horizontal = 16.dp, vertical = 12.dp),
            ) {
                Text(
                    text = "Наведите камеру на QR-код на экране организатора",
                    style = MaterialTheme.typography.bodyMedium,
                    color = Color.White,
                )
            }
        }
    }
}
