#!/usr/bin/env node
// The public site's request queue: visitors ask for a deck or a matchup to be solved, and the
// author's machine collects the requests (site/requests.sh) and marks them done. No dependencies.
//
//   POST /api/requests              {kind: "deck"|"matchup", decks: [{name, cards: [3]}], note, website}
//   GET  /api/requests/queue        the public list: decks, kind, when and status (no notes)
//   GET  /api/requests              everything, notes too            (Authorization: Bearer TOKEN)
//   POST /api/requests/<id>         {status: waiting|solved|declined, reply}   (Authorization: Bearer TOKEN)
//
// Environment: PORT (default 4581), HOST (default 127.0.0.1: nginx proxies /api/requests to it),
// DATA_DIR (where requests.json lives; default ./data), TOKEN (required: the author's key),
// CARDS_FILE (the site's data/cards.json; when it's readable, cards must be on it).
"use strict";
const http = require("http");
const fs = require("fs");
const path = require("path");
const crypto = require("crypto");

const PORT = Number(process.env.PORT || 4581);
const HOST = process.env.HOST || "127.0.0.1";
const DATA_DIR = process.env.DATA_DIR || path.join(__dirname, "data");
const TOKEN = process.env.TOKEN || "";
const CARDS_FILE = process.env.CARDS_FILE || "";
const FILE = path.join(DATA_DIR, "requests.json");

const MAX_BODY = 8 * 1024;
const MAX_NAME = 40, MAX_CARD = 150, MAX_NOTE = 500, MAX_REPLY = 300;
const PER_IP_PER_DAY = 20, ALL_PER_DAY = 300, MAX_WAITING = 500, QUEUE_SHOWN = 100;
const STATUSES = ["waiting", "solved", "declined"];

if (!TOKEN || TOKEN.length < 20) {
  console.error("Set TOKEN (at least 20 characters): the key site/requests.sh uses to collect requests.");
  process.exit(1);
}

fs.mkdirSync(DATA_DIR, { recursive: true });
let requests = fs.existsSync(FILE) ? JSON.parse(fs.readFileSync(FILE, "utf8")) : [];

// Card names, reloaded when the site's export changes them.
let cards = null, cardsStamp = 0;
function cardSet() {
  if (!CARDS_FILE) return null;
  try {
    const stamp = fs.statSync(CARDS_FILE).mtimeMs;
    if (stamp !== cardsStamp) {
      const names = JSON.parse(fs.readFileSync(CARDS_FILE, "utf8"));
      cards = new Map(names.map((n) => [n.toLowerCase(), n]));
      cardsStamp = stamp;
    }
  } catch {
    // no list yet: names are checked again when the request is imported
  }
  return cards;
}

function save() {
  const tmp = FILE + ".tmp";
  fs.writeFileSync(tmp, JSON.stringify(requests, null, 1));
  fs.renameSync(tmp, FILE);
}

// Requests per visitor per day, by a hash of the address (addresses aren't stored).
const recent = new Map();
let day = "", today = 0;
function allowed(ip) {
  const d = new Date().toISOString().slice(0, 10);
  if (d !== day) { day = d; today = 0; recent.clear(); }
  const key = crypto.createHash("sha256").update(ip).digest("hex").slice(0, 16);
  const n = recent.get(key) || 0;
  if (n >= PER_IP_PER_DAY || today >= ALL_PER_DAY) return false;
  recent.set(key, n + 1);
  today++;
  return true;
}

const str = (v) => (typeof v === "string" ? v.trim() : "");
// Same decks, in any order of cards (and of decks, for a matchup), ignoring names.
const deckKey = (d) => d.cards.map((c) => c.toLowerCase()).sort().join("|");
const requestKey = (r) => r.kind + ":" + r.decks.map(deckKey).sort().join(" vs ");

function validate(body) {
  const problems = [];
  const kind = body && body.kind;
  if (kind !== "deck" && kind !== "matchup") return { problems: ["Ask for a deck or a matchup."] };
  const want = kind === "deck" ? 1 : 2;
  if (!Array.isArray(body.decks) || body.decks.length !== want) return { problems: [`A ${kind} needs ${want} deck${want > 1 ? "s" : ""}.`] };
  const known = cardSet();
  const decks = body.decks.map((d, i) => {
    const label = want > 1 ? (i ? "Second deck" : "First deck") : "The deck";
    const name = str(d && d.name).slice(0, MAX_NAME);
    const list = Array.isArray(d && d.cards) ? d.cards.map(str) : [];
    if (list.length !== 3 || list.some((c) => !c || c.length > MAX_CARD)) {
      problems.push(`${label} needs exactly three cards.`);
      return null;
    }
    const out = [];
    for (const c of list) {
      const real = known ? known.get(c.toLowerCase()) : c;
      if (!real) problems.push(`"${c}" isn't a Vintage-legal card.`);
      else out.push(real);
    }
    return { name, cards: out };
  });
  if (problems.length) return { problems };
  return { request: { kind, decks, note: str(body.note).slice(0, MAX_NOTE) } };
}

function send(res, status, data) {
  const body = JSON.stringify(data);
  res.writeHead(status, { "Content-Type": "application/json; charset=utf-8", "Cache-Control": "no-store" });
  res.end(body);
}

function authorized(req) {
  const got = Buffer.from(String(req.headers.authorization || ""));
  const want = Buffer.from("Bearer " + TOKEN);
  return got.length === want.length && crypto.timingSafeEqual(got, want);
}

function readBody(req) {
  return new Promise((resolve, reject) => {
    let size = 0;
    const chunks = [];
    req.on("data", (c) => {
      size += c.length;
      if (size > MAX_BODY) { reject(Object.assign(new Error("too big"), { status: 413 })); req.destroy(); return; }
      chunks.push(c);
    });
    req.on("end", () => {
      try { resolve(JSON.parse(Buffer.concat(chunks).toString("utf8") || "{}")); }
      catch { reject(Object.assign(new Error("bad json"), { status: 400 })); }
    });
    req.on("error", reject);
  });
}

const publicView = (r) => ({ id: r.id, at: r.at, kind: r.kind, decks: r.decks, status: r.status, reply: r.reply || "" });

async function handle(req, res) {
  const url = new URL(req.url, "http://localhost");
  const p = url.pathname.replace(/\/+$/, "");
  if (p === "/api/requests/queue" && req.method === "GET") {
    return send(res, 200, requests.slice().reverse().slice(0, QUEUE_SHOWN).map(publicView));
  }
  if (p === "/api/requests" && req.method === "GET") {
    if (!authorized(req)) return send(res, 401, { error: "Not allowed." });
    return send(res, 200, requests);
  }
  if (p === "/api/requests" && req.method === "POST") {
    const body = await readBody(req);
    if (str(body.website)) return send(res, 200, { ok: true }); // the form's hidden field: a bot filled it in
    const { request, problems } = validate(body);
    if (problems) return send(res, 400, { problems });
    const key = requestKey(request);
    const same = requests.find((r) => r.status === "waiting" && requestKey(r) === key);
    if (same) return send(res, 200, { id: same.id, duplicate: true });
    if (requests.filter((r) => r.status === "waiting").length >= MAX_WAITING) {
      return send(res, 503, { error: "The request list is full for now. Please try again later." });
    }
    const ip = String(req.headers["x-real-ip"] || req.socket.remoteAddress || "");
    if (!allowed(ip)) return send(res, 429, { error: "That's a lot of requests for one day. Please try again tomorrow." });
    const r = { id: crypto.randomBytes(5).toString("hex"), at: new Date().toISOString(), ...request, status: "waiting", reply: "" };
    requests.push(r);
    save();
    return send(res, 201, { id: r.id });
  }
  const m = /^\/api\/requests\/([0-9a-f]{10})$/.exec(p);
  if (m && req.method === "POST") {
    if (!authorized(req)) return send(res, 401, { error: "Not allowed." });
    const r = requests.find((x) => x.id === m[1]);
    if (!r) return send(res, 404, { error: "No request " + m[1] });
    const body = await readBody(req);
    if (body.status !== undefined) {
      if (!STATUSES.includes(body.status)) return send(res, 400, { error: "status is one of " + STATUSES.join(", ") });
      r.status = body.status;
    }
    if (body.reply !== undefined) r.reply = str(body.reply).slice(0, MAX_REPLY);
    save();
    return send(res, 200, r);
  }
  send(res, 404, { error: "Not found." });
}

http.createServer((req, res) => {
  handle(req, res).catch((e) => send(res, e.status || 500, { error: e.status ? "Bad request." : "Something went wrong." }));
}).listen(PORT, HOST, () => console.log(`3 Card Blind requests: http://${HOST}:${PORT}/api/requests (data in ${FILE})`));
