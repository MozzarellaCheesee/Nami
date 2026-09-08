package dev.nami.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import org.junit.Rule
import org.junit.Test

/**
 * Генератор baseline-профиля: `./gradlew :baselineprofile:generateBaselineProfile` на подключённом
 * устройстве или эмуляторе (root/userdebug образ, API 28+). Результат кладётся в
 * `app/src/main/baseline-prof.txt`, оттуда его подхватывает profileinstaller.
 *
 * Сценарий намеренно минимальный - "холодный старт до отрисованной библиотеки" (План.md Часть X,
 * "холодный старт"): именно этот путь пользователь видит каждый запуск, и именно его JIT греет
 * дольше всего. Глубже по экранам не идём: любой клик по конкретному элементу списка ломается
 * от состава библиотеки на устройстве, а профиль от этого выигрывает мало.
 */
class BaselineProfileGenerator {

    @get:Rule
    val baselineProfileRule = BaselineProfileRule()

    @Test
    fun generate() = baselineProfileRule.collect(packageName = "dev.nami.app") {
        pressHome()
        startActivityAndWait()
        // Первый кадр списка уже отрисован к моменту возврата startActivityAndWait, но
        // библиотека грузится через Paging асинхронно - даём ей доехать, иначе в профиль не
        // попадут классы Room/Paging, которые как раз и тормозят холодный старт.
        device.waitForIdle()
    }
}
