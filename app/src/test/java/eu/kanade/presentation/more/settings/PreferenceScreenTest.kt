package eu.kanade.presentation.more.settings

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.parallel.Execution
import org.junit.jupiter.api.parallel.ExecutionMode

@Execution(ExecutionMode.CONCURRENT)
class PreferenceScreenTest {

    @Test
    fun `a hidden group takes no rows`() {
        val items = listOf(
            group("Hidden", visible = false, "A", "B"),
            group("Shown", visible = true, "C"),
        )

        items.findHighlightedIndex("Shown") shouldBe 0
        items.findHighlightedIndex("C") shouldBe 1
        items.findHighlightedIndex("A") shouldBe -1
    }

    @Test
    fun `a hidden item still takes its row`() {
        val items = listOf(
            Preference.PreferenceItem.TextPreference(title = "Hidden", visible = false),
            group("Shown", visible = true, "C"),
        )

        items.findHighlightedIndex("Shown") shouldBe 1
        items.findHighlightedIndex("C") shouldBe 2
    }

    private fun group(title: String, visible: Boolean, vararg items: String) = Preference.PreferenceGroup(
        title = title,
        visible = visible,
        preferenceItems = items.map { Preference.PreferenceItem.TextPreference(title = it) },
    )
}
