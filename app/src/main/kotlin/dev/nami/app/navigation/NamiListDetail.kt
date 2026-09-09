package dev.nami.app.navigation

import android.app.Activity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import dev.nami.core.designsystem.NamiColors

/** Ниже этой ширины левая колонка перестаёт быть списком и превращается в полоску - в такой
 * ситуации сгиб игнорируем и делим экран по проценту. */
private val MIN_LIST_PANE_WIDTH = 220.dp

/**
 * П.md §29-31 "list-detail". На широком экране (Medium/Expanded - планшет, телефон в ландшафте,
 * раскрытый складной) список и деталь живут рядом одновременно, а не сменяют друг друга. На
 * узком экране (Compact) ничего не меняется: рисуется только список, деталь открывается обычным
 * переходом на отдельный экран.
 *
 * Граница между колонками - физический сгиб, если устройство развёрнуто и сгиб виден
 * ([FoldingFeature] с isSeparating и вертикальной ориентацией); иначе доля ширины. Сама полоса
 * шарнира остаётся пустой: контент через неё не идёт.
 */
@Composable
internal fun NamiListDetail(
    twoPane: Boolean,
    list: @Composable () -> Unit,
    detail: @Composable () -> Unit,
) {
    if (!twoPane) {
        list()
        return
    }
    val hinge = rememberSeparatingVerticalFold()
    val density = LocalDensity.current
    // Ряд стоит не у левого края окна (слева NavigationRail плюс вырезы), а координаты сгиба
    // приходят в координатах окна - поэтому нужен реальный сдвиг ряда, а не ноль.
    var rowLeftPx by remember { mutableIntStateOf(0) }
    Row(
        modifier = Modifier
            .fillMaxSize()
            .onGloballyPositioned { rowLeftPx = it.positionInWindow().x.toInt() },
    ) {
        val listWidth: Dp? = hinge?.let {
            with(density) { (it.bounds.left - rowLeftPx).coerceAtLeast(0).toDp() }
        }?.takeIf { it >= MIN_LIST_PANE_WIDTH }
        Box(modifier = if (listWidth != null) Modifier.width(listWidth) else Modifier.fillMaxWidth(0.4f)) {
            list()
        }
        if (listWidth != null) {
            Spacer(modifier = Modifier.width(with(density) { hinge.bounds.width().toDp() }))
        }
        Box(modifier = Modifier.fillMaxSize()) { detail() }
    }
}

/** Заглушка правой колонки, пока в списке ничего не выбрано. */
@Composable
internal fun NamiEmptyDetailPane(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = text, color = NamiColors.Paper40)
    }
}

/**
 * Вертикальный сгиб, физически разделяющий окно надвое. null во всех остальных случаях: обычный
 * телефон/планшет, сложенное устройство, книжный сгиб (горизонтальный), окно на одной половине
 * экрана. Проверить целиком можно только на реальном складном устройстве или в эмуляторе с
 * профилем foldable - здесь оно деградирует до деления по проценту ширины, что и есть поведение
 * на любом нескладном экране.
 */
@Composable
private fun rememberSeparatingVerticalFold(): FoldingFeature? {
    val activity = LocalContext.current as? Activity ?: return null
    val layoutInfo by remember(activity) {
        WindowInfoTracker.getOrCreate(activity).windowLayoutInfo(activity)
    }.collectAsState(initial = null)
    return layoutInfo?.displayFeatures
        ?.filterIsInstance<FoldingFeature>()
        ?.firstOrNull { it.isSeparating && it.orientation == FoldingFeature.Orientation.VERTICAL }
}
