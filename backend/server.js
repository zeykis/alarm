// Wake-Up Alarm backend — no Firebase, no third-party push service.
// -----------------------------------------------------------------------
// The phone keeps one persistent WebSocket connection open to this server.
// The website calls POST /trigger. This server just forwards an "ALARM"
// message down that already-open socket, instantly, and enforces a
// server-side 5-minute cooldown so it can't be spammed.
// -----------------------------------------------------------------------

const http = require("http");
const express = require("express");
const path = require("path");
const fs = require("fs");
const { WebSocketServer } = require("ws");

const PORT = process.env.PORT || 3000;
const COOLDOWN_MS =  60 * 1000; // 5 minutes
const STATE_FILE = path.join(__dirname, "state.json");

function loadState() {
  if (!fs.existsSync(STATE_FILE)) return { lastTriggeredAt: 0 };
  return JSON.parse(fs.readFileSync(STATE_FILE, "utf8"));
}
function saveState(state) {
  fs.writeFileSync(STATE_FILE, JSON.stringify(state, null, 2));
}

const app = express();
app.set("trust proxy", true); // needed on Render so req.ip is the real caller, not Render's internal proxy
app.use(express.json());

// --- Visit logging ---
const VISITS_FILE = path.join(__dirname, "visits.json");

function logVisit(req) {
  const entry = {
    ip: req.ip,
    path: req.path,
    userAgent: req.headers["user-agent"] || "",
    time: new Date().toISOString(),
  };
  // console.log(`Visit: ${entry.ip} ${entry.path} at ${entry.time}`);

  const visits = fs.existsSync(VISITS_FILE)
    ? JSON.parse(fs.readFileSync(VISITS_FILE, "utf8"))
    : [];
  // visits.push(entry);
  // Keep the file from growing forever — last 500 visits is plenty.
  // fs.writeFileSync(VISITS_FILE, JSON.stringify(visits.slice(-500), null, 2));
}

app.use((req, res, next) => {
  if (req.method === "GET") logVisit(req);
  next();
});

// View logged visits any time at GET /visits
app.get("/visits", (req, res) => {
  const visits = fs.existsSync(VISITS_FILE)
    ? JSON.parse(fs.readFileSync(VISITS_FILE, "utf8"))
    : [];
  res.json(visits.reverse()); // most recent first
});

app.use(express.static(path.join(__dirname, "..", "website")));

// Allow the website to be hosted elsewhere too, if you want.
app.use((req, res, next) => {
  res.header("Access-Control-Allow-Origin", "*");
  res.header("Access-Control-Allow-Headers", "Content-Type");
  res.header("Access-Control-Allow-Methods", "GET,POST,OPTIONS");
  if (req.method === "OPTIONS") return res.sendStatus(200);
  next();
});

const server = http.createServer(app);
const wss = new WebSocketServer({ server, path: "/phone" });

// Single phone, single connection. If it reconnects, the new socket replaces the old one.
let phoneSocket = null;

wss.on("connection", (ws) => {
  console.log("Phone connected");
  phoneSocket = ws;

  // Keep the connection alive / detect drops quickly.
  ws.isAlive = true;
  ws.on("pong", () => (ws.isAlive = true));

  ws.on("close", () => {
    console.log("Phone disconnected");
    if (phoneSocket === ws) phoneSocket = null;
  });

  ws.on("error", (err) => console.error("Phone socket error:", err.message));
});

// Ping every 20s; drop sockets that stop responding so status is always accurate.
setInterval(() => {
  wss.clients.forEach((ws) => {
    if (ws.isAlive === false) return ws.terminate();
    ws.isAlive = false;
    ws.ping();
  });
}, 20000);

// The website button calls this.
app.post("/trigger", (req, res) => {
  console.log(`Trigger request from ${req.ip} at ${new Date().toISOString()}`);

  if (!phoneSocket || phoneSocket.readyState !== phoneSocket.OPEN) {
    return res.status(503).json({ error: "Phone is not connected right now" });
  }

  const state = loadState();
  const now = Date.now();
  const elapsed = now - state.lastTriggeredAt;

  if (elapsed < COOLDOWN_MS) {
    return res.status(429).json({
      error: "cooldown",
      remainingSeconds: Math.ceil((COOLDOWN_MS - elapsed) / 1000),
    });
  }

  phoneSocket.send(JSON.stringify({ type: "ALARM", sentAt: now }));

  state.lastTriggeredAt = now;
  saveState(state);

  console.log("Alarm triggered at", new Date(now).toISOString());
  res.json({ ok: true });
});

// Lets the website show live status instead of guessing.
app.get("/status", (req, res) => {
  const state = loadState();
  const elapsed = Date.now() - state.lastTriggeredAt;
  const remainingSeconds = Math.max(0, Math.ceil((COOLDOWN_MS - elapsed) / 1000));
  res.json({
    phoneConnected: !!phoneSocket && phoneSocket.readyState === phoneSocket.OPEN,
    remainingSeconds,
  });
});

server.listen(PORT, () => {
  console.log(`Wake-Up Alarm server running on http://localhost:${PORT}`);
  console.log(`Phone connects to ws://<this-host>:${PORT}/phone`);
});
