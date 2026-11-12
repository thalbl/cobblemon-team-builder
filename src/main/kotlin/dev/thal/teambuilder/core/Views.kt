package dev.thal.teambuilder.core

/**
 * O que trafega entre servidor e cliente (como JSON). O cliente so desenha: toda a
 * logica fica no servidor, que e quem conhece o PC do jogador.
 *
 * Textos que vem do servidor ja chegam no idioma do jogador; tipos dentro deles vem como
 * "{fire}" e a tela troca por icones.
 */

/** Pedido do cliente: o time desejado e, opcionalmente, um texto com nomes a adicionar. */
data class BuilderRequest(
    /** Chaves dos slots: "uuid:<uuid>" para um Pokemon do jogador, ou o id da especie. */
    val keys: List<String> = emptyList(),
    val add: String? = null,
    /** Ignora [keys] e comeca do party atual. */
    val useParty: Boolean = false,
    /** Mudam a opcao do jogador quando presentes; null mantem como esta. */
    val legendaries: Boolean? = null,
    val megas: Boolean? = null,
    val doubles: Boolean? = null,
    /** null = modo normal; "" = detectar a raid proxima; texto = chefe manual ("charizard fire"). */
    val raid: String? = null,
    /** Funcao escolhida para cada slot (id de [SetRole]), na ordem de [keys]; null = a recomendada. */
    val roles: List<String?> = emptyList(),
    /** Coloca no party os Pokemon do jogador que estao no time. */
    val apply: Boolean = false,
    /** Id do treinador do RCT para calcular counters; null = modo normal. */
    val trainer: String? = null,
)

/** Uma forma alternativa do Pokemon de um slot (normal, Mega X, Mega Y...). */
data class FormView(val key: String, val label: String, val mega: Boolean)

data class SlotView(
    val key: String,
    val name: String,
    val types: List<String>,
    /** Caracteristicas (ja traduzidas) que a nota do time considera. */
    val roles: List<String>,
    val owned: Boolean,
    val level: Int,
    /** Para desenhar o modelo no cliente. */
    val species: String = "",
    val aspects: List<String> = emptyList(),
    /** Linha curta de contexto: nivel/IVs/natureza do exemplar, ou tier e uso da especie. */
    val info: String = "",
    val mega: Boolean = false,
    /** Formas entre as quais o slot pode alternar; vazio quando so existe uma. */
    val forms: List<FormView> = emptyList(),
    /** Esta no party agora (so para Pokemon do jogador). */
    val inParty: Boolean = false,
    /** Habilidade considerada na defesa (Levitate, Flash Fire...), para a tabela de tipos no hover. */
    val ability: String = "",
)

data class MoveView(
    val id: String,
    val status: String,
    /** Como aprende, ja traduzido ("nivel 36", "TM"...). */
    val method: String,
    val note: String,
    val type: String = "",
    /** PHYSICAL, SPECIAL ou STATUS; vazio se o golpe nao esta no registro. */
    val category: String = "",
    val power: Int = 0,
)

/** Uma funcao que o jogador pode escolher para o slot, com um resumo do set que ela daria. */
data class RoleView(
    val id: String,
    val label: String,
    val recommended: Boolean,
    val nature: String,
    val evs: List<Int>,
    val moves: List<String>,
    val item: String,
)

/** Set sugerido para o Pokemon de um slot, dado o resto do time. */
data class SetView(
    val moves: List<MoveView> = emptyList(),
    val items: List<String> = emptyList(),
    val ability: String = "",
    val nature: String = "",
    val evs: List<Int> = emptyList(),
    val notes: List<String> = emptyList(),
    val fromMeta: Boolean = false,
    /** Funcao em uso (id) e todas as possiveis. */
    val role: String = "",
    val roleLabel: String = "",
    val roleOptions: List<RoleView> = emptyList(),
    /** IVs atuais do exemplar (HP..Spe); vazio se o jogador nao tem o Pokemon. */
    val ivs: List<Int> = emptyList(),
    /** Atributos (0..5) cujo IV vale subir, do mais para o menos urgente. */
    val ivPriority: List<Int> = emptyList(),
    /** Atributos (0..5) que este set nao usa. */
    val ivUnused: List<Int> = emptyList(),
    /** O que falta para o exemplar do jogador ficar igual ao set. */
    val checklist: List<ChecklistItemView> = emptyList(),
)

/**
 * Um passo para montar o set. [kind]: move, nature, ability, item, mega, ev, iv, obtain.
 * [id] e o golpe/natureza/habilidade/item (ou o indice do atributo); [state]: done, have
 * (o item necessario esta no inventario), missing ou info.
 */
data class ChecklistItemView(val kind: String, val id: String, val state: String, val detail: String = "")

/** Nucleo ativo (clima, terreno, Trick Room) com quem ativa e quem aproveita. */
data class CoreView(val id: String, val label: String, val setters: List<String>, val abusers: List<String>)

data class ThreatView(
    val slot: SlotView,
    /** 0..100, relativo a maior ameaca. */
    val score: Int,
    val weak: List<String>,
    val walls: List<String>,
    val answers: List<String>,
    /** Tipos de ataque dele que acertam alguem em cheio, como "{fire}{ground}". */
    val hitting: String,
)

/** Treinador do RCT numa lista (busca da aba Treinadores). */
data class TrainerSummary(val id: String, val name: String, val type: String, val level: Int, val members: List<SlotView>)

data class TrainerEnemyView(
    val slot: SlotView,
    val level: Int,
    val moves: List<String>,
    val ability: String,
    val item: String,
    val nature: String,
    /** Quem do time atual melhor o enfrenta (vazio se o time esta vazio). */
    val answer: String = "",
)

data class CounterView(val slot: SlotView, val score: Int, val handles: List<String>)

data class TrainerView(
    val id: String,
    val name: String,
    val type: String,
    val enemies: List<TrainerEnemyView>,
    val pcPicks: List<CounterView>,
    val allPicks: List<CounterView>,
)

data class PickView(val slot: SlotView, val gain: Double, val reasons: List<String>)

data class TeamView(val keys: List<String>, val added: List<SlotView>, val total: Double)

/** Quantos membros do time sao fracos / resistentes a um tipo de ataque. */
data class TypeBalanceView(
    val type: String,
    val weak: Int,
    val resist: Int,
    /** Multiplicador de dano que cada membro recebe deste tipo, na ordem do time (ja com a habilidade). */
    val multipliers: List<Double> = emptyList(),
)

data class SwapView(val outKey: String, val outName: String, val pick: SlotView, val gain: Double, val reasons: List<String>)

data class RaidPickView(
    val slot: SlotView,
    /** 0..100, relativo ao melhor da lista. */
    val score: Int,
    /** Melhor golpe contra o chefe; vazio se nao ha dados de golpes. */
    val move: String,
    /** false = o golpe precisa ser aprendido antes. */
    val moveReady: Boolean,
    val effectiveness: Double,
    /** Pior multiplicador que ele recebe dos golpes do chefe. */
    val worstTaken: Double,
)

data class RaidView(
    val boss: String,
    val types: List<String>,
    /** Vazio quando o chefe nao esta terastalizado. */
    val teraType: String,
    val moves: List<String>,
    val stars: Int,
    val feature: String,
    val partySize: Int,
    val species: String = "",
    val aspects: List<String> = emptyList(),
    val pcPicks: List<RaidPickView>,
    val allPicks: List<RaidPickView>,
)

data class BuilderState(
    val team: List<SlotView> = emptyList(),
    /** Um set por slot, na mesma ordem de [team]. */
    val sets: List<SetView> = emptyList(),
    val total: Double = 0.0,
    val strength: Double = 0.0,
    val defense: Double = 0.0,
    val offense: Double = 0.0,
    val roles: Double = 0.0,
    val synergy: Double = 0.0,
    val weaknesses: List<String> = emptyList(),
    /** Os 18 tipos, na ordem da tabela. */
    val typeBalance: List<TypeBalanceView> = emptyList(),
    val pcPicks: List<PickView> = emptyList(),
    val allPicks: List<PickView> = emptyList(),
    val pcTeams: List<TeamView> = emptyList(),
    val allTeams: List<TeamView> = emptyList(),
    val pcSwaps: List<SwapView> = emptyList(),
    val allSwaps: List<SwapView> = emptyList(),
    val ownedCount: Int = 0,
    val raid: RaidView? = null,
    val legendaries: Boolean = false,
    val megas: Boolean = true,
    val doubles: Boolean = false,
    val cores: List<CoreView> = emptyList(),
    val coresScore: Double = 0.0,
    val threats: List<ThreatView> = emptyList(),
    val trainer: TrainerView? = null,
    val error: String? = null,
    /** Mensagem de sucesso (ex.: time aplicado ao party). */
    val notice: String? = null,
)

/** Busca por nome enquanto o jogador digita. */
data class SearchRequest(val query: String = "", val kind: String = "pokemon")

data class SearchResult(
    val query: String = "",
    val results: List<SlotView> = emptyList(),
    val kind: String = "pokemon",
    val trainers: List<TrainerSummary> = emptyList(),
)
