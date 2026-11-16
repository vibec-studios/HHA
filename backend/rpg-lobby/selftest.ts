// Selbsttest: startet zwei Clients gegen einen laufenden Server (Standard ws://127.0.0.1:18080/ws),
// spielt Lobby, Start und einen Tunnel-Strom in beide Richtungen durch. Aufruf: bun run selftest.ts [url]
const url = process.argv[2] ?? "ws://127.0.0.1:18080/ws";

function client(name: string) {
  const ws = new WebSocket(url);
  ws.binaryType = "arraybuffer";
  const inbox: any[] = [];
  const bins: Uint8Array[] = [];
  ws.onmessage = (e) => {
    if (typeof e.data === "string") inbox.push(JSON.parse(e.data));
    else bins.push(new Uint8Array(e.data as ArrayBuffer));
  };
  const opened = new Promise<void>((r) => (ws.onopen = () => r()));
  return {
    ws, inbox, bins, opened,
    send: (m: any) => ws.send(JSON.stringify(m)),
    async wait(t: string, ms = 3000) {
      const end = Date.now() + ms;
      while (Date.now() < end) {
        const i = inbox.findIndex((m) => m.t === t);
        if (i >= 0) return inbox.splice(i, 1)[0];
        await Bun.sleep(10);
      }
      throw new Error(`${name}: timeout waiting for ${t}; inbox=${JSON.stringify(inbox)}`);
    },
    async waitBin(ms = 3000) {
      const end = Date.now() + ms;
      while (Date.now() < end) {
        if (bins.length) return bins.shift()!;
        await Bun.sleep(10);
      }
      throw new Error(`${name}: timeout waiting for binary`);
    },
  };
}

const host = client("host");
const guest = client("guest");
await host.opened;
await guest.opened;
host.send({ t: "hello", name: "Host", uuid: "11111111-1111-1111-1111-111111111111", addons: [{ id: "hha_blood", name: "Blood", rpg: true }] });
guest.send({ t: "hello", name: "Guest", uuid: "22222222-2222-2222-2222-222222222222", addons: [] });
await host.wait("welcome");
await guest.wait("welcome");
host.send({ t: "create", path: "heaven", addons: ["hha_blood"] });
const lobby = await host.wait("lobby");
console.log("code", lobby.code);
guest.send({ t: "join", code: lobby.code.toLowerCase() });
const seen = await guest.wait("lobby");
if (seen.members.length !== 2) throw new Error("members");
guest.send({ t: "ready", ready: true });
await host.wait("lobby");
host.send({ t: "start" });
await guest.wait("starting");
host.send({ t: "open" });
await guest.wait("go");
guest.send({ t: "tcp_open", sid: 77 });
const open = await host.wait("tcp_open");
if (open.sid !== 77) throw new Error("sid");
host.send({ t: "tcp_ok", sid: 77 });
await guest.wait("tcp_ok");
const frame = new Uint8Array([0, 0, 0, 77, 1, 2, 3]);
guest.ws.send(frame);
const got = await host.waitBin();
if (got.length !== 7 || got[6] !== 3) throw new Error("bin to host");
host.ws.send(new Uint8Array([0, 0, 0, 77, 9, 9]));
const back = await guest.waitBin();
if (back[4] !== 9) throw new Error("bin to guest");
guest.send({ t: "tcp_close", sid: 77 });
await host.wait("tcp_close");
host.ws.close();
await guest.wait("closed");
console.log("SELFTEST OK");
process.exit(0);
