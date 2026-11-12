package dev.thal.teambuilder.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class PairAdvisorTest {
    private val meta = MetaSnapshot.EMPTY

    private fun move(id: String, type: String, cat: MoveCategory, power: Int, hitsAlly: Boolean = false) =
        id to MoveInfo(id, type, cat, power, 0, spread = hitsAlly, hitsAlly = hitsAlly)

    private val moves = mapOf(
        move("earthquake", "ground", MoveCategory.PHYSICAL, 100, hitsAlly = true),
        move("discharge", "electric", MoveCategory.SPECIAL, 80, hitsAlly = true),
        move("bravebird", "flying", MoveCategory.PHYSICAL, 120),
        move("flareblitz", "fire", MoveCategory.PHYSICAL, 120),
        move("closecombat", "fighting", MoveCategory.PHYSICAL, 120),
        move("woodhammer", "grass", MoveCategory.PHYSICAL, 120),
        move("fakeout", "normal", MoveCategory.PHYSICAL, 40),
        move("swordsdance", "normal", MoveCategory.STATUS, 0),
        move("trickroom", "psychic", MoveCategory.STATUS, 0),
        move("psychic", "psychic", MoveCategory.SPECIAL, 90),
        move("surf", "water", MoveCategory.SPECIAL, 90, hitsAlly = true),
    )
    private val scorer = TeamScorer(meta)
    private val advisor = PairAdvisor(moves, scorer)
    private val builder = ProfileBuilder(meta, moves, Format.DOUBLES)

    private fun mon(id: String, types: String, ability: String = "", legal: Set<String> = emptySet(), spe: Int = 80, atk: Int = 100) =
        builder.build(
            Candidate(
                id = id, speciesId = id, name = id, types = types.split('/'), abilities = listOfNotNull(ability.ifEmpty { null }),
                baseStats = mapOf(StatKey.HP to 90, StatKey.ATK to atk, StatKey.DEF to 90, StatKey.SPA to 80, StatKey.SPD to 90, StatKey.SPE to spe),
                legalMoves = legal,
            )
        )

    @Test
    fun earthquakeLikesAFlyingPartner() {
        val quaker = mon("quaker", "ground")
        val bird = mon("bird", "flying")
        val fire = mon("firemon", "fire")
        val safe = advisor.pair(quaker, bird, listOf("earthquake"), listOf("bravebird"), Lang.PT)
        val hurt = advisor.pair(quaker, fire, listOf("earthquake"), listOf("flareblitz"), Lang.PT)
        assertTrue(safe.score > hurt.score, "${safe.score} vs ${hurt.score}")
        assertTrue(safe.good.any { "<earthquake>" in it && "bird" in it }, safe.good.toString())
        assertTrue(hurt.bad.any { "<earthquake>" in it && "firemon" in it }, hurt.bad.toString())
    }

    @Test
    fun levitateAndTelepathyAlsoDodgeSpread() {
        val quaker = mon("quaker", "ground")
        val floaty = mon("floaty", "psychic", "levitate")
        val p = advisor.pair(quaker, floaty, listOf("earthquake"), listOf("psychic"), Lang.EN)
        assertTrue(p.bad.none { "<earthquake>" in it }, p.bad.toString())
        assertTrue(p.good.any { "<earthquake>" in it }, p.good.toString())
    }

    @Test
    fun sharedWeaknessIsPenalizedAndCoverRewarded() {
        val grass = mon("grassy", "grass")
        val bug = mon("buggy", "bug")
        val fire = mon("firemon", "fire")
        val same = advisor.pair(grass, bug, listOf("woodhammer"), emptyList(), Lang.PT)
        val mixed = advisor.pair(grass, fire, listOf("woodhammer"), listOf("flareblitz"), Lang.PT)
        assertTrue(same.bad.any { "{fire}" in it }, same.bad.toString())
        assertTrue(mixed.score > same.score)
    }

    @Test
    fun fakeOutCoversASetupSweeper() {
        val support = mon("supporter", "normal", legal = setOf("fakeout"))
        val sweeper = mon("sweeper", "fighting", atk = 130)
        val plain = mon("plain", "fighting", atk = 80, spe = 60)
        val withSetup = advisor.pair(support, sweeper, listOf("fakeout"), listOf("swordsdance", "closecombat"), Lang.PT)
        val without = advisor.pair(support, plain, listOf("fakeout"), listOf("closecombat"), Lang.PT)
        assertTrue(withSetup.good.any { "Fake Out" in it }, withSetup.good.toString())
        assertTrue(withSetup.score > without.score)
    }

    @Test
    fun trickRoomWantsASlowPartner() {
        val setter = mon("roomer", "psychic", legal = setOf("trickroom"))
        val slow = mon("slowbro", "fighting", spe = 30, atk = 130)
        val fast = mon("speedy", "fighting", spe = 120, atk = 130)
        val good = advisor.pair(setter, slow, listOf("trickroom", "psychic"), listOf("closecombat"), Lang.PT)
        val bad = advisor.pair(setter, fast, listOf("trickroom", "psychic"), listOf("closecombat"), Lang.PT)
        assertTrue(good.score > bad.score)
        assertTrue(bad.bad.any { "speedy" in it }, bad.bad.toString())
    }

    @Test
    fun planSplitsSixIntoThreeBestPairs() {
        val quaker = mon("quaker", "ground")
        val bird = mon("bird", "flying")
        val waterBoy = mon("waterboy", "water")
        val absorber = mon("absorber", "water", "waterabsorb")
        val support = mon("supporter", "normal", legal = setOf("fakeout"))
        val sweeper = mon("sweeper", "fighting", atk = 130)
        val team = listOf(quaker, waterBoy, support, bird, absorber, sweeper)
        val movesOf = listOf(
            listOf("earthquake"), listOf("surf"), listOf("fakeout"),
            listOf("bravebird"), listOf("surf"), listOf("swordsdance", "closecombat"),
        )
        val plan = assertNotNull(advisor.plan(team, movesOf, Lang.PT))
        assertEquals(3, plan.pairs.size)
        val sets = plan.pairs.map { setOf(it.a, it.b) }.toSet()
        assertTrue(setOf(0, 3) in sets, "Earthquake + Voador: $sets")
        assertTrue(setOf(2, 5) in sets, "Fake Out + setup: $sets")
        assertEquals(6, plan.pairs.flatMap { listOf(it.a, it.b) }.toSet().size)
        assertEquals(6, plan.matrix.size)
        assertEquals(plan.matrix[0][3], plan.matrix[3][0])
    }

    @Test
    fun oddTeamLeavesOneOut() {
        val team = listOf(mon("a", "fire"), mon("b", "water"), mon("c", "grass"))
        val plan = assertNotNull(advisor.plan(team, team.map { emptyList() }, Lang.EN))
        assertEquals(1, plan.pairs.size)
    }
}
