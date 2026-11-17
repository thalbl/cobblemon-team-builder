package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SpawnAndDamageTest {
    private fun entry(
        form: String, weight: Double, bucket: String = "common", bucketWeight: Double = 94.4,
        biomes: Set<String> = setOf("minecraft:plains"), type: String = "grounded",
        times: List<IntRange> = emptyList(), timeName: String = "",
    ) = SpawnEntry(form, form, type, bucket, bucketWeight, weight, biomes, times = times, timeName = timeName)

    private val a = entry("a", 10.0)
    private val b = entry("b", 30.0)
    private val c = entry("c", 5.0, bucket = "uncommon", bucketWeight = 5.0)
    private val night = entry("owl", 40.0, times = listOf(12000..23999), timeName = "night")
    private val forest = entry("f", 10.0, biomes = setOf("minecraft:forest", "minecraft:plains"))
    private val fish = entry("fish", 10.0, type = "surface")
    private val table = SpawnTable(listOf(a, b, c, night, forest, fish))

    private fun near(expected: Double, actual: Double) = assertTrue(kotlin.math.abs(expected - actual) < 1e-6, "esperado $expected, veio $actual")

    @Test
    fun bucketFirstThenWeightInsideIt() {
        // De dia: comum tem a, b e f (peso 50); incomum so c.
        val day = 1000
        near(94.4 / 99.4 * 10 / 50, table.chance(table.of("a", "a"), "minecraft:plains", "grounded", day))
        near(5.0 / 99.4, table.chance(table.of("c", "c"), "minecraft:plains", "grounded", day))
    }

    @Test
    fun nightOnlySpawnsChangeTheOdds() {
        near(0.0, table.chance(table.of("owl", "owl"), "minecraft:plains", "grounded", 1000))
        // A noite o owl entra no balde comum e divide o peso com os outros.
        near(94.4 / 99.4 * 40 / 90, table.chance(table.of("owl", "owl"), "minecraft:plains", "grounded", 18000))
        near(94.4 / 99.4 * 10 / 90, table.chance(table.of("a", "a"), "minecraft:plains", "grounded", 18000))
    }

    @Test
    fun otherBiomesAndPlacesDoNotCount() {
        near(0.0, table.chance(table.of("a", "a"), "minecraft:desert", "grounded"))
        near(0.0, table.chance(table.of("fish", "fish"), "minecraft:plains", "grounded"))
        // Na agua o peixe e o unico: 100%.
        near(1.0, table.chance(table.of("fish", "fish"), "minecraft:plains", "surface"))
        // Na floresta o f e o unico comum.
        assertEquals("minecraft:forest", table.bestBiomes(table.of("f", "f")).first().first)
    }

    @Test
    fun statsMatchTheGames() {
        // Garchomp: HP base 108 no nivel 100 com 31 IV e 0 EV = 357; Ataque 130 com 252 EV Adamant = 394.
        assertEquals(357, StatCalc.stat(StatKey.HP, 108, 100))
        assertEquals(394, StatCalc.stat(StatKey.ATK, 130, 100, 31, 252, "adamant"))
        assertEquals(1.0, Natures.multiplier("hardy", StatKey.ATK))
        assertEquals(0.9, Natures.multiplier("cobblemon:modest", StatKey.ATK))
    }

    @Test
    fun damageFollowsTheFormula() {
        val moves = mapOf(
            "tackle" to MoveInfo("tackle", "normal", MoveCategory.PHYSICAL, 100, 0),
            "ember" to MoveInfo("ember", "fire", MoveCategory.SPECIAL, 100, 0),
        )
        val calc = DamageCalc(moves)
        val stats = StatKey.entries.associateWith { 100 } + (StatKey.HP to 200)
        val att = DamageCalc.Fighter("att", listOf("water"), "", 50, stats, listOf("tackle"))
        val def = DamageCalc.Fighter("def", listOf("water"), "", 50, stats, emptyList())
        // (22 * 100 * 100/100)/50 + 2 = 46; minimo 39. HP 200: 23% e 19%.
        val r = calc.best(att, def)!!
        assertEquals(19, r.min)
        assertEquals(23, r.max)
        assertEquals(5..6, r.hits)

        // Fogo na agua: 0.5x. Fantasma imune a normal: sem golpe.
        val fire = DamageCalc.Fighter("fire", listOf("fire"), "", 50, stats, listOf("ember"))
        // 46 * 1.5 (STAB) * 0.5 = 34 -> 17% do HP.
        assertEquals(17, calc.best(fire, def)!!.max)
        val ghost = DamageCalc.Fighter("ghost", listOf("ghost"), "", 50, stats, emptyList())
        assertNull(calc.best(att, ghost))
    }
}
