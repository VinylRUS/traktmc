# Trakt (Тракт) — 0.1-26.3

> [!IMPORTANT]
> **Public code-only mirror.** This repository contains the LGPL-licensed
> functional source of **Trakt** only. All creative assets of the full
> private package — textures, entity skins, localization prose, structure
> templates and the wiki — are **not included** here, because they are
> All Rights Reserved material derived from the upstream project
> ([Village Quest](https://github.com/TopasMusic) by TopasMusic) or pending
> replacement. A jar built from this mirror compiles the mod but is not a
> playable distribution; see `NOTICE.md`.

**Trakt turns settlements into living RPG hubs with dynamic caravans,
player-to-player relays, custom JSON quests, and guild progression.**

«Тракт превращает поселения в живые RPG-хабы с динамичными караванами,
релеями между игроками, кастомными квестами и развитием гильдий.»

> Based on Village Quest by TopasMusic (LGPL-3.0).
> Not affiliated with the original project.

## Что это

Линия разработки мода **Trakt** (версия 0.1, Minecraft 26.3) — форк
Trakt, переделываемый под собственный релиз: новые квесты как
данные (JSON), квестгивер в деревнях, валюта Waycoins, релеи между
игроками, собственные ассеты. Стратегия — в [RELEASE_PLAN.md](../../RELEASE_PLAN.md)
(корень репо), план замены ассетов — в
[ASSET_REPLACEMENT_PLAN.md](ASSET_REPLACEMENT_PLAN.md), контракт панели —
в [rest_api.md](rest_api.md).

Идентичность:

| Параметр | Значение |
| --- | --- |
| Имя | Trakt |
| Mod id | `trakt` (полный ребрендинг с 2026-10-26: mod id, пакет `de.trakt`, команда `/trakt` + алиас `/tr`, REST-префикс `/trakt/*`; сейвы Trakt не переносятся) |
| Версия | 0.1 (папка `0.1-26.3` = версия-MC; следующие линии — `0.2-26.3` и т.д.) |
| maven group / jar | `io.github.vinylrus` / `trakt-0.1-mc26.3.jar` |
| Атрибуция | обязательна всюду (см. цитату выше) |

## Статус 0.1

- Код — стартовая точка = Village Quest 2.5.0 + REST API + транспорт
  «агент» + кастомные квесты v1 (580+ тестов зелёные).
- Ассеты пока апстримовые (ARR) — **распространять сборку нельзя**,
  замена по ASSET_REPLACEMENT_PLAN (волна 2 релизного плана).
- Волна 1 (логика): снос квестового хардкода, Waycoins, квестгивер
  в деревнях, релеи — см. RELEASE_PLAN §8.

## Сборка

```bash
cd Trakt/0.1-26.3
./gradlew build          # JDK 25 (Temurin), jar -> build/libs/trakt-0.1-mc26.3.jar
```

Предупреждение о лицензии: пока ассеты не заменены, этот проект живёт
только в приватном репозитории.
