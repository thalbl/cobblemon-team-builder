package dev.thal.teambuilder.core

import com.google.gson.JsonParser
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AchievementsTest {
    private fun resource(path: String) = AchievementsTest::class.java.getResourceAsStream(path)

    @Test
    fun everyAchievementHasAnAdvancementWithTheSameParent() {
        for (a in AchievementList.all) {
            val stream = assertNotNull(resource("/data/cobblemon_team_builder/advancement/${a.id}.json"), "falta o json de ${a.id}")
            val json = stream.reader().use { JsonParser.parseReader(it).asJsonObject }
            val parent = json["parent"]?.asString
            assertEquals(a.parent?.let { "cobblemon_team_builder:$it" }, parent, "pai de ${a.id}")
        }
    }

    @Test
    fun parentsComeBeforeChildren() {
        val seen = HashSet<String>()
        for (a in AchievementList.all) {
            a.parent?.let { assertTrue(it in seen, "${a.id} aparece antes de $it") }
            seen += a.id
        }
    }

    @Test
    fun textsExistInBothLanguages() {
        for (lang in listOf(Lang.PT, Lang.EN)) {
            for (a in AchievementList.all) {
                assertTrue(lang.has(AchievementList.titleKey(a.id)), "${lang.code}: titulo de ${a.id}")
                assertTrue(lang.has(AchievementList.descKey(a.id)), "${lang.code}: descricao de ${a.id}")
            }
        }
    }

    @Test
    fun clientEventsAreRealAchievements() {
        assertTrue(AchievementList.CLIENT_EVENTS.all { it in AchievementList.ids })
    }
}
