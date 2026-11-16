// HHA RPG-Lobby: Lobbys mit Beitrittscode und ein Relay, das die Minecraft-Verbindung der Mitspieler zum Host tunnelt.
//
// Keine Welt läuft hier. Der Host spielt seine Welt lokal (Integrated Server, „Für LAN öffnen" auf 127.0.0.1);
// Mitspieler verbinden ihr Minecraft mit einem lokalen Port ihres Mods, der Mod schickt die Bytes über diesen
// WebSocket, wir reichen sie an den Host weiter, dessen Mod sie an seinen LAN-Port gibt. Das Relay kennt nur
// Lobbys und Datenströme, es öffnet selbst nie eine Verbindung.
//
// Protokoll (eine WebSocket-Verbindung je Spieler, Pfad /ws):
//   Text-Frames sind JSON mit Feld "t". Binär-Frames sind Tunnel-Daten: 4 Byte Strom-ID (big endian) + Nutzdaten.
//   Client -> Server
//     hello  {name, uuid, version, addons:[{id,name,rpg}]}
//     create {path, addons:[id]}            Lobby anlegen, Absender wird Host
//     join   {code}                          Lobby beitreten
//     set    {path?, addons?}                nur Host: Weg und Addons ändern
//     ready  {ready}                         Mitglied: bereit ja/nein
//     kick   {uuid}                          nur Host
//     start  {}                              nur Host: alle sehen die Startanimation
//     open   {}                              nur Host: Welt läuft, Mitspieler dürfen verbinden
//     leave  {}
//     tcp_open  {sid}    Mitglied: neue Verbindung (sid wählt das Mitglied, 1..2^31)
//     tcp_ok/tcp_fail {sid}   Host: Antwort
//     tcp_close {sid}    beide Seiten
//   Server -> Client
//     welcome {}, lobby {...}, error {msg}, starting {}, go {}, closed {reason}, kicked {}, tcp_open {sid, from},
//     tcp_ok {sid}, tcp_fail {sid}, tcp_close {sid}

type Addon = { id: string; name: string; rpg: boolean };

type Conn = {
  ws: Bun.ServerWebSocket<Conn>;
  id: number;
  name: string;
  uuid: string;
  version: string;
  addons: Addon[];
  ready: boolean;
  lobby: Lobby | null;
  ip: string;
  /** Tunnel-Ströme dieses Mitglieds (sid) */
  streams: Set<number>;
};

type Lobby = {
  code: string;
  host: Conn;
  members: Conn[];
  path: string;
  addons: string[];
  state: "lobby" | "starting" | "open";
  created: number;
  touched: number;
  /** sid -> Mitglied, dem der Strom gehört */
  streams: Map<number, Conn>;
};

const PORT = Number(process.env.PORT ?? 8080);
const MAX_MEMBERS = Number(process.env.MAX_MEMBERS ?? 16);
const RECOMMENDED = 4;
const LOBBY_IDLE_MS = 3 * 60 * 60 * 1000;
const MAX_FRAME = 2 * 1024 * 1024;
const CODE_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

const lobbies = new Map<string, Lobby>();
let nextConn = 1;
const createdByIp = new Map<string, number[]>();

function newCode(): string {
  for (let attempt = 0; attempt < 1000; attempt++) {
    let code = "";
    for (let i = 0; i < 6; i++) code += CODE_CHARS[Math.floor(Math.random() * CODE_CHARS.length)];
    if (!lobbies.has(code)) return code;
  }
  throw new Error("no free code");
}

function send(c: Conn, msg: Record<string, unknown>) {
  try {
    c.ws.send(JSON.stringify(msg));
  } catch {
    /* Verbindung schon zu */
  }
}

function lobbyView(l: Lobby) {
  return {
    t: "lobby",
    code: l.code,
    host: l.host.uuid,
    path: l.path,
    addons: l.addons,
    state: l.state,
    recommended: RECOMMENDED,
    max: MAX_MEMBERS,
    members: [l.host, ...l.members].map((m) => ({
      uuid: m.uuid,
      name: m.name,
      host: m === l.host,
      ready: m === l.host ? true : m.ready,
      addons: m.addons.map((a) => a.id),
    })),
  };
}

function broadcast(l: Lobby, msg: Record<string, unknown>, except?: Conn) {
  for (const m of [l.host, ...l.members]) if (m !== except) send(m, msg);
}

function publish(l: Lobby) {
  l.touched = Date.now();
  broadcast(l, lobbyView(l));
}

function closeStream(l: Lobby, sid: number, notify: Conn | null) {
  const owner = l.streams.get(sid);
  if (!owner) return;
  l.streams.delete(sid);
  owner.streams.delete(sid);
  if (notify) send(notify, { t: "tcp_close", sid });
}

function leave(c: Conn, reason = "left") {
  const l = c.lobby;
  if (!l) return;
  c.lobby = null;
  if (l.host === c) {
    // Host weg: Lobby zu, alle Ströme zu
    for (const m of l.members) {
      m.lobby = null;
      m.streams.clear();
      send(m, { t: "closed", reason: "host_left" });
    }
    lobbies.delete(l.code);
    return;
  }
  l.members = l.members.filter((m) => m !== c);
  for (const sid of [...c.streams]) closeStream(l, sid, l.host);
  c.streams.clear();
  if (reason === "kicked") send(c, { t: "kicked" });
  publish(l);
}

function rateLimited(ip: string): boolean {
  const now = Date.now();
  const list = (createdByIp.get(ip) ?? []).filter((t) => now - t < 10 * 60 * 1000);
  list.push(now);
  createdByIp.set(ip, list);
  return list.length > 20;
}

function sanitizeAddons(list: unknown): string[] {
  if (!Array.isArray(list)) return [];
  return list.filter((x) => typeof x === "string" && /^[a-z0-9_.-]{1,64}$/.test(x)).slice(0, 32) as string[];
}

function handleText(c: Conn, raw: string) {
  let msg: any;
  try {
    msg = JSON.parse(raw);
  } catch {
    return send(c, { t: "error", msg: "bad_json" });
  }
  const l = c.lobby;
  switch (msg.t) {
    case "hello": {
      c.name = String(msg.name ?? "Player").slice(0, 32);
      c.uuid = String(msg.uuid ?? "").slice(0, 40);
      c.version = String(msg.version ?? "").slice(0, 32);
      c.addons = Array.isArray(msg.addons)
        ? msg.addons.slice(0, 32).map((a: any) => ({ id: String(a.id).slice(0, 64), name: String(a.name ?? a.id).slice(0, 64), rpg: !!a.rpg }))
        : [];
      return send(c, { t: "welcome", recommended: RECOMMENDED, max: MAX_MEMBERS });
    }
    case "create": {
      if (!c.uuid) return send(c, { t: "error", msg: "no_hello" });
      if (rateLimited(c.ip)) return send(c, { t: "error", msg: "rate_limited" });
      leave(c);
      const lobby: Lobby = {
        code: newCode(),
        host: c,
        members: [],
        path: String(msg.path ?? "heaven").slice(0, 64),
        addons: sanitizeAddons(msg.addons),
        state: "lobby",
        created: Date.now(),
        touched: Date.now(),
        streams: new Map(),
      };
      lobbies.set(lobby.code, lobby);
      c.lobby = lobby;
      return publish(lobby);
    }
    case "join": {
      if (!c.uuid) return send(c, { t: "error", msg: "no_hello" });
      const code = String(msg.code ?? "").toUpperCase().replace(/[^A-Z0-9]/g, "");
      const target = lobbies.get(code);
      if (!target) return send(c, { t: "error", msg: "not_found" });
      if (target.state !== "lobby" && target.state !== "open") return send(c, { t: "error", msg: "already_started" });
      if (target.members.length + 1 >= MAX_MEMBERS) return send(c, { t: "error", msg: "full" });
      if ([target.host, ...target.members].some((m) => m.uuid === c.uuid)) return send(c, { t: "error", msg: "already_in" });
      leave(c);
      c.ready = false;
      c.lobby = target;
      target.members.push(c);
      publish(target);
      // Läuft die Welt schon, darf der Neue direkt verbinden.
      if (target.state === "open") send(c, { t: "go" });
      return;
    }
    case "set": {
      if (!l || l.host !== c || l.state !== "lobby") return;
      if (typeof msg.path === "string") l.path = msg.path.slice(0, 64);
      if (msg.addons !== undefined) l.addons = sanitizeAddons(msg.addons);
      for (const m of l.members) m.ready = false;
      return publish(l);
    }
    case "ready": {
      if (!l || l.host === c) return;
      c.ready = !!msg.ready;
      return publish(l);
    }
    case "kick": {
      if (!l || l.host !== c) return;
      const victim = l.members.find((m) => m.uuid === msg.uuid);
      if (victim) leave(victim, "kicked");
      return;
    }
    case "start": {
      if (!l || l.host !== c || l.state !== "lobby") return;
      l.state = "starting";
      broadcast(l, { t: "starting", path: l.path, addons: l.addons });
      return publish(l);
    }
    case "open": {
      if (!l || l.host !== c) return;
      l.state = "open";
      for (const m of l.members) send(m, { t: "go" });
      return publish(l);
    }
    case "leave":
      return leave(c);
    case "tcp_open": {
      if (!l || l.host === c || l.state !== "open") return send(c, { t: "tcp_fail", sid: msg.sid });
      const sid = Number(msg.sid);
      if (!Number.isInteger(sid) || sid <= 0 || sid > 0x7fffffff || l.streams.has(sid)) return send(c, { t: "tcp_fail", sid });
      l.streams.set(sid, c);
      c.streams.add(sid);
      return send(l.host, { t: "tcp_open", sid, from: c.uuid, name: c.name });
    }
    case "tcp_ok":
    case "tcp_fail": {
      if (!l || l.host !== c) return;
      const owner = l.streams.get(Number(msg.sid));
      if (!owner) return;
      send(owner, { t: msg.t, sid: Number(msg.sid) });
      if (msg.t === "tcp_fail") closeStream(l, Number(msg.sid), null);
      return;
    }
    case "tcp_close": {
      if (!l) return;
      const sid = Number(msg.sid);
      const owner = l.streams.get(sid);
      if (!owner) return;
      if (c === l.host) closeStream(l, sid, owner);
      else if (owner === c) closeStream(l, sid, l.host);
      return;
    }
    case "ping":
      return send(c, { t: "pong" });
    default:
      return send(c, { t: "error", msg: "unknown" });
  }
}

function handleBinary(c: Conn, data: Uint8Array) {
  const l = c.lobby;
  if (!l || data.length < 4 || data.length > MAX_FRAME) return;
  const sid = ((data[0] << 24) | (data[1] << 16) | (data[2] << 8) | data[3]) >>> 0;
  const owner = l.streams.get(sid);
  if (!owner) return;
  // Mitglied -> Host oder Host -> Mitglied, nur auf eigenen Strömen
  if (c === l.host) owner.ws.send(data);
  else if (owner === c) l.host.ws.send(data);
}

const server = Bun.serve<Conn, undefined>({
  port: PORT,
  fetch(req, srv) {
    const url = new URL(req.url);
    if (url.pathname === "/health") return new Response("ok");
    if (url.pathname === "/stats") return Response.json({ lobbies: lobbies.size });
    if (url.pathname === "/ws") {
      const ip = req.headers.get("x-forwarded-for")?.split(",")[0]?.trim() ?? srv.requestIP(req)?.address ?? "?";
      const ok = srv.upgrade(req, {
        data: { id: nextConn++, name: "", uuid: "", version: "", addons: [], ready: false, lobby: null, ip, streams: new Set() } as unknown as Conn,
      });
      return ok ? undefined : new Response("upgrade failed", { status: 400 });
    }
    return new Response("HHA RPG lobby", { status: 200 });
  },
  websocket: {
    maxPayloadLength: MAX_FRAME,
    idleTimeout: 120,
    perMessageDeflate: false,
    open(ws) {
      ws.data.ws = ws;
    },
    message(ws, message) {
      if (typeof message === "string") handleText(ws.data, message);
      else handleBinary(ws.data, new Uint8Array(message));
    },
    close(ws) {
      leave(ws.data);
    },
  },
});

setInterval(() => {
  const now = Date.now();
  for (const [code, l] of lobbies) {
    if (now - l.touched > LOBBY_IDLE_MS && l.state === "lobby") {
      broadcast(l, { t: "closed", reason: "idle" });
      for (const m of [l.host, ...l.members]) m.lobby = null;
      lobbies.delete(code);
    }
  }
}, 60_000);

console.log(`HHA RPG lobby on :${server.port}`);
