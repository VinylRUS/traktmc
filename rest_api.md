# REST API мода Trakt Remake (rest_api.md)

> Документ — **контракт для стороны бота/панели** (репозиторий `VinylRUS/dibilkinbot`).
> Здесь всё, что нужно для интеграции: адрес, авторизация, все эндпоинты с примерами,
> флоу привязки Minecraft↔Discord, события и формат кастомных квестов.
> Реализация: пакет `de.trakt.api` (встроенный JDK HttpServer + Gson, ноль внешних зависимостей).

**Два транспорта на выбор (конфигурируются без изменения кода):**

- **Транспорт A (по умолчанию)** — встроенный HTTP-сервер мода, панель ходит на сервер
  (`http://ip:8790`). Подходит, если хостинг разрешает входящие порты.
- **Транспорт B (агент)** — мод сам подключается к панели исходящими запросами.
  Подходит для хостингов **без входящих портов** (наш случай: exarathon).
  В этом режиме панель должна развернуть небольшой гейтвей из трёх эндпоинтов —
> см. §4. Эндпоинты, форматы ответов, события, привязка и квесты (§5–§9)
> **идентичны для обоих транспортов**: разница только в способе доставки.

---

## 1. Архитектура в двух словах

Транспорт A (входящие порты разрешены — по умолчанию):
```
┌──────────────────┐   HTTP GET/POST (Bearer)   ┌─────────────────────────────┐
│  Панель + бот    │ ─────────────────────────▶ │  MC-сервер                 │
│  (BotHost)       │ ◀───────────────────────── │  мод trakt-remake   │
└──────────────────┘   JSON-ответы              └─────────────────────────────┘
```

Транспорт B (порты запрещены — exarathon): соединения инициирует МОД
```
┌──────────────────┐   ①GET /trakt/commands (опрос)  ┌─────────────────────────┐
│  Панель + бот    │ ◀────────────────────────── │  MC-сервер (exarathon)  │
│  (BotHost)       │ ②POST /trakt/results (ответы)  │  мод сам ходит в панель  │
│  гейтвей из 3    │ ──────────────────────────▶ │  по HTTPS, порты НЕ     │
│  эндпоинтов      │ ③POST /trakt/events (события)  │  открываются             │
└──────────────────┘                            └─────────────────────────┘
```

- **Мод — источник данных, панель — клиент.** Мод ничего не знает о Discord/панели.
- Транспорт A: панель поллит эндпоинты (события — через `after_id`, см. §7).
  Транспорт B: мод сам доставляет события (§4) и исполняет команды панели.
- Все чтения игрового состояния выполняются на главном потоке сервера
  (каждый запрос ждёт до 5 сек; если сервер лагает — придёт `503 server_busy`).
- **exarathon-спячка**: когда на сервере нет игроков, процесс выключен.
  Транспорт A: API недоступен — `connection refused` = «сервер спит», не ошибка.
  Транспорт B: прекращаются опросы гейтвея — молчание дольше
  `max(15 с, 3 × poll_seconds)` = «сервер спит». После пробуждения события
  догоняются автоматически (журнал персистентный) — ничего не теряется.

## 2. Конфигурация мода

Файл `config/trakt/api.properties` (создаётся при первом запуске):

| Ключ | По умолчанию | Смысл |
|---|---|---|
| `api_enabled` | `true` | мастер-выключатель API |
| `transport` | `server` | `server` = транспорт A (встроенный HTTP), `agent` = транспорт B (исходящие запросы к панели) |
| `api_port` | `8790` | TCP-порт (транспорт A) |
| `api_bind` | `0.0.0.0` | адрес привязки (транспорт A); для доступа с BotHost нужен `0.0.0.0` |
| `api_token` | генерируется | bearer-токен панели→мод (транспорт A) |
| `panel_url` | (пусто) | транспорт B: публичный базовый URL панели, напр. `https://mybot.bothost.tech` |
| `gateway_token` | генерируется | транспорт B: общий секрет мод→панель |
| `poll_seconds` | `2` | транспорт B: интервал опроса гейтвея (1–60) |
| `server_id` | `main` | транспорт B: метка сервера, которую знает панель (`[a-z0-9._-]`, до 32 симв.) |
| `push_batch` | `128` | транспорт B: макс. событий за один push |
| `rate_limit_per_minute` | `240` | лимит запросов в минуту (транспорт A) |
| `max_events` | `5000` | ёмкость кольцевого журнала событий |
| `default_locale` | `ru_ru` | язык текстов в ответах API (`title`, `progress_line`) |
| `link_code_ttl_minutes` | `15` | срок жизни кода привязки |

Изменения применяются командой `/trakt api reload` (в игре/консоли, права оператора).
**Токен** смотрится командой `/trakt api status` (маскированный) или `/trakt api token show`
>(полностью; в режиме агента показывает gateway_token), ротация — `/trakt api token rotate`
>(ротирует токен активного транспорта; старый перестаёт действовать сразу).

Хранилище API живёт в `config/trakt/api/` (links.json, events.jsonl,
quests/*.json, gateway.json — состояние транспорта B) —
**переживает рестарты и смены мира**: привязки и созданные квесты не слетают.

## 3. Авторизация, ошибки, лимиты

- Каждый запрос: заголовок `Authorization: Bearer <api_token>`. Нет/неверен → `401`:
  ```json
  {"error": "unauthorized", "message": "missing or invalid bearer token"}
  ```
- Любая ошибка: HTTP-статус + тело `{"error": "<код>", "message": "<текст>"}`.
  Коды: `bad_request` 400, `unauthorized` 401, `not_found` 404, `method_not_allowed` 405,
  `conflict` 409, `rate_limited` 429, `server_busy`/`server_unavailable` 503, `internal_error` 500.
- Превышен лимит → `429 rate_limited`. Рекомендация панели: кешировать ответы 15–60 сек.
- Тело запроса ≤ 1 МиБ; только JSON-объект.

**Базовый URL (транспорт A)**: `http://<адрес-или-ип-сервера>:8790`. Все пути начинаются с `/api`.
`GET /api` возвращает машиночитаемый список всех эндпоинтов.

## 4. Транспорт B: режим агента (хостинги без входящих портов)

**Когда использовать**: хостинг не даёт открывать входящие порты (exarathon).
Мод сам инициирует все соединения к панели — с точки зрения хостинга это обычный
исходящий HTTPS-трафик. Никаких портов, туннелей и сторонних сервисов.

**Включение на стороне мода** (`api.properties` + `/trakt api reload`):
```properties
transport=agent
panel_url=https://mybot.bothost.tech   # базовый URL панели, БЕЗ /trakt/...
gateway_token=<48 hex>                   # /trakt api token show
server_id=exarathon-prod
poll_seconds=2
```

### 4.1. Что панель должна развернуть (гейтвей из трёх эндпоинтов)

Все три эндпоинта авторизуются заголовком `Authorization: Bearer <gateway_token>`
(неверный → `401`; неизвестный `server_id` → `403`). Тело — JSON-объект, ответы — JSON.

**① GET /trakt/commands** — мод опрашивает раз в `poll_seconds` (это же heartbeat):
```
GET /trakt/commands?after_id=<последний исполненный id>&server_id=exarathon-prod&online=3&mod_version=2.5.0
```
Ответ — очередь команд для мода:
```json
{
  "commands": [
    {"id": 7, "method": "GET", "path": "/api/players"},
    {"id": 8, "method": "GET", "path": "/api/players/d0b576ec-...', "query": "locale=en_us"},
    {"id": 9, "method": "POST", "path": "/api/quests", "body": { ...определение квеста... }},
    {"id": 10, "method": "PATCH", "path": "/api/quests/custom/smoke_gather", "body": {"enabled": false}},
    {"id": 11, "method": "DELETE", "path": "/api/links/123456789"}
  ],
  "last_command_id": 11
}
```
Команда = **виртуальный HTTP-запрос**: метод + путь + query-строка + body-объект.
Пути/тела/ответы — РОВНО те же, что в §5–§9 (одни и те же обработчики в моде).
`id` — монотонные на сервер панели; мод исполняет каждую команду не более одного раза
(идентификатор исполненного персистится до отправки результата — дубли игнорируются).

**② POST /trakt/results** — мод присылает результаты (может батчами, повторы возможны —
дедуплицируйте по `command_id`):
```json
{
  "server_id": "exarathon-prod",
  "results": [
    {"command_id": 7, "status": 200, "body": { ...успешный ответ... }},
    {"command_id": 8, "status": 404, "body": {"error": "not_found", "message": "..."}}
  ]
}
```
Ответ панели: `{"ok": true}`. Формат `body` идентичен транспортy A (200 — полезная
нагрузка, иначе — объект `error/message`; HTTP-статус дублируется в `status`).

**③ POST /trakt/events** — мод пушит новые события (батчем до `push_batch`):
```json
{
  "server_id": "exarathon-prod",
  "first_event_id": 101,
  "last_event_id": 105,
  "events": [ { ...объекты событий из §7... } ]
}
```
Ответ панели **обязателен**: `{"acked_event_id": 105}` — мод продолжит с этого id
(неподтверждённое переотправляется). `first_event_id` позволяет панели заметить разрыв
(например, журнал событий был сброшен, id пошли заново — обновите курсор принудительно).

### 4.2. Правила для панели (транспорт B)

1. **Онлайн/офлайн**: нет опроса `GET /trakt/commands` дольше `max(15 с, 3 × poll_seconds)` —
   сервер спит/выключен. Это нормальное состояние exarathon, не авария.
2. Очередь команд храните в своей БД с монотонными id; неподтверждённые результатом
   команды можно переотправлять (мод игнорирует `id <= after_id`).
3. Результаты идемпотентно складывайте по `command_id`; UX: пользователь панели нажал
   кнопку → поставили команду → через 1–2 poll-цикла (2–4 с) есть результат.
4. События сохраняйте с дедупликацией по `id` (ат-least-once доставка), курсор —
   последний `acked_event_id`/`last_event_id`.
5. HTTPS обязателен в проде (токен в заголовке). Для локальной отладки — `http://127.0.0.1:...`.
6. Rate: не чаще 1 опроса/сек не запрашивайте — мод сам приходит раз в `poll_seconds`.

**Эталонная реализация гейтвея** (Python, без зависимостей + автотест):
`tools/gateway_reference.py` в репозитории мода — можно портировать в FastAPI как есть.

## 5. Эндпоинты: сервер и игроки

### GET /api/health — статус
```json
{
  "status": "ok", "mod_version": "2.5.0-unreleased.21",
  "online_players": 3, "max_players": 20,
  "uptime_seconds": 7540,
  "events_last_id": 412, "custom_quests": 2
}
```
Панель может звать раз в 30–60 сек: живой индикатор + «сервер не спит».

### GET /api/players — список игроков
Без параметров — только онлайн. `?all=true` — все известные (онлайн + все, кто есть в данных мода).
```json
{"count": 2, "players": [
  {"uuid": "069a79f4-44e9-4726-a5be-fca90e38aaf5", "name": "VinylRUS", "online": true},
  {"uuid": "853c80ef-3c37-49fd-aa49-938b674adae6", "name": "Steve", "online": false}
]}
```

### GET /api/players/{uuid} — карточка игрока
Всё об игроке одним запросом (баланс, дейлик, виклик, кастом, репутация, маршруты):

```json
{
  "uuid": "069a79f4-...", "name": "VinylRUS", "online": true, "balance": 1450,
  "daily": {
    "active": true, "completed_today": false,
    "quest_id": "daily/wheat_harvest", "state": "active",
    "title": "Урожай пшеницы", "progress_line": "Собрано пшеницы: 12/24"
  },
  "weekly": {"active": true, "completed_count": 5, "title": "Рыночная неделя", "progress_lines": ["..."]},
  "custom": {
    "id": "gather_wheat", "title": "Собери пшеницу", "progress": 12, "target": 24,
    "subject": "Пшеница", "available_count": 1
  },
  "reputation": {"total": 210, "tracks": {"farming": 80, "crafting": 40, "animals": 30, "trade": 60, "monster": 0}},
  "routes": {"count": 2, "total_successes": 14, "income_today": 9, "escrow": 0},
  "village_count": 4, "in_guild": true,
  "quests_completed_total": 37,
  "discord_link": {"discord_id": 123456789012345678, "discord_username": "vinyl", "mc_uuid": "069a79f4-...", "mc_name": "VinylRUS", "linked_at": 1760000000000}
}
```
Поля `daily.title/progress_line` и `weekly.title` уже переведены в `default_locale` (ru_ru).
`discord_link` = null, если аккаунт не привязан.

### GET /api/players/by-discord/{discord_id} — карточка по Discord ID
То же самое + обёртка: `{"discord_id": ..., "link": {...}, "player": {...карточка...}}`.
404, если Discord-аккаунт не привязан. Это главный эндпоинт для `/traktme` и персональных уведомлений.

## 6. Эндпоинты: мир и экономика

### GET /api/villages — деревни
Деревни в Trakt **персональные** (у каждого игрока своя сеть): возвращается плоский
список с владельцем. `?limit=200` (1..1000).

```json
{"count": 1, "villages": [{
  "owner_uuid": "069a79f4-...", "owner_name": "VinylRUS", "index": 0,
  "x": 120, "z": -430, "type": "granary",
  "need": "grain", "need_label": "Зерно", "support": 12,
  "condition": "stable", "cycles_completed": 3,
  "route_arrivals": 14, "route_failures": 2
}]}
```
`condition`: `crisis | strained | stable | ...`. `type`: `granary|forge|pasture|apiary|archive`.

### GET /api/caravans — караваны/торговые маршруты
Игроки, у которых есть маршруты (прогресс каравана виртуальный — «активен» = маршрут есть):
```json
{"count": 1, "players": [{
  "uuid": "069a79f4-...", "name": "VinylRUS", "online": true,
  "total_successes": 14, "income_today": 9, "escrow": 0,
  "routes": [
    {"index": 0, "name": "Дорога к амбару", "destination_x": 320, "destination_z": -180,
     "active_event": false, "successes": 6},
    {"index": 1, "name": "Тропа кузнеца", "destination_x": -95, "destination_z": 640,
     "active_event": true, "successes": 8}
  ]
}]}
```
`active_event: true` — на маршруте сейчас инцидент (напасть/помочь): годится для пинга владельца.

### GET /api/economy/summary — экономика сервера
```json
{
  "players_tracked": 12, "online_players": 3,
  "money_supply": 84350, "income_today": 27, "escrow": 0,
  "quests_completed_total": 380,
  "currency_unit": "waycoin (1 gold = 100 silver = 10000 copper); v1 fields in silver"
}
```

**Семантика денежных полей v1 (F7a, D7):** внутренняя единица мода — медная
путевая монета (1 серебряная = 100 медных, 1 золотая = 10 000 медных), но
поля v1 (`balance`, `money_supply`, `escrow`, `income_today`) продолжают
отдаваться **в серебряных монетах** — панель не ломается, цифры игрока
визуально совпадают с 2.5. Авторное поле квестов `rewards.silvermarks` —
тоже серебро. Новые поля `waycoins`/`copper|silver|gold` — в F7c.
(Казна гильдии валюты не держит — у гильдий известность, не деньги; расширение экономики — Phase 4.)

### GET /api/leaderboards/{type} — топы
`type`: `wealth` | `reputation` | `quests_total` | `route_successes` | `weekly_completed`. `?limit=10`.
```json
{"type": "wealth", "entries": [
  {"rank": 1, "uuid": "069a79f4-...", "name": "VinylRUS", "value": 1450},
  {"rank": 2, "uuid": "853c80ef-...", "name": "Steve", "value": 620}
]}
```
`quests_total` — счётчик всех закрытых квестов (дейли+викли+кастом), ведётся с момента
установки этой версии мода. Игроки со значением 0 не показываются.

## 7. События: GET /api/events?after_id={id}

Журнал событий персистентный (переживает рестарты и спячку exarathon), ID монотонные.

- Первый запрос: `after_id=0` → последние `limit` (по умолчанию 100, макс 1000) событий + `last_id`.
- Дальше: `after_id=<last_id>` каждые 30–60 сек. Новые = события с `id > after_id`.
- Хранится до `max_events` (5000) последних; если бот молчал дольше ёмкости журнала —
  просто продолжит с последнего доступного id (дыра в нумерации = норма).

```json
{"last_id": 415, "count": 2, "events": [
  {"id": 414, "ts": 1761002345000, "type": "quest_completed",
   "player_uuid": "069a79f4-...", "player_name": "VinylRUS",
   "data": {"quest_id": "daily/wheat_harvest", "cycle": "daily", "title": "Урожай пшеницы"}},
  {"id": 415, "ts": 1761002401000, "type": "caravan_arrived",
   "player_uuid": "853c80ef-...", "player_name": "Steve",
   "data": {"reward": 7}}
]}
```

**Типы событий** (`type` → что в `data`):

| type | data | когда |
|---|---|---|
| `server_started` | `online_players` | сервер поднялся (после спячки тоже) |
| `server_stopping` | — | сервер останавливается (уходит в спячку) |
| `player_joined` / `player_left` | `online_after` | вход/выход игрока |
| `quest_completed` | `quest_id`, `cycle` (`daily`/`daily-bonus`/`weekly`/`custom`), `title`, для custom ещё `quest_title`, `rewards` | закрыт любой квест |
| `custom_quest_accepted` / `custom_quest_abandoned` | `quest_id`, `quest_title` | игрок принял/отменил кастом |
| `caravan_arrived` | `reward` (может быть 0 — бюджет исчерпан) | караван довёз груз |
| `caravan_incident_resolved` | `event` (тип инцидента) | инцидент на маршруте отражён |
| `caravan_incident_failed` | `event` | инцидент провален |
| `caravan_crew_lost` | `entity` (id моба) | погиб член экипажа каравана |
| `questmaster_invited` | `village_key` (откуда), `yard_x`/`yard_y`/`yard_z` (куда) | игрок с высоким мастерством репутации пригласил квестодателя к себе на двор (F6b) |
| `questmaster_settled` | `village_key`, `x`/`y`/`z` | квестодатель поселился в деревне при визите игрока (F6c) |
| `relay_sent` | `recipient`, `items` (сводка «id xN»), `tariff_paid` (медь) | отправка релея: игрок оплатил тариф и отправил кладь на терминал получателя (F3b) |
| `relay_delivered` | `sender`, `items`, `travel_days` | получатель забрал кладь со своего терминала (F3b) |
| `relay_returned` | `reason` (timeout/cancelled/terminal_destroyed/route_removed), `items` | отправитель забрал возврат со своего терминала (F3b) |
| `link_claimed` | `discord_id`, `mc_uuid`, `mc_name` | Discord-аккаунт привязан к MC |
| `custom_quest_created` / `custom_quest_updated` / `custom_quest_deleted` | `quest_id`, (`quest_title`) | управление квестами через панель |
| `api_token_rotated` | `by` (кто ротировал) | после ротации панель должна перечитать токен |

Идеи для бота: посты в канал («📦 Караван Steve довёз груз +7 серебряных марок»),
персональные уведомления привязанным игрокам («твой караван ограблен!»), алерт Матке
при `server_stopping` → нет `server_started` дольше X минут.

## 8. Привязка Minecraft ↔ Discord

**Что к чему привязывается:** Discord ID (число, из slash-команды бота) ↔ Minecraft UUID + ник.
Одно MC-ведро = один Discord ID (перепривязка переносит связь на новый Discord ID).

Флоу (сначала игрок, потом бот):

1. Игрок в Minecraft: `/trakt link` → мод выдаёт **6-значный код** (живет 15 мин):
   - «Код привязки: 428190 — отправьте его боту в Discord командой /linkmc 428190»
   - `/trakt link status` — проверить состояние, `/trakt link cancel` — отменить код.
2. Игрок в Discord: `/linkmc 428190` → бот вызывает:
   ```http
   POST /api/links/claim
   {"code": "428190", "discord_id": 123456789012345678, "discord_username": "vinyl"}
   ```
   Ответ 200: `{"link": {"discord_id": 123456789012345678, "discord_username": "vinyl",
   "mc_uuid": "069a79f4-...", "mc_name": "VinylRUS", "linked_at": 1760000000000}}`
   Ошибки: `404` — код не найден/истёк (пусть игрок сделает `/trakt link` заново), `400` — нет полей.
3. Далее бот для любых персональных данных зовёт `GET /api/players/by-discord/{discord_id}`.

Прочее:
- `GET /api/links/{discord_id}` — только связь (без карточки игрока); 404 если не привязан.
- `DELETE /api/links/{discord_id}` — отвязать (например, команда `/unlinkmc`).
- Пока сервер спит — коды живут в файле и не сгорают (TTL проверяется по факту claim).

## 9. Квесты: просмотр и создание через панель

### GET /api/quests — все квесты сервера
Builtin (дейли 17 шт., викли 7 шт.) + кастомные. У builtin `id` вида `daily/<type>`, `weekly/<type>`.
```json
{"count": 26, "quests": [
  {"id": "daily/wheat_harvest", "cycle": "daily", "source": "builtin", "category": "cooking",
   "title_en": "Wheat Harvest", "title_ru": "Урожай пшеницы", "completion_mode": "QUESTMASTER_TURN_IN"},
  {"id": "weekly/market_week", "cycle": "weekly", "source": "builtin", "category": "trade",
   "title_en": "Market Week", "title_ru": "Рыночная неделя", "completion_mode": "QUESTMASTER_TURN_IN"},
  {"id": "custom/gather_wheat", "short_id": "gather_wheat", "cycle": "custom", "source": "custom",
   "enabled": true, "title_en": "Gather Wheat", "title_ru": "Собери пшеницу",
   "titles": {"en_us": "Gather Wheat", "ru_ru": "Собери пшеницу"},
   "offer": "Квестодатель ждёт.",
   "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 24},
   "rewards": {"silvermarks": 12, "levels": 3, "reputation_track": "FARMING",
               "reputation_amount": 5, "items": [{"item": "minecraft:emerald", "count": 2}]},
   "repeat": "daily", "subject": "Пшеница"}
]}
```
`GET /api/quests/{id}` — одно описание (id как в списке).

### POST /api/quests — создать кастомный квест
Тело — JSON квеста (см. схему ниже). Ответ — созданный квест + `"created": true`.
Квест **применяется на лету**: игроки сразу видят его в `/trakt custom`.
Ошибки валидации → `400` со списком через `;`.

```json
{
  "id": "gather_wheat",
  "title": {"en_us": "Gather Wheat", "ru_ru": "Собери пшеницу"},
  "offer": {"ru_ru": "Квестодатель просит пополнить амбар."},
  "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 24},
  "rewards": {
    "silvermarks": 12,
    "levels": 3,
    "reputation_track": "FARMING",
    "reputation_amount": 5,
    "items": [{"item": "minecraft:emerald", "count": 2}]
  },
  "repeat": "daily"
}
```

**Схема:**

| Поле | Обяз. | Правила |
|---|---|---|
| `id` | да | `[a-z0-9_]{3,40}`, уникальный |
| `title` | да | объект локалей, минимум `en_us` (`ru_ru` желательно) |
| `offer` | нет | объект локалей — текст при принятии |
| `goal.kind` | да | см. таблицу видов целей ниже |
| `goal.amount` | да | 1..100000 |
| `goal.item/block/entity` | — | id вида `minecraft:wheat`; какой нужен — зависит от kind |
| `rewards.silvermarks` | нет | 0..1000000 (1 крона = 10) |
| `rewards.levels` | нет | 0..100 (уровни опыта) |
| `rewards.reputation_track` + `reputation_amount` | нет | трек: `FARMING`, `CRAFTING`, `ANIMALS`, `TRADE`, `MONSTER_HUNTING` |
| `rewards.items` | нет | до 10 × `{item, count≤64}` |
| `rewards.choice_of` | нет | F2a: 2–4 опции `{silvermarks 0..1000000, items ≤2×{item, count≤64}}`. Исключает остальные поля наград. Снапшот замораживается при принятии квеста; игрок выбирает опцию в момент выдачи (`/trakt rewards claim`). GET квеста помечает это режимом `"mode":"choice_of"` со списком опций |
| `repeat` | нет | `once` (по умолчанию) или `daily` (можно брать заново каждый день после ресета) |
| `enabled` | нет | `true` по умолчанию |

**Виды целей (`goal.kind`):**

| kind | параметры | как считает прогресс |
|---|---|---|
| `collect_item` | `item` | подбор предмета (ванильная статистика, с момента принятия) |
| `craft_item` | `item` | крафт предмета |
| `smelt_item` | `item` | выплавка/добыча из печи |
| `break_block` | `block` | блок сломан |
| `kill_entity` | `entity` | моб убит |
| `breed_animal` | `entity` (необяз.) | разведено животное (любое или конкретное) |
| `shear_sheep` | — | овца пострижена |
| `villager_trade` | — | сделка с жителем |

Виды выше — контракт v1 для `POST /api/quests`. Полный движок определений
(файлы датапака / папка мира `<world>/trakt/quests/` / builtin-пак,
см. `GET /api/quest-definitions`) понимает **19 видов**: 8 выше + F2b.1
`harvest_hive`, `visit_villagers`, `recolor_collar`, `catch_fish` + F2b.2
`tame_animal` (приручить, фильтр по `entity`), `enchant_item` (зачаровать на
наковальне, фильтр по `item`), `brew_potion` (сварить зелье), `visit_village`
(посетить уникальные деревни), `escort_caravan` (эскорт конвоя гильдии),
`deliver_item` (доставить груз по контракту гильдии, фильтр по `item`),
`sleep_in_bed` (выспаться, ванильная статистика).

Любой цели файла определений можно добавить модификаторы контекста:
`"modifiers": ["at_night", "in_biome:minecraft:dark_forest"]` — прогресс
засчитывается только ночью и в указанном биоме. Правила: `at_night`/`raining`
объединяются по И; несколько `in_biome:<id|#тег>` — по ИЛИ; ночь — окно
внутриигровых часов 13000–23000 (как контракты паломников). Панель может
встретить эти виды и модификаторы в выводе реестра определений и в
`GET /api/quest-definitions/{id}`; для POST-кастомов v1 они пока недоступны.

Завершение **автоматическое** (цель выполнена → награда сразу, сообщение в чат,
событие `quest_completed` c `cycle=custom`). Сдача Квестодателю не нужна.

### PATCH /api/quests/custom/{id} — изменить
Частичное тело: `{"enabled": false}` — вкл/выкл; или любые из `title/goal/rewards/repeat`
(они заменяются целиком, валидация как при создании).

### DELETE /api/quests/custom/{id} — удалить
Builtin (`daily/...`, `weekly/...`) нельзя менять/удалять — только читать (получите 400).

### GET /api/quest-definitions — реестр определений (F1b)
Единый реестр определений квестов — 4 слоя с приоритетами: **датапак > папка мира
(`<world>/trakt/quests/`) > legacy-конфиг панели (`config/trakt/api/quests/`)
> builtin-пак**. Одна строка на действующее определение; `source` — слой, который выиграл;
`enabled:false` в высшем слое глушит нижние (override).

```json
{"count": 13, "problems": 0, "definitions": [
  {"id": "wheat_harvest", "source": "builtin", "cycle": "daily", "enabled": true,
   "title": "quest.trakt.daily.wheat.title", "completion_mode": "questmaster",
   "repeat": "daily", "goals": 2,
   "rewards": "{\"silvermarks\":3,\"levels\":2,\"reputation_track\":\"FARMING\",\"reputation_amount\":10,\"items\":[]}",
   "extra_choices": 2, "turn_in_items": 2}
]}
```

- `cycle`: `daily` (id совпадает со слотом квеста дня) или `custom`.
- `title`: инлайн-текст в `default_locale`, либо lang-ключ (builtin-пак).
- `rewards`: v1-объект наград строкой; `silvermarks` — серебро (§6, валюта v2).
- `extra_choices` / `turn_in_items`: счётчики, присутствуют только когда есть.
- `problems`: сколько файлов не прошли валидацию при последнем перечитывании
  (детали — в журнале сервера и в игре `/trakt quests list`).

### GET /api/questmasters — где стоят квестодатели (F6c)
Мировой список осевших в деревнях и приглашённых на дворы квестодателей
(переживает рестарты: settled — из биндингов F6a, invited — из реестра F6c).

```json
{"settled_count": 2, "invited_count": 1, "settled": [
  {"village_key": "minecraft:overworld:320:-224", "dimension": "minecraft:overworld",
   "entity_uuid": "…", "x": 330, "y": 70, "z": -215}
], "invited": [
  {"entity_uuid": "…", "player_uuid": "…", "player_name": "VinylRUS",
   "yard_x": 1180, "yard_y": 64, "yard_z": -42}
]}
```

- `settled[].village_key` — `dim:x:z` (центр структуры деревни).
- `invited[]` — квестодатели, переселённые sneak-use на двор игрока (F6b).
- Позиции — точки спавна/двора; сами сущности могут быть выгружены с чанками.

### GET /api/quest-definitions/{id} — одно определение
Полный JSON в авторской форме (round-trip — как файл) плюс метаданные слоя:

```json
{"id": "wheat_harvest", "source": "builtin", "cycle": "daily", "enabled": true,
 "file": "data/trakt/quests/wheat_harvest.json", "definition": { ...авторский JSON... }}
```

404 — неизвестный id. Награды builtin-пака (F1b): `rewards` — базовая выплата
(серебро/уровни/репутация, до 2 предметов), `extra_choices` — выбор пакета поверх
базы (id/серебро/предметы, опциональный `gate: "diamonds"` — прогрессионный гейт).

### Игровая сторона кастомов
- `/trakt custom` — список доступных + активный с прогрессом
- `/trakt custom accept <id>` — принять (один активный одновременно)
- `/trakt custom abandon` — отменить
- Прогресс дублируется actionbar-ом над хотбаром.

Ограничения v1 (сознательные): кастомы не участвуют в выборке дейликов у Квестодателя,
нет этапных целей (только одна цель), нет шаринга пати. Это задел на Phase 3 full.

## 10. Известные ограничения и планы

- Спец-квесты (5 историй реликвий) и сюжетные арки пока не раскрыты отдельными эндпоинтами —
  только счётчики в карточке игрока. Добавим по запросу.
- `GET /api/villages` — персональные сети игроков, не «глобальные деревни сервера»:
  в моде деревни per-player by design.
- Экономика read-only; ручные операции/казна/налоги — Phase 4 (после аудит-лога).
- Write-эндпоинтов про игровой процесс нет (только квесты и линки) — принципиально.
- Нет WebSocket: транспорт A — поллинг `after_id`, транспорт B — пуш батчами
  (для панели этого достаточно, WS — Phase 5).

## 11. Быстрый старт для бота (чек-лист)

> Полный фикс-лист текущего состояния интеграции — §12 (обновляется по мере Волны 1).

0. **Выбрать транспорт.** Порты разрешены → A: базовый URL `http://ip:8790` + `api_token`.
   Порты запрещены (exarathon) → B: развернуть гейтвей из §4.1 на своём FastAPI,
   забрать у владельца сервера `gateway_token` + `server_id` + включённый `transport=agent`.
1. Прочитать токен: `/trakt api status` / `/trakt api token show` в консоли сервера (или файл api.properties).
2. Транспорт A: `GET /api/health` — проверить связь; 401 = неверный токен.
   Транспорт B: дождаться первого `GET /trakt/commands` (это и есть health-check) и отправить
   в ответ команду `{"id":1,"method":"GET","path":"/api/health"}`.
3. Забрать события: A — `GET /api/events?after_id=0`; B — подождать первый POST /trakt/events
   и сохранить `acked_event_id` как курсор.
4. Цикл 30–60 сек (A: поллинг; B: само приходит, только обрабатывайте) — на основе
   событий посты/уведомления/ачивки.
5. `/linkmc` → `POST /api/links/claim` (в B — через команду) → сохранить пару (discord_id ↔ mc_uuid).
6. Страницы панели: players, villages, caravans, leaderboards, quests — по эндпоинтам §5–§9.
7. Отдельная кнопка/раздел «Квесты»: список (GET) + форма создания (POST) + вкл/выкл (PATCH).

## 12. Статус интеграции панели и инфраструктуры (фикс-лист)

> Дата: 2026-10-26. Всё, что нужно починить/учесть на стороне панели (DeeBeelkin)
> и инфраструктуры при возвращении к интеграции. Секция обновляется по мере
> движения Волны 1 (RELEASE_PLAN.md §8) — строки «запланировано» заменяются
> на фактические контракты здесь же.

### 12.1. Гейтвей панели: реестр server_id (блокер на стороне панели)

- Панель `dibilkis.bothost.tech` (uvicorn/FastAPI) на **любые** запросы гейтвея
  (§4.1) отвечает `403 {"error":"forbidden","message":"unknown server_id 'vsaujucml1jseqb3'"}`
  — слой реестра server_id проверяется ДО Bearer (строже эталона
  `tools/gateway_reference.py`, где только Bearer → 401).
- `server_id=vsaujucml1jseqb3` выдан самой панелью и вписан в конфиг мода вручную,
  но в реестре панели не значится (либо реестр был в памяти контейнера BotHost
  и умер вместе с ним).
- **Фикс на стороне панели (одно из двух):**
  1. зарегистрировать пару `server_id=vsaujucml1jseqb3` + `gateway_token`
     (токен — `/trakt api token show` на сервере, либо файл `api.properties`); или
  2. убрать слой реестра server_id и следовать контракту §4.1 (Bearer → 401).
- После правки панели перезапуск MC-сервера **не нужен**: агент ретраит
  с бэкоффом 1→60 с и подключится сам в течение минуты.

### 12.2. CI репозитория мода (блокер до открытия репо — решение владельца)

- GitHub Actions приватного репо мода не запускаются: квота Actions для private-репо
  исчерпана, раннер не назначается (раны падают за ~3 с, шагов и логов нет).
- **Решение владельца (2026-10-26): за CI не платить.** Публичные репо получают
  Actions бесплатно — CI оживёт сам на этапе R2 (открытие репо, RELEASE_PLAN §3),
  воркфлоу и exec-биты уже починены (коммит c0b23ce).
- До открытия источником правды остаётся **локальная сборка** `./gradlew build`
  в `Trakt/0.1-26.3/`; артефакт доставляется на сервер вручную.
- Панель это не затрагивает: сборка — инфраструктура репо, не API.

### 12.3. Валюта v2 (мини-контракт для панельной нейросети)

Изменения уже в API (этап F7a), панель должна учесть при отображении денег:

- Внутренняя единица мода — **медная путевая монета** (D7): 1 серебряная = 100 медных,
  1 золотая = 10 000 медных. v1-поля (`balance`, `money_supply`, `escrow`,
  `income_today`) отдаются **в серебряных** — панель не ломается, цифры игрока
  визуально совпадают с 2.5.
- `currency_unit` в `/api/economy/summary` описывает лестницу:
  `"waycoin (1 gold = 100 silver = 10000 copper); v1 fields in silver"`.
- Авторское поле квестов `rewards.silvermarks` — по-прежнему серебро.
- **Запланировано (F7c, этап 13 Волны 1):** канонические поля `waycoins` (медь) и
  `copper|silver|gold` рядом с v1-алиасами — панель сможет рисовать кошелёк тремя
  ярусами, как игровой UI (золотая/серебряная/медная).
- Событие `caravan_arrived`: `data.reward` теперь **маржа 15–40%** от стоимости
  груза (D8) + транспортный сбор, а не полная стоимость товара — цифры в
  уведомлениях бота станут меньше, это ожидаемо, а не баг.

### 12.4. Новое в API после 2.5 (что панель должна поддержать)

| Что | Где | Статус |
|---|---|---|
| Событие `quest_definition_changed` | §7 (после `/reload` и `/trakt quests reload`) | F1a, уже в API |
| Режим наград `mode:choice_of` + опции | §9, GET кастомного квеста | F2a, уже в API |
| Снос призыва квестмастера командой | REST не затронут (эндпоинтов призыва не было) | F6a, уже в моде |
| Квестмастера селятся в деревнях сами | событие `questmaster_invited` — уже в API (F6b); `questmaster_settled` — уже в API (F6c); `GET /api/questmasters` (settled + invited) — уже в API (F6c) | ✅ F6c сделан 2026-10-26 |
| Реестр определений квестов (4 слоя, override/enabled) | §9.1: `GET /api/quest-definitions`(+`/{id}`), в игре `/trakt quests list`; награды daily теперь в JSON (rewards + extra_choices) | F1b, уже в API |
| Новые виды целей + модификаторы контекста | не в POST v1 — видны в реестре определений §9 (таблица видов: 19 kinds; `modifiers` at_night/raining/in_biome у целей) | F2b, уже в моде (2026-10-26) |
| Релеи между игроками (терминал→терминал, эскроу) | события `relay_sent` / `relay_delivered` / `relay_returned` (§7) уже в API; `relay_robbed` — F3c; `GET /api/relays` — F7c | 🔶 F3b сделан 2026-10-26 (MVP: терминал relay_post, укладка≤3 стаков, тариф=сжигание, таймауты 3/7 дней, возвраты) |
