# Cobblemon Team Builder

Mod Fabric para Minecraft 1.21.1 + Cobblemon 1.7 que analisa os Pokémon do seu party e PC e monta times para batalhas 1v1 (singles) e 2v2 (duplas). Para abrir, aperte **H**.

## Recursos

- **Sugestões** para o próximo slot, vindas do seu PC e de todo o Cobblemon, com os motivos de cada uma (tipos que segura, tipos em que bate, funções que traz).
- **Times completos** montados a partir dos slots que você já preencheu.
- **Análise** da defesa por tipo e trocas sugeridas.
- **Funções por membro** (atacante físico/especial, setup, pivô, tanque, suporte), com a recomendada marcada. A função escolhida muda golpes, item, natureza e EVs do Pokémon e o que os companheiros precisam cobrir.
- **Prioridade de IV**: em que ordem vale subir os IVs dos seus Pokémon para a função atual.
- **Formas mega**: botões para alternar entre a forma normal e as megas (só uma por batalha).
- **Raid**: detecta o chefe do Cobblemon Raid Dens (ou aceita um digitado) e ordena os melhores counters.
- **Treinadores**: escolha um líder, Elite 4, campeão ou qualquer treinador do Radical Cobblemon Trainers e veja o time dele, quem do seu time responde a cada um e os melhores counters (com botão para montar o time a partir do PC).
- **Ameaças**: os Pokémon fortes do meta que o seu time tem dificuldade de segurar, com quem é fraco e quem responde.
- **Núcleos**: detecta chuva, sol, areia, neve, terrenos e Trick Room no time e passa a valorizar quem aproveita (Swift Swim, Chlorophyll, sets lentos no Trick Room...).
- **Checklist** ("Falta"): o que seu Pokémon ainda precisa para ficar igual ao set (golpes, mint, Ability Capsule/Patch, item, mega stone, EVs, IVs), conferindo o seu inventário.
- **Busca**: enquanto você digita, mostra cards dos Pokémon que combinam, os seus primeiro.
- **Aplicar** o time ao party e **salvar** times para consultar depois.
- Interface em português e inglês, conforme o idioma do jogo.

Os dados competitivos vêm das estatísticas de uso do Smogon (`singles.json` e `doubles.json`).

## Comandos

```
/teambuilder pc | meta <pokemon> | suggest <pokemon...> | complete <pokemon...>
/teambuilder analyze | sets [pokemon...] | raid [chefe [tipo tera]]
/teambuilder option legendaries|megas|doubles true|false
/teambuilder reload | snapshot [update [mês] [doubles] | reset]   (admin)
```

## Configuração

`config/cobblemon_team_builder/config.json` (criado na primeira execução): opções iniciais (lendários, megas, 2v2), quantidade de resultados da busca e de ameaças, nível de permissão dos comandos de admin e todos os pesos da nota, separados para 1v1 e 2v2. Depois de editar, use `/teambuilder reload`. A tecla de abrir (H) muda em Opções > Controles.

## Atualizar os dados no jogo

```
/teambuilder snapshot                       # mostra os snapshots em uso
/teambuilder snapshot update [mês] [doubles] # baixa do Smogon (ex.: 2026-11)
/teambuilder snapshot reset                 # volta aos que vêm no jar
```

Os arquivos baixados ficam em `config/cobblemon_team_builder/meta/`.

## Compilar

Precisa de um JDK 21 ou mais novo:

```
./gradlew build
```

O jar sai em `build/libs/`.

## Atualizar os dados do Smogon

```
python tools/build_snapshot.py            # singles
python tools/build_snapshot.py --doubles  # duplas (2v2)
```

## Licença

MIT
