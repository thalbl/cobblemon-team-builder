package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class EngineTest {
    private val meta = MetaLoader.loadBundled()
    private val builder = ProfileBuilder(meta, emptyMap())
    private val engine = TeamBuilderEngine(TeamScorer(meta))

    private fun mon(id: String, types: String, ability: String = "", bst: Int = 540, spe: Int = 80) = builder.build(
        Candidate(
            id = id, speciesId = id, name = id, types = types.split('/'), abilities = listOfNotNull(ability.ifEmpty { null }),
            baseStats = mapOf(
                StatKey.HP to (bst - spe) / 5, StatKey.ATK to (bst - spe) / 5, StatKey.DEF to (bst - spe) / 5,
                StatKey.SPA to (bst - spe) / 5, StatKey.SPD to (bst - spe) / 5, StatKey.SPE to spe,
            ),
            legalMoves = emptySet(),
        )
    )

    @Test
    fun typeChartBasics() {
        val flyingSteel = TypeChart.defensiveProfile(listOf("flying", "steel"), null)
        assertEquals(0.0, flyingSteel[TypeChart.indexOf("ground")])
        assertEquals(0.25, flyingSteel[TypeChart.indexOf("grass")])
        assertEquals(2.0, flyingSteel[TypeChart.indexOf("fire")])
        val levitator = TypeChart.defensiveProfile(listOf("electric"), "levitate")
        assertEquals(0.0, levitator[TypeChart.indexOf("ground")])
        // todo tipo e acertado com vantagem por pelo menos um outro
        val everything = TypeChart.superEffectiveMask(TypeChart.TYPES)
        assertEquals(TypeChart.COUNT, Integer.bitCount(everything))
    }

    @Test
    fun suggestsPartnerThatCoversWeakness() {
        // Time fraco a fogo; entre dois candidatos de mesma forca, o que resiste a fogo deve vencer.
        val team = listOf(mon("fakegrass", "grass"), mon("fakesteel", "steel"))
        val water = mon("fakewater", "water")
        val bug = mon("fakebug", "bug")
        val picks = engine.suggestNext(team, listOf(bug, water))
        assertEquals("fakewater", picks.first().pick.id)
        assertTrue(picks.first().reasons.any { "fire" in it }, picks.first().reasons.toString())
    }

    @Test
    fun completesDistinctTeamsRespectingSpeciesClause() {
        val names = listOf(
            "rillaboom" to "grass", "dragapult" to "dragon/ghost", "greattusk" to "ground/fighting",
            "gholdengo" to "steel/ghost", "heatran" to "fire/steel", "toxapex" to "poison/water",
            "landorustherian" to "ground/flying", "kingambit" to "dark/steel", "zapdos" to "electric/flying",
            "clefable" to "fairy", "garchomp" to "dragon/ground", "ferrothorn" to "grass/steel",
            "rotomwash" to "electric/water", "corviknight" to "flying/steel", "slowking" to "water/psychic",
        )
        val pool = names.map { (id, types) -> mon(id, types, bst = 580) }
        val start = pool.filter { it.id == "rillaboom" || it.id == "dragapult" }
        val teams = engine.complete(start, pool, count = 3)

        assertEquals(3, teams.size)
        for (t in teams) {
            assertEquals(6, t.members.size)
            assertEquals(6, t.members.map { it.speciesId }.toSet().size)
            assertEquals(start.map { it.id }, t.members.take(2).map { it.id })
        }
        assertEquals(3, teams.map { t -> t.members.map { it.id }.toSet() }.toSet().size)
        // ordenados da melhor para a pior nota
        assertTrue(teams[0].breakdown.total >= teams[1].breakdown.total)
    }

    @Test
    fun smallPoolDoesNotBreakCompletion() {
        val pool = listOf(mon("a", "water"), mon("b", "fire"), mon("c", "grass"))
        val teams = engine.complete(listOf(pool[0]), pool)
        assertEquals(3, teams.first().members.size)
    }

    @Test
    fun swapFixesStackedWeakness() {
        val team = listOf(
            mon("g1", "grass"), mon("g2", "grass/steel"), mon("g3", "bug"),
            mon("w1", "water"), mon("e1", "electric"), mon("n1", "normal"),
        )
        val swaps = engine.suggestSwaps(team, listOf(mon("fakefire", "fire"), mon("fakeice", "ice")))
        assertEquals("fakefire", swaps.first().pick.id)
        assertTrue(engine.weaknesses(team).contains("fire"))
    }
}
