#!/usr/bin/env python3
"""Trakt: эталонный гейтвей для транспорта «агент» (Transport B).

Это одновременно:
1) РЕФЕРЕНСНАЯ РЕАЛИЗАЦИЯ серверной стороны протокола для панели/бота
   (см. rest_api.md, раздел «Транспорт B»). Разверните эти три эндпоинта
   в своём FastAPI-приложении — и мод подключится к вам сам.
2) ТЕСТОВЫЙ СТЕНД: `python3 gateway_reference.py --auto` прогоняет полный
   e2e-сценарий против запущенного dev-сервера мода.

Протокол (мод — инициатор всех соединений, порты открывать НЕ нужно):

  GET  /trakt/commands?after_id=N&server_id=..&online=..&mod_version=..
       -> {"commands":[{"id":1,"method":"GET","path":"/api/players",
                        "query":"limit=50","body":{...}}],
           "last_command_id": 1}
  POST /trakt/results    {"server_id":"..","results":[{"command_id":1,"status":200,"body":{...}}]}
       -> {"ok":true}
  POST /trakt/events     {"server_id":"..","first_event_id":1,"last_event_id":9,
                        "events":[{...}, ...]}
       -> {"acked_event_id":9}

Аутентификация: заголовок `Authorization: Bearer <gateway_token>` — тот же
токен, что записан в config/trakt/api.properties (gateway_token)
на стороне сервера Minecraft.

Семантика офлайна: если GET /trakt/commands не приходил дольше
max(15 c, 3 x poll_seconds) — сервер Minecraft спит или выключен.

Запуск стенда:  python3 gateway_reference.py --port 8791 --token SECRET --auto
Ручной режим:   python3 gateway_reference.py --port 8791 --token SECRET
  команды REPL: get /api/players | post /api/quests '{...}' | patch /api/quests/x '{...}'
                del /api/links/123 | stats | events | quit
"""
from __future__ import annotations

import argparse
import json
import threading
import time
import urllib.parse
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

# --------------------------------------------------------------------- состояние

LOCK = threading.Lock()
COMMANDS: list[dict] = []      # очередь команд к моду
NEXT_COMMAND_ID = 1
RESULTS: dict[int, dict] = {}  # command_id -> результат от мода
EVENTS: list[dict] = []        # принятые события (дедупликация по id)
POLL_COUNT = 0
FIRST_POLL_AT: float | None = None
LAST_POLL_AT: float | None = None
AUTO_STATE = {"scheduled": False, "summary_printed": False}

# --------------------------------------------------------------------- HTTP слой


class Gateway(BaseHTTPRequestHandler):
    token = "dev-token"
    protocol_version = "HTTP/1.1"

    # -- служебное ------------------------------------------------------

    def log_message(self, fmt, *args):  # тише в логах
        pass

    def _reply(self, status: int, payload: dict) -> None:
        raw = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(raw)))
        self.end_headers()
        self.wfile.write(raw)

    def _authorized(self) -> bool:
        header = self.headers.get("Authorization", "")
        return header == f"Bearer {self.token}"

    def _read_json(self) -> dict:
        length = int(self.headers.get("Content-Length", "0") or 0)
        if length <= 0 or length > 2 * 1024 * 1024:
            return {}
        raw = self.rfile.read(length)
        try:
            value = json.loads(raw.decode("utf-8"))
            return value if isinstance(value, dict) else {}
        except ValueError:
            return {}

    # -- эндпоинты ------------------------------------------------------

    def do_GET(self):
        global POLL_COUNT, FIRST_POLL_AT, LAST_POLL_AT
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path != "/trakt/commands":
            self._reply(404, {"error": "not_found"})
            return
        if not self._authorized():
            self._reply(401, {"error": "unauthorized"})
            return
        query = urllib.parse.parse_qs(parsed.query)
        after_id = int(query.get("after_id", ["0"])[0] or 0)
        with LOCK:
            POLL_COUNT += 1
            if FIRST_POLL_AT is None:
                FIRST_POLL_AT = time.time()
            LAST_POLL_AT = time.time()
            pending = [c for c in COMMANDS if c["id"] > after_id]
            last_id = COMMANDS[-1]["id"] if COMMANDS else after_id
        print(f"[poll #{POLL_COUNT}] after_id={after_id} online={query.get('online')} "
              f"mod={query.get('mod_version')} -> {len(pending)} command(s)", flush=True)
        self._reply(200, {"commands": pending, "last_command_id": last_id})
        maybe_schedule_auto()

    def do_POST(self):
        parsed = urllib.parse.urlsplit(self.path)
        if parsed.path not in ("/trakt/results", "/trakt/events"):
            self._reply(404, {"error": "not_found"})
            return
        if not self._authorized():
            self._reply(401, {"error": "unauthorized"})
            return
        body = self._read_json()
        if parsed.path == "/trakt/results":
            count = 0
            with LOCK:
                for result in body.get("results", []):
                    cid = result.get("command_id")
                    if isinstance(cid, int):
                        RESULTS[cid] = result
                        count += 1
            print(f"[results] +{count} (всего {len(RESULTS)})", flush=True)
            self._reply(200, {"ok": True})
            check_auto_results()
            return
        # /trakt/events
        events = body.get("events", [])
        acked = 0
        with LOCK:
            known = {e["id"] for e in EVENTS}
            for event in events:
                if isinstance(event, dict) and event.get("id") not in known:
                    EVENTS.append(event)
                    known.add(event.get("id"))
                    print(f"[event] #{event.get('id')} {event.get('type')} "
                          f"{json.dumps(event.get('data', {}), ensure_ascii=False)[:120]}", flush=True)
            if events:
                acked = max(int(e.get("id", 0)) for e in events)
        self._reply(200, {"acked_event_id": acked})
        check_auto_events()

# --------------------------------------------------------------------- авто-режим


def enqueue(method: str, path: str, query: str | None = None, body: dict | None = None) -> None:
    global NEXT_COMMAND_ID
    with LOCK:
        command = {"id": NEXT_COMMAND_ID, "method": method, "path": path}
        if query:
            command["query"] = query
        if body is not None:
            command["body"] = body
        COMMANDS.append(command)
        NEXT_COMMAND_ID += 1


def maybe_schedule_auto() -> None:
    if not AUTO_OPTIONS.auto or AUTO_STATE["scheduled"]:
        return
    AUTO_STATE["scheduled"] = True
    threading.Thread(target=run_auto_script, daemon=True).start()


QUEST_JSON = {
    "id": "smoke_gather",
    "title": {"en_us": "Smoke Gather", "ru_ru": "Дымный сбор"},
    "offer": {"ru_ru": "Тестовый квест из автотеста гейтвея."},
    "goal": {"kind": "collect_item", "item": "minecraft:wheat", "amount": 10},
    "rewards": {"silvermarks": 5, "levels": 1,
                "items": [{"item": "minecraft:emerald", "count": 1}]},
    "repeat": "daily",
}

AUTO_COMMANDS = [
    ("GET", "/api/health", None, None, [200], "health"),
    ("GET", "/api", None, None, [200], "index"),
    ("GET", "/api/players", None, None, [200], "players"),
    ("GET", "/api/quests", None, None, [200], "quest list"),
    ("POST", "/api/quests", None, QUEST_JSON, [200, 409], "quest create"),
    ("POST", "/api/quests", None, {"id": "BAD ID", "goal": {"kind": "nope", "amount": -5}},
     [400], "quest create invalid -> 400"),
    ("PATCH", "/api/quests/custom/smoke_gather", None, {"enabled": False},
     [200, 404], "quest patch disable"),
    ("DELETE", "/api/quests/custom/smoke_gather", None, None,
     [200, 404], "quest delete"),
    ("GET", "/api/quests/custom/smoke_gather", None, None, [404], "quest deleted -> 404"),
    ("POST", "/api/links/claim", None, {"code": "000000", "discord_id": 123,
                                        "discord_username": "tester"},
     [404], "link claim unknown code -> 404"),
    ("GET", "/api/events", "after_id=0&limit=50", None, [200], "events pull"),
    ("GET", "/api/leaderboards/wealth", None, None, [200], "leaderboard"),
    ("GET", "/api/villages", None, None, [200], "villages"),
    ("GET", "/api/caravans", None, None, [200], "caravans"),
    ("GET", "/api/economy/summary", None, None, [200], "economy"),
]


def run_auto_script() -> None:
    # Держим паузу после первого опроса, чтобы мод успел штатно завершить цикл.
    time.sleep(3)
    print(f"[auto] ставлю в очередь {len(AUTO_COMMANDS)} команд", flush=True)
    for method, path, query, body, _expected, _title in AUTO_COMMANDS:
        enqueue(method, path, query, body)


def check_auto_results() -> None:
    if not AUTO_OPTIONS.auto:
        return
    expected_total = len(AUTO_COMMANDS)
    if len(RESULTS) >= expected_total:
        print("[auto] все результаты получены — можно останавливать сервер Minecraft",
              flush=True)


def check_auto_events() -> None:
    if not AUTO_OPTIONS.auto:
        return
    types = [e.get("type") for e in EVENTS]
    if "server_stopping" in types and not AUTO_STATE["summary_printed"]:
        AUTO_STATE["summary_printed"] = True
        print_summary()


def print_summary() -> None:
    expected_total = len(AUTO_COMMANDS)
    passed, failed = 0, 0
    print("\n===== AUTO SUMMARY =====", flush=True)
    # 1. результаты команд
    for i, (_m, _p, _q, _b, expected, title) in enumerate(AUTO_COMMANDS, start=1):
        result = RESULTS.get(i)
        if result is None:
            print(f"[FAIL] {title}: результат не получен")
            failed += 1
            continue
        status = result.get("status")
        if status in expected:
            passed += 1
            print(f"[PASS] {title} -> {status}")
        else:
            failed += 1
            print(f"[FAIL] {title} -> {status}, ожидалось {expected}: "
                  f"{json.dumps(result.get('body', {}), ensure_ascii=False)[:200]}")
    # 2. содержимое отдельных ответов
    if 1 in RESULTS:
        body = RESULTS[1].get("body", {})
        if body.get("status") == "ok":
            passed += 1
            print("[PASS] health.status == ok")
        else:
            failed += 1
            print(f"[FAIL] health.status != ok: {body}")
    # 3. события
    ids = [e.get("id") for e in EVENTS]
    if ids == sorted(ids) and len(ids) == len(set(ids)):
        passed += 1
        print(f"[PASS] события: {len(ids)} шт., id возрастают без дублей")
    else:
        failed += 1
        print(f"[FAIL] события: id не монотонны или есть дубли: {ids[:20]}")
    types = [e.get("type") for e in EVENTS]
    if "server_started" in types:
        passed += 1
        print("[PASS] событие server_started получено (push работает)")
    else:
        failed += 1
        print("[FAIL] server_started не получен")
    if "custom_quest_deleted" in types:
        passed += 1
        print("[PASS] событие custom_quest_deleted получено")
    else:
        failed += 1
        print("[FAIL] custom_quest_deleted не получен")
    if "server_stopping" in types:
        passed += 1
        print("[PASS] событие server_stopping получено (финальный flush работает)")
    else:
        failed += 1
        print("[FAIL] server_stopping не получен")
    print(f"===== RESULT: PASS={passed} FAIL={failed} =====", flush=True)
    if AUTO_OPTIONS.auto:
        threading.Thread(target=_exit_later, args=(1 if failed else 0,), daemon=True).start()


def _exit_later(code: int) -> None:
    time.sleep(1)
    import os
    import sys
    sys.stdout.flush()
    os._exit(code)

# --------------------------------------------------------------------- REPL-режим


def repl() -> None:
    print("Ручной режим. Команды: get PATH [QUERY] | post PATH {json} | patch PATH {json} "
          "| del PATH | stats | events | quit")
    while True:
        try:
            line = input("gw> ").strip()
        except EOFError:
            break
        if not line:
            continue
        parts = line.split(None, 2)
        cmd = parts[0].lower()
        if cmd == "quit":
            break
        if cmd == "stats":
            with LOCK:
                print(f"polls={POLL_COUNT} queued={len(COMMANDS)} "
                      f"results={len(RESULTS)} events={len(EVENTS)}")
            continue
        if cmd == "events":
            with LOCK:
                for event in EVENTS[-30:]:
                    print(f"  #{event['id']} {event['type']}")
            continue
        if cmd in ("get", "post", "patch", "del") and len(parts) >= 2:
            method = {"get": "GET", "post": "POST", "patch": "PATCH", "del": "DELETE"}[cmd]
            path = parts[1]
            query = None
            body = None
            rest = parts[2] if len(parts) > 2 else ""
            if method == "GET" and rest:
                query = rest
            elif rest:
                try:
                    body = json.loads(rest)
                except ValueError:
                    print("  не удалось разобрать JSON — команда не поставлена")
                    continue
            enqueue(method, path, query, body)
            print("  команда в очереди")
            continue
        print("  не понял; см. подсказку выше")

# --------------------------------------------------------------------- main


AUTO_OPTIONS = argparse.Namespace(auto=False)


def main() -> None:
    global AUTO_OPTIONS
    parser = argparse.ArgumentParser(description="Trakt gateway reference / test harness")
    parser.add_argument("--port", type=int, default=8791)
    parser.add_argument("--token", default="dev-token")
    parser.add_argument("--auto", action="store_true",
                        help="автотест: 15 команд + проверка событий; выход с кодом 0/1")
    args = parser.parse_args()
    AUTO_OPTIONS = args
    Gateway.token = args.token
    server = ThreadingHTTPServer(("0.0.0.0", args.port), Gateway)
    print(f"Gateway listening on 0.0.0.0:{args.port} (token: {args.token[:4]}...)"
          f"{' [AUTO]' if args.auto else ' [REPL]'}", flush=True)
    if args.auto:
        # авто-режим: подстраховочный выход по таймауту
        def watchdog():
            time.sleep(900)
            print("[auto] watchdog timeout (15 мин) — печатаю итог и выхожу", flush=True)
            print_summary()
            import os
            os._exit(1)
        threading.Thread(target=watchdog, daemon=True).start()
        server.serve_forever()
    else:
        threading.Thread(target=server.serve_forever, daemon=True).start()
        repl()


if __name__ == "__main__":
    main()
