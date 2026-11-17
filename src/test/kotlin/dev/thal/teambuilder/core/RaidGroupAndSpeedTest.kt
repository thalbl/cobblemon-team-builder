package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class RaidGroupAndSpeedTest {
    private fun move(id: String, type: String, cat: MoveCategory, power: Int) = id to MoveInfo(id, type, cat, power, 0)

    private val moves = mapOf(
        move("surf", "water", MoveCategory.SPECIAL, 90),
        move("flareblitz", "fire", MoveCategory.PHYSICAL, 120),
        move("reflect", "psychic", MoveCategory.STATUS, 0),
        move("lightscreen", "psychic", MoveCategory.STATUS, 0),
        move("tackle", "normal", MoveCategory.PHYSICAL, 40),
    )
    private val builder = ProfileBuilder(MetaSnapshot.EMPTY, moves)
    private val raids = RaidAdvisor(moves)
    private val group = RaidGroupAdvisor(moves, raids)
    private val boss = RaidBossInfo("charizard", listOf("fire"), moves = listOf("flareblitz"))

    private fun owned(id: String, type: String, vararg known: String) = builder.build(
        OwnedPokemon(
            uuid = id, source = Source.PC, id = id, speciesId = id, displayName = id, level = 50,
            types = listOf(type), ability = "", nature = "hardy",
            baseStats = StatKey.entries.associateWith { 100 }, ivs = emptyMap(), evs = emptyMap(),
            currentMoves = known.toList(), accessibleMoves = known.toSet(), legalMoves = known.toSet(),
        ).toCandidate()
    )

    @Test
    fun speedMatchesTheGames() {
        // Garchomp (102) nivel 100, 31 IV, 252 EV, Jolly: 333; com Choice Scarf: 499.
        assertEquals(333, Speed.stat(102, 100, 31, 252, "jolly"))
        assertEquals(499, Speed.stat(102, 100, 31, 252, "cobblemon:jolly", "choicescarf"))
        assertEquals(272, Speed.stat(102, 100, 31, 252, "brave"))
        assertEquals(303, Speed.stat(102, 100, 31, 252, "hardy"))
    }

    @Test
    fun aloneEveryoneAttacks() {
        val me = RaidMember("me", "Me", listOf(owned("water", "water", "surf"), owned("wall", "normal", "reflect", "tackle")))
        val roles = group.assign(boss, listOf(me))
        assertEquals(RaidRole.ATTACK, roles.single().role)
        assertEquals("water", roles.single().pick?.pick?.id)
    }

    @Test
    fun theOneWithScreensSupportsTheOthersAttack() {
        val a = RaidMember("a", "A", listOf(owned("watera", "water", "surf")))
        val b = RaidMember("b", "B", listOf(owned("waterb", "water", "surf"), owned("wall", "normal", "reflect", "tackle")))
        val roles = group.assign(boss, listOf(a, b)).associateBy { it.member.id }
        assertEquals(RaidRole.ATTACK, roles.getValue("a").role)
        assertEquals(RaidRole.SUPPORT, roles.getValue("b").role)
        assertEquals("wall", roles.getValue("b").pick?.pick?.id)
        // O chefe so tem golpe fisico: Reflect serve, Light Screen nao.
        assertEquals(listOf("reflect"), roles.getValue("b").supportMoves)
    }

    @Test
    fun specialScreenIsUselessAgainstAPhysicalBoss() {
        val a = RaidMember("a", "A", listOf(owned("watera", "water", "surf")))
        val b = RaidMember("b", "B", listOf(owned("waterb", "water", "surf"), owned("screen", "normal", "lightscreen", "tackle")))
        val roles = group.assign(boss, listOf(a, b))
        // Ninguem tem suporte util: os dois atacam.
        assertTrue(roles.all { it.role == RaidRole.ATTACK })
        assertNull(roles.firstOrNull { it.role == RaidRole.SUPPORT })
    }
}
