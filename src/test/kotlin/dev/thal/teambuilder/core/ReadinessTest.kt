package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReadinessTest {
    private fun move(id: String, type: String, cat: MoveCategory, power: Int) = id to MoveInfo(id, type, cat, power, 0)

    private val moves = mapOf(
        move("surf", "water", MoveCategory.SPECIAL, 90),
        move("energyball", "grass", MoveCategory.SPECIAL, 90),
        move("flamethrower", "fire", MoveCategory.SPECIAL, 90),
    )
    private val builder = ProfileBuilder(MetaSnapshot.EMPTY, moves)
    private val advisor = BattleAdvisor(RaidAdvisor(moves))

    private fun owned(id: String, type: String, level: Int, move: String) = builder.build(
        OwnedPokemon(
            uuid = "$id-$level", source = Source.PC, id = id, speciesId = id, displayName = id, level = level,
            types = listOf(type), ability = "", nature = "hardy",
            baseStats = StatKey.entries.associateWith { 100 }, ivs = emptyMap(), evs = emptyMap(),
            currentMoves = listOf(move), accessibleMoves = setOf(move), legalMoves = setOf(move),
        ).toCandidate()
    )

    private val fireMon = builder.build(
        Candidate(
            id = "fire", speciesId = "fire", name = "fire", types = listOf("fire"), abilities = emptyList(),
            baseStats = StatKey.entries.associateWith { 100 }, legalMoves = setOf("flamethrower"),
        )
    )
    private fun enemy(level: Int) = EnemyMon(fireMon, level, listOf("flamethrower"), "")

    @Test
    fun superEffectiveResistantAndLeveledIsFullyReady() {
        val r = advisor.readiness(listOf(enemy(50)), listOf(owned("water", "water", 50, "surf")))!!
        assertEquals(100, r.percent)
        assertEquals(50, r.teamLevel)
        assertEquals(50, r.enemyLevel)
        assertTrue(r.unanswered.isEmpty())
    }

    @Test
    fun underLevelCutsReadiness() {
        val r = advisor.readiness(listOf(enemy(50)), listOf(owned("water", "water", 25, "surf")))!!
        // Metade do nivel: (25/50)^2 = 25%.
        assertEquals(25, r.percent)
        assertEquals(listOf(0), r.unanswered)
    }

    @Test
    fun wrongTypeLeavesTheEnemyUnanswered() {
        val r = advisor.readiness(listOf(enemy(50)), listOf(owned("grass", "grass", 50, "energyball")))!!
        assertTrue(r.percent < 35)
        assertEquals(listOf(0), r.unanswered)
    }

    @Test
    fun noTeamMeansNoReadiness() {
        assertNull(advisor.readiness(listOf(enemy(50)), emptyList()))
    }

    @Test
    fun countersPreferPokemonAtTheTrainersLevel() {
        val low = owned("waterlow", "water", 20, "surf")
        val high = owned("waterhigh", "water", 50, "surf")
        val picks = advisor.rank(listOf(enemy(50)), listOf(low, high))
        assertEquals("waterhigh", picks.first().pick.id)
        // Abaixo do nivel o valor cai mais rapido que os stats: 20/50 de nivel vale bem menos que 20%.
        assertTrue(picks.last().score < 10, "nota do nivel 20: ${picks.last().score}")
    }
}
