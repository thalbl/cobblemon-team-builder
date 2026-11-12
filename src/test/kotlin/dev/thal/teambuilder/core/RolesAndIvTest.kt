package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RolesAndIvTest {
    private val meta = MetaSnapshot.EMPTY

    private fun move(id: String, type: String, cat: MoveCategory, power: Int, priority: Int = 0, spread: Boolean = false) =
        id to MoveInfo(id, type, cat, power, priority, spread)

    private val moves = mapOf(
        move("flamethrower", "fire", MoveCategory.SPECIAL, 90),
        move("fireblast", "fire", MoveCategory.SPECIAL, 110),
        move("icebeam", "ice", MoveCategory.SPECIAL, 90),
        move("thunderbolt", "electric", MoveCategory.SPECIAL, 90),
        move("heatwave", "fire", MoveCategory.SPECIAL, 95, spread = true),
        move("stealthrock", "rock", MoveCategory.STATUS, 0),
        move("willowisp", "fire", MoveCategory.STATUS, 0),
        move("recover", "normal", MoveCategory.STATUS, 0),
        move("nastyplot", "dark", MoveCategory.STATUS, 0),
        move("protect", "normal", MoveCategory.STATUS, 0),
        move("fakeout", "normal", MoveCategory.PHYSICAL, 40, priority = 3),
        move("tailwind", "flying", MoveCategory.STATUS, 0),
    )

    private val stats = mapOf(
        StatKey.HP to 100, StatKey.ATK to 60, StatKey.DEF to 100,
        StatKey.SPA to 110, StatKey.SPD to 100, StatKey.SPE to 80,
    )
    private val legal = setOf("flamethrower", "fireblast", "icebeam", "thunderbolt", "stealthrock", "willowisp", "recover", "nastyplot")

    private fun candidate(id: String, owned: OwnedPokemon? = null, legalMoves: Set<String> = legal) = Candidate(
        id = id, speciesId = id, name = id, types = listOf("fire"), abilities = listOf("flashfire"),
        baseStats = stats, legalMoves = legalMoves, owned = owned,
    )

    private val builder = ProfileBuilder(meta, moves)
    private val advisor = SetAdvisor(moves, TeamScorer(meta))

    @Test
    fun roleChangesTheSet() {
        val p = builder.build(candidate("fakemon"))
        val roles = advisor.roles(p)
        assertTrue(SetRole.SPECIAL in roles && SetRole.SUPPORT in roles && SetRole.TANK in roles && SetRole.SETUP in roles, roles.toString())

        val special = advisor.adviseWith(p, emptyList(), SetRole.SPECIAL).moves.map { it.id }
        val support = advisor.adviseWith(p, emptyList(), SetRole.SUPPORT).moves.map { it.id }
        val setup = advisor.adviseWith(p, emptyList(), SetRole.SETUP).moves.map { it.id }
        assertTrue(special.count { moves.getValue(it).category != MoveCategory.STATUS } >= 3, special.toString())
        assertTrue("stealthrock" in support && "willowisp" in support, support.toString())
        assertTrue("nastyplot" in setup, setup.toString())

        val tank = advisor.adviseWith(p, emptyList(), SetRole.TANK)
        assertTrue("recover" in tank.moves.map { it.id }, tank.moves.toString())
        assertEquals(252, tank.evs[0], "tanque investe em HP")
    }

    @Test
    fun teammateHazardsRemoveTheNeed() {
        val p = builder.build(candidate("fakemon"))
        val alone = advisor.adviseWith(p, emptyList(), SetRole.SPECIAL).moves.map { it.id }
        val setter = builder.build(candidate("setter"))
        val withSetter = advisor.adviseWith(p, listOf(MateSet(setter, SetRole.SUPPORT, listOf("stealthrock"))), SetRole.SPECIAL)
            .moves.map { it.id }
        assertTrue("stealthrock" in alone, alone.toString())
        assertFalse("stealthrock" in withSetter, withSetter.toString())
    }

    @Test
    fun ivPriorityFollowsTheRole() {
        val ivs = mapOf(
            StatKey.HP to 31, StatKey.ATK to 5, StatKey.DEF to 31,
            StatKey.SPA to 10, StatKey.SPD to 31, StatKey.SPE to 20,
        )
        val owned = OwnedPokemon(
            uuid = "u", source = Source.PC, id = "fakemon", speciesId = "fakemon", displayName = "Fakemon",
            level = 50, types = listOf("fire"), ability = "flashfire", nature = "timid",
            baseStats = stats, ivs = ivs, evs = emptyMap(),
            currentMoves = emptyList(), accessibleMoves = emptySet(), legalMoves = legal,
        )
        val p = builder.build(owned.toCandidate())
        val set = advisor.adviseWith(p, emptyList(), SetRole.SPECIAL)
        // SpA (252 EVs, IV 10) antes de Spe (252 EVs, IV 20); Atk nao importa para um atacante especial.
        assertEquals(listOf(3, 5), set.ivPriority.take(2))
        assertTrue(1 in set.ivUnused)
        assertFalse(1 in set.ivPriority)
    }

    @Test
    fun recommendationReactsToTheTeam() {
        val p = builder.build(candidate("fakemon"))
        val mate = builder.build(candidate("mate"))
        val alone = advisor.recommend(p, emptyList())
        // Com outro atacante especial e ninguem pondo hazards, suporte passa a valer mais.
        val withAttacker = advisor.recommend(p, listOf(MateSet(mate, SetRole.SPECIAL, listOf("fireblast"))))
        assertEquals(SetRole.SPECIAL, alone)
        assertEquals(SetRole.SUPPORT, withAttacker)
    }

    @Test
    fun doublesValuesDoublesTools() {
        val legalDoubles = setOf("fakeout", "protect", "tailwind", "heatwave", "flamethrower", "icebeam")
        val c = candidate("dbl", legalMoves = legalDoubles)
        val singles = ProfileBuilder(meta, moves, Format.SINGLES).build(c)
        val doubles = ProfileBuilder(meta, moves, Format.DOUBLES).build(c)
        assertTrue(Role.FAKE_OUT in doubles.roles && Role.SPEED_CONTROL in doubles.roles && Role.SPREAD in doubles.roles)
        assertTrue(TeamScorer(meta, Weights.DOUBLES).score(listOf(doubles)).roles > TeamScorer(meta, Weights.SINGLES).score(listOf(singles)).roles)

        val set = SetAdvisor(moves, TeamScorer(meta, Weights.DOUBLES), Format.DOUBLES).adviseWith(doubles, emptyList(), SetRole.SPECIAL)
        val ids = set.moves.map { it.id }
        assertTrue("protect" in ids, ids.toString())
        assertTrue("heatwave" in ids, ids.toString())
    }

    @Test
    fun doublesSnapshotLoads() {
        val doubles = MetaLoader.loadBundled(MetaLoader.DOUBLES_PATH)
        assertTrue(doubles.size > 300, "snapshot de duplas: ${doubles.size}")
        val incineroar = doubles["incineroar"]
        assertTrue(incineroar != null && "fakeout" in incineroar.moves, "Incineroar de duplas usa Fake Out")
    }

    @Test
    fun translations() {
        assertEquals("cobre {dragon}", Lang.PT.t("note.covers", Lang.types(listOf("dragon"))))
        assertEquals("covers {dragon}", Lang.EN.t("note.covers", Lang.types(listOf("dragon"))))
        assertEquals(Lang.PT, Lang.of("pt_pt"))
        assertEquals(Lang.EN, Lang.of("de_de"))
        assertEquals("tanque", SetRole.TANK.label(Lang.PT))
        assertEquals("tank", SetRole.TANK.label(Lang.EN))
        // Toda chave do portugues existe em ingles e vice-versa.
        val keys = listOf("pt_br", "en_us").map { code ->
            val text = Lang::class.java.getResourceAsStream("/assets/cobblemon_team_builder/lang/$code.json")!!.reader().readText()
            com.google.gson.JsonParser.parseString(text).asJsonObject.keySet()
        }
        assertEquals(keys[0], keys[1], (keys[0] - keys[1]).toString() + (keys[1] - keys[0]).toString())
    }
}
