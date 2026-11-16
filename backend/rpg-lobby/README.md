# HHA RPG-Lobby

Kleiner WebSocket-Dienst für den RPG-Modus von H&H Armors: Lobbys mit sechsstelligem Beitrittscode und ein Relay, das
die Minecraft-Verbindung der Mitspieler zum Host tunnelt. **Hier läuft keine Welt.** Der Host spielt lokal (seine
Welt wird „für LAN geöffnet“, nur auf 127.0.0.1), die Mods auf beiden Seiten reichen die Bytes über `/ws` weiter.

- `GET /health` → `ok`, `GET /stats` → Zahl der Lobbys, `GET /ws` → WebSocket (Protokoll im Kopf von `server.ts`).
- Umgebungsvariablen: `PORT` (8080), `MAX_MEMBERS` (16; empfohlen sind 4).
- Lokal: `bun run server.ts`. Docker: `docker build -t hha-rpg-lobby . && docker run -p 8080:8080 hha-rpg-lobby`.
- Der Mod liest die Adresse aus der Client-Config (`rpg_backend`, Standard siehe `RpgBackend.kt`).
