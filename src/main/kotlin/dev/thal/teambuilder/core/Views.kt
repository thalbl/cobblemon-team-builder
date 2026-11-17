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

/** Chance de aparecer agora no bioma do jogador, num tipo de lugar ("grounded", "surface"...). */
data class SpawnChanceView(val type: String, val chance: Double)

/** Um dos biomas onde a especie mais aparece. */
data class SpawnBiomeView(val biome: String, val type: String, val chance: Double)

/**
 * Onde capturar uma especie que o jogador nao tem.
 *
 * @param natural false quando ela nao aparece na natureza (lendarios de evento, megas, evolucoes)
 * @param biome bioma onde o jogador esta
 * @param here chance agora, neste bioma e nesta hora, por tipo de lugar
 * @param otherTime aparece neste bioma, mas em outro horario
 * @param times faixas de horario quando todas as regras dela tem horario ("night", "day"...)
 */
data class SpawnView(
    val natural: Boolean = true,
    val biome: String = "",
    val here: List<SpawnChanceView> = emptyList(),
    val otherTime: Boolean = false,
    val best: List<SpawnBiomeView> = emptyList(),
    val biomeCount: Int = 0,
    val anywhere: Boolean = false,
    val minLevel: Int = 0,
    val maxLevel: Int = 0,
    val bucket: String = "",
    val times: List<String> = emptyList(),
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
    /** Onde capturar (so para especies que o jogador nao tem). */
    val spawn: SpawnView? = null,
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
/** Uma dupla do 2v2: indices no time, nota, se e a abertura sugerida e os motivos. */
data class PairView(
    val a: Int,
    val b: Int,
    val score: Double,
    val lead: Boolean,
    val good: List<String>,
    val bad: List<String>,
)

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
data class TrainerSummary(
    val id: String,
    val name: String,
    val type: String,
    val level: Int,
    val members: List<SlotView>,
    /** O jogador ja venceu este treinador (progresso do RCT). */
    val defeated: Boolean = false,
    /** Proximo passo da serie do jogador no RCT. */
    val next: Boolean = false,
    /** 0..100, quao pronto o time atual esta; -1 sem time. */
    val readiness: Int = -1,
)

data class TrainerEnemyView(
    val slot: SlotView,
    val level: Int,
    val moves: List<String>,
    val ability: String,
    val item: String,
    val nature: String,
    /** Quem do time atual melhor o enfrenta (vazio se o time esta vazio). */
    val answer: String = "",
    /** Velocidade real dele (com IV, EV, natureza e Choice Scarf). */
    val speed: Int = 0,
    /** Membros do seu time mais rapidos e mais lentos que ele, como "Pikachu 102". */
    val faster: List<String> = emptyList(),
    val slower: List<String> = emptyList(),
    /** A resposta ([answer]) ataca antes (1), depois (-1) ou empata/sem time (0). */
    val answerFaster: Int = 0,
    /** Quanto os membros do seu time tiram dele (os 3 que mais tiram). */
    val dealt: List<DamageView> = emptyList(),
    /** Quanto ele tira da sua resposta. */
    val taken: DamageView? = null,
)

/** Dano estimado de um golpe, em % do HP do alvo. */
data class DamageView(val attacker: String, val target: String, val move: String, val min: Int, val max: Int)

data class CounterView(val slot: SlotView, val score: Int, val handles: List<String>)

data class TrainerView(
    val id: String,
    val name: String,
    val type: String,
    val enemies: List<TrainerEnemyView>,
    val pcPicks: List<CounterView>,
    val allPicks: List<CounterView>,
    /** 0..100, quao pronto o time atual esta; -1 sem time. */
    val readiness: Int = -1,
    /** Nivel medio dos Pokemon do jogador no time e nivel do mais forte do treinador. */
    val teamLevel: Int = 0,
    val enemyLevel: Int = 0,
    /** Adversarios que o time atual nao responde bem. */
    val unanswered: List<String> = emptyList(),
    val defeated: Boolean = false,
    val next: Boolean = false,
)

/**
 * Um Pokemon do jogador que, evoluido, entraria bem no time.
 *
 * @param method "lv:28", "item:cobblemon:thunder_stone", "trade", "friendship" ou "other"
 * @param stages quantas evolucoes ate la (Machop -> Machamp = 2)
 */
data class EvolutionView(
    val from: SlotView,
    val to: SlotView,
    val gain: Double,
    val reasons: List<String>,
    val method: String,
    val stages: Int,
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
    /** Raid em grupo: papel de cada jogador (voce primeiro); vazio quando so voce esta na raid. */
    val group: List<RaidRoleView> = emptyList(),
)

/**
 * Papel de um jogador numa raid em grupo.
 *
 * @param role "attack" ou "support"
 * @param avoided tipo de golpe do chefe que ficaria repetido como fraqueza; vazio se nenhum
 */
data class RaidRoleView(
    val player: String,
    val me: Boolean,
    val role: String,
    val pick: SlotView?,
    val move: String = "",
    val moveReady: Boolean = true,
    val supportMoves: List<String> = emptyList(),
    val learn: List<String> = emptyList(),
    val avoided: String = "",
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
    /** So no 2v2: o time dividido em duplas (a primeira e a abertura) e a nota de cada par. */
    val pairs: List<PairView> = emptyList(),
    val pairMatrix: List<List<Double>> = emptyList(),
    val trainer: TrainerView? = null,
    val error: String? = null,
    /** Mensagem de sucesso (ex.: time aplicado ao party). */
    val notice: String? = null,
    /** Ids das conquistas que o jogador ja tem (ver [AchievementList]). */
    val achievements: List<String> = emptyList(),
    /** Pokemon do PC que valeria evoluir para o time atual. */
    val evolutions: List<EvolutionView> = emptyList(),
    /** Limite de nivel do RCT para o jogador (0 = desconhecido). */
    val levelCap: Int = 0,
)

/** Busca por nome enquanto o jogador digita. */
data class SearchRequest(
    val query: String = "",
    val kind: String = "pokemon",
    /** Time atual, para a busca de treinadores dizer quao pronto ele esta contra cada um. */
    val keys: List<String> = emptyList(),
)

data class SearchResult(
    val query: String = "",
    val results: List<SlotView> = emptyList(),
    val kind: String = "pokemon",
    val trainers: List<TrainerSummary> = emptyList(),
    /** Progresso do RCT: limite de nivel e quantos treinadores ja vencidos (0 sem RCT). */
    val levelCap: Int = 0,
    val defeatedCount: Int = 0,
)
