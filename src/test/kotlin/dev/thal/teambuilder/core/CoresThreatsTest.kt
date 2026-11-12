package dev.thal.teambuilder.core

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class CoresThreatsTest {
    private val meta = MetaSnapshot.EMPTY

    private fun move(id: String, type: String, cat: MoveCategory, power: Int) = id to MoveInfo(id, type, cat, power, 0)

    private val moves = mapOf(
        move("surf", "water", MoveCategory.SPECIAL, 90),
        move("thunder", "electric", MoveCategory.SPECIAL, 110),
        move("flamethrower", "fire", MoveCategory.SPECIAL, 90),
        move("earthquake", "ground", MoveCategory.PHYSICAL, 100),
        move("energyball", "grass", MoveCategory.SPECIAL, 90),
        move("closecombat", "fighting", MoveCategory.PHYSICAL, 120),
        move("trickroom", "psychic", MoveCategory.STATUS, 0),
        move("psychic", "psychic", MoveCategory.SPECIAL, 90),
    )
    private val builder = ProfileBuilder(meta, moves)

    private fun mon(
        id: String, types: String, ability: String = "", legal: Set<String> = emptySet(),
        spe: Int = 80, atk: Int = 100, spa: Int = 100,
    ) = builder.build(
        Candidate(
            id = id, speciesId = id, name = id, types = types.split('/'), abilities = listOfNotNull(ability.ifEmpty { null }),
            baseStats = mapOf(StatKey.HP to 90, StatKey.ATK to atk, StatKey.DEF to 90, StatKey.SPA to spa, StatKey.SPD to 90, StatKey.SPE to spe),
            legalMoves = legal,
        )
    )

    @Test
    fun rainCoreRewardsSwiftSwim() {
        val setter = mon("drizzler", "water", "drizzle")
        val swimmer = mon("swimmer", "water", "swiftswim")
        val plain = mon("plain", "normal")
        val active = Cores.active(listOf(setter, swimmer))
        assertEquals(listOf(Core.RAIN), active.map { it.core })
        assertTrue(swimmer in active.single().abusers)
        assertTrue(Cores.active(listOf(swimmer, plain)).isEmpty(), "sem quem ative, nao ha nucleo")

        val scorer = TeamScorer(meta)
        assertTrue(scorer.score(listOf(setter, swimmer)).cores > scorer.score(listOf(setter, plain)).cores)
        val reasons = TeamBuilderEngine(scorer, lang = Lang.PT).suggestNext(listOf(setter), listOf(swimmer), 1).single().reasons
        assertTrue(reasons.any { "aproveita Chuva" in it }, reasons.toString())
    }

    @Test
    fun trickRoomMakesSetsSlow() {
        val setter = mon("roomer", "psychic", legal = setOf("trickroom", "psychic"))
        val slow = mon("slowpoke", "fighting", legal = setOf("closecombat", "earthquake"), spe = 30, atk = 130, spa = 50)
        val advisor = SetAdvisor(moves, TeamScorer(meta))
        val set = advisor.adviseWith(slow, listOf(MateSet(setter, SetRole.SUPPORT, listOf("trickroom"))), SetRole.PHYSICAL)
        assertEquals("brave", set.nature)
        assertEquals(0, set.evs[5])
        val normal = advisor.adviseWith(slow, emptyList(), SetRole.PHYSICAL)
        assertFalse(normal.nature == "brave")
    }

    @Test
    fun threatsFindWhatTheTeamCannotHandle() {
        // Time todo fraco a terra e sem ninguem que bata em agua/terra com vantagem.
        val team = listOf(mon("a", "fire"), mon("b", "electric"), mon("c", "rock"))
        val metaMons = listOf(
            mon("quaker", "ground", legal = setOf("earthquake")),
            mon("leafy", "grass", legal = setOf("energyball")),
        ).map { p -> Profile(p.candidate, MetaEntry(p.name, "gen9ou", 1, 0.2, emptyMap(), emptyMap(), emptyMap(), emptyList(), emptyMap(), emptyMap()), p.ability, 0.8, p.defense, p.coverageMask, p.roles, listOf(p.candidate.legalMoves.first())) }
        val threats = ThreatAnalyzer(moves).threats(team, metaMons)
        assertEquals("quaker", threats.first().threat.id)
        assertTrue("ground" in threats.first().hittingTypes)
        assertEquals(3, threats.first().weak.size)
    }

    @Test
    fun countersForATrainerTeam() {
        val enemies = listOf(
            EnemyMon(mon("geodude", "rock/ground"), 20, listOf("earthquake"), ""),
            EnemyMon(mon("onix", "rock/ground"), 22, listOf("earthquake"), ""),
        )
        val water = mon("water", "water", legal = setOf("surf"))
        val fire = mon("fire", "fire", legal = setOf("flamethrower"))
        val advisor = BattleAdvisor(RaidAdvisor(moves))
        val picks = advisor.rank(enemies, listOf(fire, water))
        assertEquals("water", picks.first().pick.id)
        assertEquals(listOf(0, 1), picks.first().handles)
        val answers = advisor.answers(enemies, listOf(fire, water))
        assertEquals("water", answers[0]?.first?.id)
    }

    @Test
    fun snapshotEntryFromChaosData() {
        val chaos = JsonParser.parseString(
            """
            {"Raw count": 500, "usage": 0.123456,
             "Abilities": {"Drizzle": 100},
             "Moves": {"Origin Pulse": 90, "Ice Beam": 80, "": 5, "Nothing": 3},
             "Items": {"Choice Scarf": 60},
             "Spreads": {"Timid:4/0/0/252/0/252": 50, "Modest:0/0/0/252/4/252": 1},
             "Teammates": {"Rayquaza": 40},
             "Checks and Counters": {"Zacian": {"n": 100, "p": 0.8, "d": 0.02}, "Pidgey": {"n": 5, "p": 1.0, "d": 0.0}}}
            """.trimIndent()
        ).asJsonObject
        val e = assertNotNull(SnapshotBuilder.buildEntry("Kyogre", chaos, "gen9ubers", 0))
        assertEquals(0.9, e.getAsJsonObject("moves")["originpulse"].asDouble)
        assertFalse(e.getAsJsonObject("moves").has("nothing"))
        assertEquals(1, e.getAsJsonArray("spreads").size(), "spread raro demais fica de fora")
        assertEquals("timid", e.getAsJsonArray("spreads")[0].asJsonObject["nature"].asString)
        assertTrue(e.getAsJsonObject("counters").has("zacian"))
        assertFalse(e.getAsJsonObject("counters").has("pidgey"))

        // O resultado precisa ser lido pelo MetaLoader.
        val root = """{"month":"x","cutoff":1500,"formatUsage":{"gen9ubers":{"kyogre":0.12}},"species":{"kyogre":$e}}"""
        val snap = MetaLoader.parse(root.reader())
        assertEquals("choicescarf", snap["kyogre"]!!.items.keys.first())
    }
}
