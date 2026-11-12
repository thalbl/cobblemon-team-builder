package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class NameResolverTest {
    private val resolver = NameResolver(
        listOf(
            "rillaboom", "dragapult", "greattusk", "landorus", "landorustherian", "ninetales",
            "ninetalesalola", "mrmime", "charizard", "charmander", "ironvaliant", "rotomwash",
        )
    )

    private fun ids(text: String) = resolver.resolveAll(text).map { it.id }

    @Test
    fun ignoresCaseSpacesAndPunctuation() {
        assertEquals("greattusk", resolver.resolveOne("Great Tusk"))
        assertEquals("landorustherian", resolver.resolveOne("Landorus-Therian"))
        assertEquals("mrmime", resolver.resolveOne("Mr. Mime"))
    }

    @Test
    fun regionalFormsInAnyOrder() {
        assertEquals("ninetalesalola", resolver.resolveOne("Alolan Ninetales"))
        assertEquals("ninetalesalola", resolver.resolveOne("ninetales alola"))
    }

    @Test
    fun prefixesAndTypos() {
        assertEquals("rillaboom", resolver.resolveOne("rilla"))
        assertEquals("dragapult", resolver.resolveOne("dragapullt"))
        assertEquals("charizard", resolver.resolveOne("charizrd"))
        assertNull(resolver.resolveOne("zzzzzz"))
    }

    @Test
    fun splitsSeveralNames() {
        assertEquals(listOf("rillaboom", "greattusk", "dragapult"), ids("Rillaboom Great Tusk dragapult"))
        assertEquals(listOf("rillaboom", "greattusk"), ids("rillaboom, great tusk"))
        assertEquals(listOf("ironvaliant", "ninetalesalola"), ids("iron valiant alolan ninetales"))
        assertEquals(listOf("rillaboom", null), ids("rillaboom, naoexiste"))
    }

    @Test
    fun completions() {
        assertEquals(listOf("charizard", "charmander"), resolver.completions("Char"))
    }
}
