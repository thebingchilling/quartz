package app.quartz.core.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class QuartzCardLayersTest {
    // A group with two tunnels, the second a Clash tunnel with one open proxy group, then a loose
    // tunnel.
    private val stacks =
        listOf(
            listOf("f:1"),
            listOf("f:1"),
            listOf("f:1", "c:7"),
            listOf("f:1", "c:7", "g:7/PROXY"),
            listOf("f:1", "c:7", "g:7/PROXY"),
            emptyList(),
        )

    @Test
    fun cardsStartAndEndWithTheirItems() {
        assertEquals(listOf(QuartzCardLayer(0, starts = true, ends = false)), quartzCardLayers(stacks, 0))
        assertEquals(
            listOf(
                QuartzCardLayer(0, starts = false, ends = false),
                QuartzCardLayer(1, starts = true, ends = false),
            ),
            quartzCardLayers(stacks, 2),
        )
        // The last node closes all three cards at once.
        assertEquals(
            listOf(
                QuartzCardLayer(0, starts = false, ends = true),
                QuartzCardLayer(1, starts = false, ends = true),
                QuartzCardLayer(2, starts = false, ends = true),
            ),
            quartzCardLayers(stacks, 4),
        )
        assertEquals(emptyList<QuartzCardLayer>(), quartzCardLayers(stacks, 5))
    }

    @Test
    fun neighbouringGroupsAreSeparateCards() {
        val two = listOf(listOf("f:1"), listOf("f:2"))
        assertEquals(listOf(QuartzCardLayer(0, starts = true, ends = true)), quartzCardLayers(two, 0))
        assertEquals(listOf(QuartzCardLayer(0, starts = true, ends = true)), quartzCardLayers(two, 1))
    }
}
