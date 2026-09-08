package dev.nami.player

import kotlin.test.Test
import kotlin.test.assertEquals

/** П.md §20 "цепочки". Главное, что тут проверяется, - перестановка не теряет и не размножает
 * треки: очередь после цепочек обязана быть перестановкой исходной, иначе плейлист молча
 * укоротится или зациклится. */
class QueueChainsTest {

    @Test
    fun `без цепочек порядок не меняется`() {
        val ids = listOf("a", "b", "c")
        assertEquals(ids, applyChains(ids, emptyMap()))
    }

    @Test
    fun `преемник встаёт сразу за своим треком`() {
        assertEquals(
            listOf("a", "c", "b"),
            applyChains(listOf("a", "b", "c"), mapOf("a" to "c")),
        )
    }

    @Test
    fun `цепочка работает транзитивно`() {
        assertEquals(
            listOf("a", "c", "d", "b"),
            applyChains(listOf("a", "b", "c", "d"), mapOf("a" to "c", "c" to "d")),
        )
    }

    @Test
    fun `звено на трек не из этой очереди игнорируется`() {
        val ids = listOf("a", "b")
        assertEquals(ids, applyChains(ids, mapOf("a" to "постороннее")))
    }

    @Test
    fun `кольцо не зацикливает и не дублирует`() {
        val result = applyChains(listOf("a", "b", "c"), mapOf("a" to "b", "b" to "a"))
        assertEquals(listOf("a", "b", "c"), result)
    }

    @Test
    fun `результат всегда перестановка исходного списка`() {
        val ids = listOf("a", "b", "c", "d", "e")
        val chains = mapOf("a" to "d", "d" to "b", "e" to "c", "c" to "e")
        val result = applyChains(ids, chains)
        assertEquals(ids.size, result.size)
        assertEquals(ids.toSet(), result.toSet())
    }
}
