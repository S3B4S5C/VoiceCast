const statusEl = document.getElementById("status");
const statusPill = document.getElementById("statusPill");
const out = document.getElementById("out");
const footerMeta = document.getElementById("footerMeta");

const actions = document.getElementById("actions");
const btnDisconnect = document.getElementById("btnDisconnect");

const btnInfo = document.getElementById("btnInfo");
const btnSpells = document.getElementById("btnSpells");
const chkDebug = document.getElementById("chkDebug");
const debugWrap = document.getElementById("debugWrap");

const btnStart = document.getElementById("btnStart");
const btnStop = document.getElementById("btnStop");
const lastTextEl = document.getElementById("lastText");
const selLang = document.getElementById("selLang");

let token = null;
let playerUuid = null;

let recognition = null;
let listening = false;
let starting = false;
let userStopped = false;

let wantListening = false;

let startGuardTimer = null;
let lastStartAttemptAt = 0;

let micStream = null;

let aliasIndex = [];

let lastFinalNorm = "";
let lastFinalAt = 0;
const FINAL_DEDUPE_MS = 2500;

const spellCooldownUntil = new Map();
const SPELL_COOLDOWN_MS = 1000;

let debugEnabled = false;

let apiBase = "";

let speechLang = (selLang && selLang.value) ? selLang.value : "es-ES";

function setPill(state) {
  if (!statusPill) return;
  statusPill.dataset.state = state;
}

function appendLog(line) {
  if (!debugEnabled) return;
  if (!out) return;
  const ts = new Date().toLocaleTimeString();
  out.textContent = `[${ts}] ${line}\n` + out.textContent;
}

function setStatus(text, ok = true) {
  if (statusEl) statusEl.textContent = text;
  setPill(ok ? "ok" : "bad");
}

function setIdle(text) {
  if (statusEl) statusEl.textContent = text;
  setPill("idle");
}

function getParams() {
  return new URLSearchParams(location.search);
}

function getCodeFromUrl() {
  return getParams().get("code");
}

function getShareFromUrl() {
  return getParams().get("share");
}

if (chkDebug && debugWrap) {
  chkDebug.addEventListener("change", () => {
    debugEnabled = !!chkDebug.checked;
    debugWrap.style.display = debugEnabled ? "block" : "none";
    if (!debugEnabled && out) out.textContent = "";
  });
}

if (selLang) {
  selLang.addEventListener("change", async () => {
    speechLang = selLang.value || "es-ES";
    const wasListening = listening || starting;

    if (recognition) {
      try { recognition.__vc?.markAbortByUs(); } catch (_) {}
      try { recognition.abort(); } catch (_) {}
      try { recognition.lang = speechLang; } catch (_) {}
    }

    appendLog(`Speech language set to ${speechLang} (restart=${wasListening})`);

    if (wasListening) {
      await new Promise(r => setTimeout(r, 200));
      await startListening();
    }
  });
}

function b64urlDecode(str) {
  str = (str || "").replace(/-/g, "+").replace(/_/g, "/");
  while (str.length % 4) str += "=";
  const bin = atob(str);
  const bytes = new Uint8Array(bin.length);
  for (let i = 0; i < bin.length; i++) bytes[i] = bin.charCodeAt(i);
  return new TextDecoder().decode(bytes);
}

function parseShare(share) {
  try {
    const json = b64urlDecode(share);
    const obj = JSON.parse(json);
    const code = obj?.code;
    const candidates = Array.isArray(obj?.candidates) ? obj.candidates : [];
    const list = candidates
      .map(c => ({
        baseUrl: String(c?.baseUrl || ""),
        priority: Number(c?.priority || 0)
      }))
      .filter(c => c.baseUrl.length > 0);

    list.sort((a, b) => (b.priority - a.priority));
    return { code, candidates: list };
  } catch (e) {
    return null;
  }
}

async function probeCandidate(baseUrl, timeoutMs = 900) {
  const ctrl = new AbortController();
  const t = setTimeout(() => ctrl.abort(), timeoutMs);

  try {
    const res = await fetch(baseUrl.replace(/\/$/, "") + "/health", {
      method: "GET",
      signal: ctrl.signal
    });
    return res.ok;
  } catch (_) {
    return false;
  } finally {
    clearTimeout(t);
  }
}

async function pickApiBaseFromCandidates(candidates) {
  for (const c of candidates) {
    const baseUrl = c.baseUrl.replace(/\/$/, "");
    appendLog(`Probing candidate ${baseUrl} prio=${c.priority}`);
    const ok = await probeCandidate(baseUrl);
    if (ok) return baseUrl;
  }
  return null;
}

function apiUrl(path) {
  const base = (apiBase || "").replace(/\/$/, "");
  return base + path;
}

async function apiGet(path) {
  const res = await fetch(apiUrl(path), { method: "GET" });
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

async function apiPost(path, body, withAuth = false) {
  const headers = { "Content-Type": "application/json" };
  if (withAuth && token) headers["Authorization"] = `Bearer ${token}`;

  const res = await fetch(apiUrl(path), {
    method: "POST",
    headers,
    body: JSON.stringify(body ?? {})
  });

  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new Error(data.error || `HTTP ${res.status}`);
  return data;
}

async function connectWithCode(code) {
  setIdle("Connecting...");
  const data = await apiPost("/api/connect", { code });

  token = data.token;
  playerUuid = data.playerUuid;

  if (actions) actions.style.display = "block";
  setStatus(`Connected (${playerUuid})`, true);
  if (footerMeta) footerMeta.textContent = `Session: ${playerUuid}  •  ${apiBase || "local"}`;

  appendLog(`Connected playerUuid=${playerUuid} apiBase=${apiBase}`);

  await loadSpells();
}

async function loadSpells() {
  for (let attempt = 0; attempt < 10; attempt++) {
    try {
      const data = await apiGet("/api/spells");
      const list = Array.isArray(data?.spells) ? data.spells : [];
      buildAliasIndex(list);
      appendLog(`Loaded spells=${aliasIndex.length}`);
      if (aliasIndex.length > 0) return;
    } catch (e) {
      appendLog(`Failed /api/spells: ${e.message || e}`);
    }
    await new Promise(r => setTimeout(r, 500));
  }

  appendLog("No spells after retries — using fallback aliases");
  buildAliasIndex(fallbackSpells());
}

function fallbackSpells() {
  return [
    { id: "fireball", aliases: ["fireball", "fire ball", "bola de fuego", "bola de fogo", "fuego", "fogo"] },
    { id: "shield", aliases: ["shield", "escudo"] }
  ];
}

function buildAliasIndex(spells) {
  aliasIndex = [];

  for (const s of spells) {
    const id = s?.id;
    if (!id) continue;

    let aliases = [];
    if (Array.isArray(s.aliases)) {
      aliases = s.aliases;
    } else if (s.aliases && typeof s.aliases === "object") {
      for (const k of Object.keys(s.aliases)) {
        const arr = s.aliases[k];
        if (Array.isArray(arr)) aliases.push(...arr);
      }
    }

    if (!aliases.length) aliases = [id];

    const aliasesNorm = aliases
      .map(a => normalizeText(a))
      .filter(a => a.length > 0);

    aliasIndex.push({ spellId: id, aliasesNorm });
  }
}

async function cast(spellId, rawText = null, confidence = 1.0) {
  if (!token) {
    setStatus("Not connected (missing token).", false);
    return;
  }

  const now = Date.now();
  const until = spellCooldownUntil.get(spellId) || 0;
  if (now < until) return;
  spellCooldownUntil.set(spellId, now + SPELL_COOLDOWN_MS);

  appendLog(`CAST spell=${spellId} raw="${rawText ?? ""}" conf=${confidence}`);

  await apiPost("/api/cast", { spellId, confidence, raw: rawText ?? spellId }, true);
  setStatus(`Cast queued (${spellId})`, true);
}

async function disconnect() {
  if (!token) return;

  await apiPost("/api/disconnect", {}, true);
  token = null;
  playerUuid = null;

  stopListening();

  if (actions) actions.style.display = "none";
  setIdle("Disconnected");
  if (footerMeta) footerMeta.textContent = "Waiting for connection…";
  appendLog("Disconnected");
}

async function ensureMicPermission() {
  if (micStream) return;

  if (!navigator.mediaDevices?.getUserMedia) {
    throw new Error("getUserMedia not available (browser blocked mic APIs).");
  }

  micStream = await navigator.mediaDevices.getUserMedia({ audio: true });
}

function normalizeText(text) {
  return (text || "")
    .toLowerCase()
    .normalize("NFD")
    .replace(/[\u0300-\u036f]/g, "")
    .replace(/[^\p{L}\p{N}\s]/gu, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function matchSpellIdFromText(text) {
  const norm = normalizeText(text);
  if (!norm) return null;

  let best = null;
  let bestLen = 0;

  for (const entry of aliasIndex) {
    for (const alias of entry.aliasesNorm) {
      if (!alias) continue;
      if (norm === alias || norm.includes(alias)) {
        if (alias.length > bestLen) {
          bestLen = alias.length;
          best = entry.spellId;
        }
      }
    }
  }
  return best;
}

function initWebSpeech() {
  const SR = window.SpeechRecognition || window.webkitSpeechRecognition;
  if (!SR) return null;

  const r = new SR();
  r.continuous = true;
  r.interimResults = true;
  r.maxAlternatives = 1;
  r.lang = speechLang || "es-ES";

  let abortByUs = false;

  r.onstart = () => {
    starting = false;
    listening = true;
    abortByUs = false;

    if (btnStart) btnStart.disabled = true;
    if (btnStop) btnStop.disabled = false;

    setStatus("Listening", true);
    appendLog("Listening started");
  };

  r.onend = () => {
    starting = false;
    listening = false;

    if (btnStart) btnStart.disabled = false;
    if (btnStop) btnStop.disabled = true;

    wantListening = false;
    setIdle("Not listening");
    appendLog("Listening ended (manual mode)");
  };

  r.onerror = (e) => {
    starting = false;

    const err = e?.error || "unknown";
    appendLog(`Speech error: ${err}`);

    wantListening = false;

    if (btnStart) btnStart.disabled = false;
    if (btnStop) btnStop.disabled = true;

    if (err === "aborted" && abortByUs) {
      abortByUs = false;
      setIdle("Not listening");
      return;
    }

    if (err === "not-allowed" || err === "service-not-allowed") {
      setStatus("Microphone permission blocked by the browser.", false);
      return;
    }
    if (err === "audio-capture") {
      setStatus("No microphone available (audio-capture).", false);
      return;
    }

    setStatus(`Speech error: ${err}. Press Start again.`, false);
  };

  r.onresult = (e) => {
    const last = e.results[e.results.length - 1];
    const alt = last[0];
    const text = (alt.transcript || "").trim();
    if (lastTextEl) lastTextEl.textContent = text || "-";

    if (!last.isFinal) return;

    const conf = alt.confidence ?? 1.0;
    const finalNorm = normalizeText(text);
    const now = Date.now();

    if (finalNorm && finalNorm === lastFinalNorm && (now - lastFinalAt) < FINAL_DEDUPE_MS) return;
    lastFinalNorm = finalNorm;
    lastFinalAt = now;

    const spellId = matchSpellIdFromText(text);
    appendLog(`FINAL text="${text}" norm="${finalNorm}" match=${spellId ?? "none"} aliases=${aliasIndex.length} lang=${speechLang}`);

    if (!spellId) return;

    cast(spellId, text, conf).catch(err => {
      setStatus(`Cast failed: ${err.message}`, false);
      appendLog(`Cast failed: ${String(err)}`);
    });
  };

  r.__vc = {
    markAbortByUs() { abortByUs = true; }
  };

  return r;
}

async function startListening() {
  wantListening = true;

  if (!recognition) recognition = initWebSpeech();
  if (!recognition) {
    wantListening = false;
    setStatus("WebSpeech not supported (use Edge/Chrome).", false);
    appendLog("WebSpeech not supported");
    return;
  }

  const now = Date.now();
  if (now - lastStartAttemptAt < 400) return;
  lastStartAttemptAt = now;

  try { recognition.lang = speechLang || "es-ES"; } catch (_) {}

  if (listening || starting) return;

  userStopped = false;
  starting = true;

  if (btnStart) btnStart.disabled = true;
  if (btnStop) btnStop.disabled = false;
  setIdle("Starting microphone...");

  try {
    await ensureMicPermission();

    if (startGuardTimer) clearTimeout(startGuardTimer);
    startGuardTimer = setTimeout(() => {
      startGuardTimer = null;
      if (starting && !listening) {
        try { recognition.__vc?.markAbortByUs(); } catch (_) {}
        try { recognition.abort(); } catch (_) {}
        starting = false;

        wantListening = false;

        if (btnStart) btnStart.disabled = false;
        if (btnStop) btnStop.disabled = true;

        setStatus("Speech did not start (check browser/HTTPS). Press Start again.", false);
        appendLog("SpeechRecognition start timeout (aborted)");
      }
    }, 2500);

    recognition.start();
  } catch (e) {
    starting = false;
    wantListening = false;

    if (btnStart) btnStart.disabled = false;
    if (btnStop) btnStop.disabled = true;

    setStatus(`Mic/Speech start failed: ${e.message || e}`, false);
    appendLog(`Mic/Speech start failed: ${String(e)}`);
  }
}

function stopListening() {
  wantListening = false;
  userStopped = true;
  starting = false;

  try { recognition.__vc?.markAbortByUs(); } catch (_) {}
  try { if (recognition) recognition.stop(); } catch (_) {}

  if (btnStart) btnStart.disabled = false;
  if (btnStop) btnStop.disabled = true;
  setIdle("Not listening");
}

if (btnDisconnect) btnDisconnect.addEventListener("click", () => disconnect().catch(e => {
  setStatus(`Disconnect failed: ${e.message}`, false);
  appendLog(String(e));
}));

if (btnInfo) btnInfo.addEventListener("click", () => apiGet("/api/info").then(d => appendLog("INFO " + JSON.stringify(d))).catch(e => appendLog(String(e))));
if (btnSpells) btnSpells.addEventListener("click", () => apiGet("/api/spells").then(d => appendLog("SPELLS " + JSON.stringify(d))).catch(e => appendLog(String(e))));

if (btnStart) btnStart.addEventListener("click", () => startListening());
if (btnStop) btnStop.addEventListener("click", () => stopListening());

(async () => {
  setIdle("Frontend ready");

  const share = getShareFromUrl();
  if (share) {
    const payload = parseShare(share);
    if (!payload || !payload.code || !payload.candidates?.length) {
      setStatus("Invalid share link.", false);
      if (footerMeta) footerMeta.textContent = "Regenerate the link in-game and try again.";
      return;
    }

    setIdle("Selecting best connection...");
    const chosen = await pickApiBaseFromCandidates(payload.candidates);
    if (!chosen) {
      setStatus("Could not reach any candidate.", false);
      if (footerMeta) footerMeta.textContent = "Make sure you're on the same LAN or VPN as the host.";
      return;
    }

    apiBase = chosen;
    appendLog(`Selected apiBase=${apiBase}`);

    try {
      await connectWithCode(payload.code);
      setStatus("Connected", true);
      return;
    } catch (e) {
      setStatus(`Connect failed: ${e.message}`, false);
      if (footerMeta) footerMeta.textContent = "Connection failed. Regenerate the link in-game and try again.";
      appendLog("Connect failed: " + String(e));
      return;
    }
  }

  const code = getCodeFromUrl();
  if (!code) {
    setStatus("Missing ?share=... or ?code=... (use /voicecast in-game).", false);
    if (footerMeta) footerMeta.textContent = "Open this page using the link generated by /voicecast.";
    return;
  }

  apiBase = location.origin;

  try {
    await connectWithCode(code);
    setStatus("Connected", true);
  } catch (e) {
    setStatus(`Connect failed: ${e.message}`, false);
    if (footerMeta) footerMeta.textContent = "Connection failed. Regenerate the link in-game and try again.";
    appendLog("Connect failed: " + String(e));
  }
})();