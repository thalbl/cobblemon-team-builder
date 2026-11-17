package dev.thal.teambuilder.core

import dev.thal.teambuilder.client.TutorialSteps
import kotlin.test.Test
import kotlin.test.assertTrue

class TutorialTextsTest {
    private val tabs = listOf("SUGGEST", "TEAMS", "ANALYSIS", "RAID", "TRAINERS", "TRAINER_DETAIL", "SAVED")

    @Test
    fun everyStepHasTitleAndTextInBothLanguages() {
        val steps = TutorialSteps.main + tabs.flatMap(TutorialSteps::forTab)
        for (lang in listOf(Lang.PT, Lang.EN)) {
            assertTrue(lang.has("tutorial.news"))
            for (s in steps) {
                assertTrue(lang.has("tutorial.${s.key}.title"), "${lang.code}: titulo de ${s.key}")
                assertTrue(lang.has("tutorial.${s.key}.text"), "${lang.code}: texto de ${s.key}")
            }
        }
    }

    @Test
    fun everyTabHasATipAndAVersion() {
        for (tab in tabs) {
            assertTrue(TutorialSteps.forTab(tab).isNotEmpty(), "sem dica: $tab")
            assertTrue(TutorialSteps.version(tab) >= 1, "sem versao: $tab")
        }
    }
}
