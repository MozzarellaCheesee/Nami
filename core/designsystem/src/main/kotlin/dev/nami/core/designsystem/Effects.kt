package dev.nami.core.designsystem

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.unit.Dp

/**
 * П.md §26 "Прозрачность и размытие" - пока только отдельный переключатель "без размытия" для
 * слабых устройств, ползунков силы размытия и прозрачности мини-плеера нет. Blur на Android
 * рисуется через RenderEffect на GPU и на слабом железе стоит заметно дороже всего остального в
 * кадре, так что выключатель полезнее ползунка.
 *
 * Глобальный наблюдаемый флаг, а не CompositionLocal - ровно тем же приёмом, что [NamiColors] и
 * [NamiDensity]: приложение однооконное, а call-site'ов у размытия пять штук в трёх модулях.
 * ponytail: global flag, switch to a CompositionLocal if this ever needs to vary per-window.
 */
object NamiEffects {
    private var blurState by mutableStateOf(true)

    val blurEnabled: Boolean get() = blurState

    fun setBlurEnabled(enabled: Boolean) {
        blurState = enabled
    }
}

/** П.md §26 "масштаб шрифта" - четыре ступени вместо ползунка: разница в полступени на глаз не
 * читается, а список из четырёх пилюль повторяет уже существующий выбор плотности рядом. */
object NamiTypeScale {
    val SCALES = listOf(0.9f to "Мельче", 1.0f to "Обычно", 1.1f to "Крупнее", 1.2f to "Крупно")
}

/** Замена `Modifier.blur(x)` на всех call-site'ах: с выключенным размытием просто не добавляет
 * эффект. Читает флаг во время сборки модификатора, то есть в композиции - переключатель
 * применяется сразу, без перезапуска экрана. */
fun Modifier.namiBlur(radius: Dp): Modifier = if (NamiEffects.blurEnabled) this.blur(radius) else this
