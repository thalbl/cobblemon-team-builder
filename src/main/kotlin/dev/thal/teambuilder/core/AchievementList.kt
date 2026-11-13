package dev.thal.teambuilder.core

/**
 * Conquistas do mod. Cada uma vira um progresso do Minecraft
 * (data/cobblemon_team_builder/advancement/<id>.json), entao aparece na tela de Progressos (L)
 * com notificacao e fica salva no mundo. A lista aqui e a mesma ordem da tela do mod.
 *
 * Os textos ficam nas chaves "advancement.cobblemon_team_builder.<id>.title" e ".desc".
 */
data class Achievement(val id: String, val parent: String?, val group: String)

object AchievementList {
    const val GROUP_BUILD = "build"
    const val GROUP_PREP = "prep"
    const val GROUP_BATTLE = "battle"

    val all: List<Achievement> = listOf(
        Achievement("root", null, GROUP_BUILD),
        Achievement("tutorial", "root", GROUP_BUILD),
        Achievement("first_pick", "root", GROUP_BUILD),
        Achievement("role", "first_pick", GROUP_BUILD),
        Achievement("mega", "first_pick", GROUP_BUILD),
        Achievement("full_team", "first_pick", GROUP_BUILD),
        Achievement("best_team", "full_team", GROUP_BUILD),
        Achievement("doubles", "full_team", GROUP_BUILD),
        Achievement("saved", "full_team", GROUP_BUILD),
        Achievement("apply", "full_team", GROUP_BUILD),

        Achievement("analysis", "root", GROUP_PREP),
        Achievement("checklist", "root", GROUP_PREP),
        Achievement("ready_one", "checklist", GROUP_PREP),
        Achievement("ready_team", "ready_one", GROUP_PREP),

        Achievement("raid_tab", "root", GROUP_BATTLE),
        Achievement("raid_win", "raid_tab", GROUP_BATTLE),
        Achievement("trainer_tab", "root", GROUP_BATTLE),
        Achievement("trainer_win", "trainer_tab", GROUP_BATTLE),
        Achievement("gym_win", "trainer_win", GROUP_BATTLE),
        Achievement("elite_win", "gym_win", GROUP_BATTLE),
    )

    val ids: Set<String> = all.mapTo(LinkedHashSet()) { it.id }

    /** Conquistas que so a tela sabe que aconteceram; o cliente avisa o servidor. */
    val CLIENT_EVENTS: Set<String> = setOf("tutorial", "first_pick", "best_team", "saved", "analysis", "checklist")

    fun titleKey(id: String) = "advancement.cobblemon_team_builder.$id.title"
    fun descKey(id: String) = "advancement.cobblemon_team_builder.$id.desc"
}
