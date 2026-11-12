package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SetAdvisorTest {
    private val meta = MetaLoader.loadBundled()

    private fun move(id: String, type: String, cat: MoveCategory, power: Int, priority: Int = 0) =
        id to MoveInfo(id, type, cat, power, priority)

    private val moves = mapOf(
        move("grassyglide", "grass", MoveCategory.PHYSICAL, 55, 1),
        move("woodhammer", "grass", MoveCategory.PHYSICAL, 120),
        move("knockoff", "dark", MoveCategory.PHYSICAL, 65),
        move("uturn", "bug", MoveCategory.PHYSICAL, 70),
        move("swordsdance", "normal", MoveCategory.STATUS, 0),
        move("highhorsepower", "ground", MoveCategory.PHYSICAL, 95),
        move("flamethrower", "fire", MoveCategory.SPECIAL, 90),
        move("fireblast", "fire", MoveCategory.SPECIAL, 110),
        move("icebeam", "ice", MoveCategory.SPECIAL, 90),
        move("hyperbeam", "normal", MoveCategory.SPECIAL, 150),
        move("stealthrock", "rock", MoveCategory.STATUS, 0),
        move("tackle", "normal", MoveCategory.PHYSICAL, 40),
    )
    private val builder = ProfileBuilder(meta, moves)
    private val advisor = SetAdvisor(moves, TeamScorer(meta))

    private val stats = mapOf(
        StatKey.HP to 100, StatKey.ATK to 125, StatKey.DEF to 90,
        StatKey.SPA to 60, StatKey.SPD to 70, StatKey.SPE to 85,
    )

    @Test
    fun metaSetOnlyUsesLegalMovesAndReportsStatus() {
        val legal = setOf("grassyglide", "woodhammer", "knockoff", "uturn", "swordsdance", "tackle")
        val owned = OwnedPokemon(
            uuid = "u", source = Source.PC, id = "rillaboom", speciesId = "rillaboom", displayName = "Rillaboom",
            level = 50, types = listOf("grass"), ability = "overgrow", nature = "hardy",
            baseStats = stats, ivs = emptyMap(), evs = emptyMap(),
            currentMoves = listOf("grassyglide", "tackle"), accessibleMoves = setOf("grassyglide", "tackle", "uturn"),
            legalMoves = legal, learnMethods = mapOf("knockoff" to "TM", "woodhammer" to "nivel 50"),
        )
        val p = builder.build(owned.toCandidate())
        val set = advisor.advise(p, listOf(p), Lang.PT)

        assertTrue(set.fromMeta)
        assertEquals(4, set.moves.size)
        assertTrue(set.moves.all { it.id in legal }, set.moves.toString())
        // highhorsepower e usado no Smogon, mas este exemplar nao aprende
        assertFalse(set.moves.any { it.id == "highhorsepower" })
        val byId = set.moves.associateBy { it.id }
        assertEquals(MoveStatus.KNOWN, byId.getValue("grassyglide").status)
        assertEquals(MoveStatus.SWAP, byId.getValue("uturn").status)
        assertEquals(MoveStatus.LEARN, byId.getValue("knockoff").status)
        assertEquals("TM", byId.getValue("knockoff").method)
        assertTrue(set.notes.any { "natureza atual" in it })
        assertTrue(set.items.isNotEmpty())
        assertEquals(6, set.evs.size)
    }

    @Test
    fun heuristicSetForUnknownSpecies() {
        val c = Candidate(
            id = "fakemon", speciesId = "fakemon", name = "Fakemon", types = listOf("fire"),
            abilities = listOf("blaze"),
            baseStats = stats + mapOf(StatKey.ATK to 60, StatKey.SPA to 125),
            legalMoves = setOf("flamethrower", "fireblast", "icebeam", "hyperbeam", "stealthrock", "tackle"),
        )
        val p = builder.build(c)
        val mate = builder.build(c.copy(id = "mate", speciesId = "mate", types = listOf("water"), legalMoves = emptySet()))
        val set = advisor.advise(p, listOf(p, mate))

        assertFalse(set.fromMeta)
        val ids = set.moves.map { it.id }
        assertEquals("fireblast", ids.first(), "o STAB mais forte vem primeiro")
        assertTrue("icebeam" in ids)
        assertTrue("stealthrock" in ids, "o time nao tem hazards")
        assertFalse("hyperbeam" in ids)
        assertFalse("tackle" in ids)
        assertEquals("modest", set.nature)
    }
}
