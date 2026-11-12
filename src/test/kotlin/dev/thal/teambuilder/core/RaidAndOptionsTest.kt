package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RaidAndOptionsTest {
    private val meta = MetaSnapshot.EMPTY

    private fun move(id: String, type: String, cat: MoveCategory, power: Int) = id to MoveInfo(id, type, cat, power, 0)

    private val moves = mapOf(
        move("surf", "water", MoveCategory.SPECIAL, 90),
        move("thunderbolt", "electric", MoveCategory.SPECIAL, 90),
        move("energyball", "grass", MoveCategory.SPECIAL, 90),
        move("flamethrower", "fire", MoveCategory.SPECIAL, 90),
        move("flareblitz", "fire", MoveCategory.PHYSICAL, 120),
        move("airslash", "flying", MoveCategory.SPECIAL, 75),
        move("tackle", "normal", MoveCategory.PHYSICAL, 40),
    )
    private val builder = ProfileBuilder(meta, moves)
    private val scorer = TeamScorer(meta)

    private fun mon(
        id: String, types: String, legal: Set<String> = emptySet(), species: String = id,
        legendary: Boolean = false, megaOf: String? = null, bst: Int = 100,
    ) = builder.build(
        Candidate(
            id = id, speciesId = species, name = id, types = types.split('/'), abilities = emptyList(),
            baseStats = StatKey.entries.associateWith { bst }, legalMoves = legal,
            legendary = legendary, megaOf = megaOf,
        )
    )

    @Test
    fun raidPrefersSuperEffectiveAndResistant() {
        val boss = RaidBossInfo("charizard", listOf("fire", "flying"), moves = listOf("flareblitz", "airslash"))
        val water = mon("water", "water", setOf("surf"))
        val electric = mon("electric", "electric", setOf("thunderbolt"))
        val grass = mon("grass", "grass", setOf("energyball"))
        val picks = RaidAdvisor(moves).rank(boss, listOf(grass, water, electric))

        // eletrico: 2x no chefe e resiste a voador; agua: 2x e resiste a fogo; planta apanha dos dois
        assertEquals("grass", picks.last().pick.id)
        assertEquals(100, picks.first().score)
        assertEquals(2.0, picks.first().effectiveness)
        assertEquals("energyball", picks.last().move)
        assertEquals(2.0, picks.last().worstTaken)
    }

    @Test
    fun teraChangesTheBestCounter() {
        val water = mon("water", "water", setOf("surf"))
        val grass = mon("grass", "grass", setOf("energyball"))
        val advisor = RaidAdvisor(moves)

        val normal = RaidBossInfo("charizard", listOf("fire", "flying"), moves = listOf("tackle"))
        assertEquals("water", advisor.rank(normal, listOf(water, grass)).first().pick.id)

        // Com tera agua, o chefe passa a resistir a agua e a ser fraco a planta.
        val tera = normal.copy(teraType = "water")
        val picks = advisor.rank(tera, listOf(water, grass))
        assertEquals("grass", picks.first().pick.id)
        assertEquals(2.0, picks.first().effectiveness)
    }

    @Test
    fun ownedPokemonUsesMovesItAlreadyHas() {
        val owned = OwnedPokemon(
            uuid = "u", source = Source.PC, id = "water", speciesId = "water", displayName = "water", level = 60,
            types = listOf("water"), ability = "", nature = "hardy",
            baseStats = StatKey.entries.associateWith { 100 }, ivs = emptyMap(), evs = emptyMap(),
            currentMoves = listOf("tackle"), accessibleMoves = setOf("tackle"), legalMoves = setOf("tackle", "surf"),
        )
        val boss = RaidBossInfo("charizard", listOf("fire", "flying"))
        val pick = RaidAdvisor(moves).rank(boss, listOf(builder.build(owned.toCandidate()))).single()
        // surf rende muito mais que tackle, entao vale sugerir aprender
        assertEquals("surf", pick.move)
        assertFalse(pick.moveReady)
    }

    @Test
    fun legendariesAndMegasFollowOptions() {
        val team = listOf(mon("a", "water"))
        val legend = mon("legend", "dragon", legendary = true, bst = 130)
        val megaX = mon("zardmegax", "fire/dragon", species = "zard", megaOf = "zard", bst = 120)
        val megaY = mon("blastmega", "water", species = "blast", megaOf = "blast", bst = 120)
        val plain = mon("plain", "grass")
        val pool = listOf(legend, megaX, megaY, plain)

        fun ids(o: BuildOptions) = TeamBuilderEngine(scorer, o).suggestNext(team, pool, 10).map { it.pick.id }

        assertFalse("legend" in ids(BuildOptions()))
        assertTrue("legend" in ids(BuildOptions(allowLegendaries = true)))
        assertTrue("zardmegax" in ids(BuildOptions()))
        assertEquals(listOf("plain"), ids(BuildOptions(allowMegas = false)))

        // No maximo uma mega por time, mesmo completando com um pool cheio delas.
        val completed = TeamBuilderEngine(scorer, BuildOptions()).complete(team, pool).first()
        assertEquals(1, completed.members.count { it.isMega })
        val withMega = team + megaX
        assertFalse("blastmega" in TeamBuilderEngine(scorer, BuildOptions()).suggestNext(withMega, pool, 10).map { it.pick.id })
    }
}
