// SpenDroid on a computer: importing older history from a bank's file. The file is read here, in
// the browser; the rows go to the phone, which adds nothing until you allow it there.
"use strict";

const ROLES = [
  ["", "Ignore"],
  ["date", "Date"],
  ["payee", "Description"],
  ["amount", "Amount (+ and −)"],
  ["out", "Money out"],
  ["in", "Money in"],
  ["flag", "Debit / credit marker"],
  ["balance", "Balance"],
];

const I = {
  step: 1, accountId: null, fileName: "", kind: "", grid: [], start: 0, roles: [], sure: [],
  dayFirst: true, flip: false, remember: true, check: null, checking: false, sendError: "",
  id: null, status: null, layouts: null, known: "", fromFile: null, timer: null,
};

// ---- reading files ------------------------------------------------------------------------

function parseCsv(text) {
  const firstLines = text.split(/\r?\n/).slice(0, 10).join("\n");
  const counts = { ",": 0, ";": 0, "\t": 0 };
  for (const ch of firstLines) if (ch in counts) counts[ch]++;
  const sep = Object.entries(counts).sort((a, b) => b[1] - a[1])[0][0];
  const rows = [];
  let row = [], cell = "", quoted = false;
  for (let i = 0; i < text.length; i++) {
    const ch = text[i];
    if (quoted) {
      if (ch === '"' && text[i + 1] === '"') { cell += '"'; i++; }
      else if (ch === '"') quoted = false;
      else cell += ch;
    } else if (ch === '"') quoted = true;
    else if (ch === sep) { row.push(cell.trim()); cell = ""; }
    else if (ch === "\n" || ch === "\r") {
      if (ch === "\r" && text[i + 1] === "\n") i++;
      row.push(cell.trim()); cell = "";
      if (row.some((c) => c !== "")) rows.push(row);
      row = [];
    } else cell += ch;
  }
  row.push(cell.trim());
  if (row.some((c) => c !== "")) rows.push(row);
  return rows;
}

/** OFX and QIF say what each value is, so they need no matching: straight to rows. */
function parseOfx(text) {
  const out = [];
  for (const block of text.split(/<STMTTRN>/i).slice(1)) {
    const tag = (t) => ((block.match(new RegExp("<" + t + ">([^<\\r\\n]*)", "i")) || [])[1] || "").trim();
    const d = tag("DTPOSTED");
    out.push({ date: d ? `${d.slice(0, 4)}-${d.slice(4, 6)}-${d.slice(6, 8)}` : null, amount: toMinor(tag("TRNAMT")), payee: tag("NAME") || tag("MEMO") });
  }
  return out;
}
function parseQif(text) {
  const out = [];
  let cur = {};
  for (const line of text.split(/\r?\n/)) {
    const k = line[0], v = line.slice(1).trim();
    if (k === "D") cur.date = parseDate(v.replace("'", "/"), I.dayFirst);
    else if (k === "T" || k === "U") cur.amount = toMinor(v);
    else if (k === "P") cur.payee = v;
    else if (k === "M" && !cur.payee) cur.payee = v;
    else if (k === "^") { out.push(cur); cur = {}; }
  }
  return out;
}

// ---- values -------------------------------------------------------------------------------

const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12 };
function parseDate(s, dayFirst) {
  s = String(s || "").trim();
  let m;
  const pad = (n) => String(n).padStart(2, "0");
  const ok = (y, mo, d) => (mo >= 1 && mo <= 12 && d >= 1 && d <= 31 ? `${y}-${pad(mo)}-${pad(d)}` : null);
  const year = (y) => (y.length === 2 ? 2000 + +y : +y);
  if ((m = s.match(/^(\d{4})-(\d{1,2})-(\d{1,2})/))) return ok(+m[1], +m[2], +m[3]);
  if ((m = s.match(/^(\d{4})(\d{2})(\d{2})$/))) return ok(+m[1], +m[2], +m[3]);
  if ((m = s.match(/^(\d{1,2})[\/.\-](\d{1,2})[\/.\-](\d{2,4})/))) {
    const a = +m[1], b = +m[2], y = year(m[3]);
    return dayFirst ? ok(y, b, a) : ok(y, a, b);
  }
  if ((m = s.match(/^(\d{1,2})[\s\-]([A-Za-z]{3,4})[A-Za-z]*[\s\-](\d{2,4})/))) {
    const mo = MONTHS[m[2].toLowerCase()];
    return mo ? ok(year(m[3]), mo, +m[1]) : null;
  }
  return null;
}
/** "£1,234.56", "(12.00)", "12.00 DR" and "-12" all read; anything else is not an amount. */
function toMinor(s) {
  let t = String(s || "").trim();
  if (!t) return null;
  let neg = false;
  if (/^\(.*\)$/.test(t)) { neg = true; t = t.slice(1, -1); }
  if (/\bDR$/i.test(t)) { neg = true; t = t.replace(/\s*DR$/i, ""); }
  t = t.replace(/\s*CR$/i, "").replace(/[£$€,\s]/g, "");
  if (t.startsWith("-") || t.startsWith("−")) { neg = !neg; t = t.slice(1); }
  if (t.startsWith("+")) t = t.slice(1);
  if (!/^\d+(\.\d+)?$/.test(t)) return null;
  const v = Math.round(parseFloat(t) * 100);
  return neg ? -v : v;
}

// ---- guessing the columns -----------------------------------------------------------------

function findStart(grid) {
  // The heading row: the first whose next row holds a date, and which holds none itself.
  for (let r = 0; r < Math.min(grid.length - 1, 20); r++) {
    const here = grid[r].some((c) => parseDate(c, true));
    const next = grid[r + 1].some((c) => parseDate(c, true));
    if (!here && next && grid[r].filter((c) => c).length >= 2) return r;
  }
  return grid.length && grid[0].some((c) => parseDate(c, true)) ? -1 : 0;
}
const signature = (heads) => heads.map((h) => h.toLowerCase().replace(/[^a-z/]/g, "")).join("|");

/** Each column's role, from its heading where it has a telling one, else from what it holds. */
function guess() {
  const heads = I.start >= 0 ? I.grid[I.start] : [];
  const data = I.grid.slice(I.start + 1, I.start + 40);
  const width = Math.max(...I.grid.slice(Math.max(0, I.start), I.start + 40).map((r) => r.length));
  const saved = I.layouts && heads.length ? I.layouts[signature(heads)] : null;
  if (saved) {
    I.roles = saved.roles.slice(0, width);
    I.sure = I.roles.map(() => true);
    I.dayFirst = saved.dayFirst !== false; I.flip = !!saved.flip;
    I.known = "a layout you matched before";
    return;
  }
  const byName = (h) => {
    h = (h || "").toLowerCase();
    if (/balance|\bbal\b/.test(h)) return "balance";
    if (/debit\s*\/\s*credit|dr\s*\/\s*cr|flag|indicator/.test(h)) return "flag";
    if (/^(transaction\s+date|date|txn\s*date|trans(action)?\s*date)$/.test(h)) return "date";
    if (/paid\s*out|money\s*out|withdrawal|^debit/.test(h)) return "out";
    if (/paid\s*in|money\s*in|deposit|^credit/.test(h)) return "in";
    if (/^(amount|value|billing\s+amount|transaction\s+amount)$/.test(h)) return "amount";
    if (/description|details|merchant|payee|narrative|^name$|counter\s*party/.test(h)) return "payee";
    if (/posting\s*date/.test(h)) return "";
    return null;
  };
  const roles = [], sure = [];
  for (let c = 0; c < width; c++) {
    const named = byName(heads[c]);
    if (named !== null) { roles.push(named); sure.push(true); continue; }
    const vals = data.map((r) => r[c] || "").filter((v) => v);
    const dates = vals.filter((v) => parseDate(v, true)).length;
    const nums = vals.filter((v) => toMinor(v) !== null).length;
    if (vals.length && dates / vals.length > 0.8) roles.push("date");
    else if (vals.length && nums / vals.length > 0.8) roles.push("amount");
    else if (vals.length) roles.push("payee");
    else roles.push("");
    sure.push(false);
  }
  // One of each: a column whose heading says so beats one only guessed from what it holds.
  for (const r of ["date", "payee", "amount", "flag"]) {
    const all = roles.map((v, i) => (v === r ? i : -1)).filter((i) => i >= 0);
    const keep = all.find((i) => sure[i]) ?? all[0];
    all.forEach((i) => { if (i !== keep) { roles[i] = ""; sure[i] = false; } });
  }
  // Money out and in, where the file has them, are the amount; a single amount column besides them
  // is more likely a balance or a total.
  if (roles.includes("out") || roles.includes("in")) roles.forEach((v, i) => { if (v === "amount" && !sure[i]) roles[i] = ""; });
  I.roles = roles; I.sure = sure;
  const sig = signature(heads);
  I.known = /date\|description\|amount/.test(sig) && heads.length <= 4 ? "first direct"
    : /type/.test(sig) && /accountname/.test(sig) ? "NatWest"
    : /debit\/creditflag|creditdebitflag/.test(sig) || (/merchant/.test(sig) && /billingamount/.test(sig)) ? "Tesco Bank"
    : "";
  // A card's single amount column: banks mostly show spending as positive there.
  const acct = account(I.accountId);
  const amountCol = roles.indexOf("amount");
  if (acct && acct.type === "CREDIT_CARD" && amountCol >= 0 && roles.indexOf("flag") < 0) {
    const vals = data.map((r) => toMinor(r[amountCol])).filter((v) => v !== null);
    I.flip = vals.filter((v) => v > 0).length > vals.length / 2;
    sure[amountCol] = false;
  }
}

/** The rows as SpenDroid would read them, and those it can't, with why. */
function readRows() {
  if (I.fromFile) {
    return I.fromFile.map((r, i) => (r.date && r.amount !== null && r.amount !== undefined
      ? { ok: true, date: r.date, amount: I.flip ? -r.amount : r.amount, payee: r.payee || "Unknown" }
      : { ok: false, line: i + 1, payee: r.payee || "", why: !r.date ? "no date" : "no amount" }));
  }
  const col = (role) => I.roles.indexOf(role);
  const [d, p, a, o, n, f] = ["date", "payee", "amount", "out", "in", "flag"].map(col);
  return I.grid.slice(I.start + 1).map((r, i) => {
    const line = I.start + 2 + i;
    const date = d >= 0 ? parseDate(r[d], I.dayFirst) : null;
    if (!date) return { ok: false, line, payee: r[p] || r.join(" ").slice(0, 40), why: d < 0 ? "choose the date column" : `"${(r[d] || "").slice(0, 16)}" isn't a date` };
    let amount = null;
    if (a >= 0 && o < 0 && n < 0) {
      amount = toMinor(r[a]);
      if (amount !== null && f >= 0) {
        const flag = (r[f] || "").trim().toLowerCase();
        amount = /^(d|dr|debit)/.test(flag) ? -Math.abs(amount) : /^(c|cr|credit)/.test(flag) ? Math.abs(amount) : amount;
      } else if (amount !== null && I.flip) amount = -amount;
    } else if (o >= 0 || n >= 0) {
      const out = o >= 0 ? toMinor(r[o]) : null, inn = n >= 0 ? toMinor(r[n]) : null;
      if (out !== null && out !== 0) amount = -Math.abs(out);
      else if (inn !== null && inn !== 0) amount = Math.abs(inn);
      else if (out === 0 || inn === 0) amount = 0;
    }
    if (amount === null) return { ok: false, line, payee: r[p] || "", why: a < 0 && o < 0 && n < 0 ? "choose the amount column" : "no amount it can read" };
    return { ok: true, date, amount, payee: (p >= 0 ? r[p] : "") || "Unknown" };
  });
}

// ---- the page -----------------------------------------------------------------------------

function importPage() {
  const d = S.data;
  if (I.layouts === null) { I.layouts = {}; fetch("/api/layouts").then((r) => r.json()).then((j) => { I.layouts = j; }).catch(() => {}); }
  if (!I.accountId) I.accountId = (d.accounts[0] || {}).id;
  const steps = [["1", "Choose file"], ["2", "Match columns"], ["3", "Check and send to your phone"]]
    .map(([n, l], i) => `<span class="chip ${I.step === i + 1 ? "on" : ""}" style="${I.step > i + 1 ? "color:var(--in)" : ""}">${I.step > i + 1 ? "✓ " : ""}${n} · ${l}</span>`).join("");
  const acctSel = `<select class="chip" id="impacct">${d.accounts.map((a) => `<option value="${esc(a.id)}" ${I.accountId === a.id ? "selected" : ""}>into ${esc(a.label)}</option>`).join("")}</select>`;
  let body = "";
  if (I.step === 1) {
    body = `<div class="panel" style="margin-top:16px;max-width:760px">
      <div class="sec">A file from your bank's website</div>
      <p class="k2" style="margin:8px 0 14px">CSV, OFX or QIF. Older history fills in what your bank's 90 days leave out - so yearly bills are found, and "usual" means more. Nothing is added until you allow it on your phone.</p>
      <label class="btn" style="display:inline-block;width:auto;padding:12px 22px;margin:0;cursor:pointer">Choose a file<input type="file" id="impfile" accept=".csv,.txt,.ofx,.qfx,.qif" style="display:none"></label>
      <p class="k2" style="margin-top:14px">first direct, NatWest and Tesco Bank files are recognised; any other is matched by you once, and remembered.</p></div>`;
  } else if (I.step === 2) {
    body = matching();
  } else {
    body = waiting();
  }
  return `<div class="top"><span class="word">IMPORT</span>${I.fileName ? `<span class="chip">${esc(I.fileName)}</span>` : ""}${I.step < 3 ? acctSel : ""}</div>
    <div class="bar-row">${steps}</div>${body}`;
}

function matching() {
  const rows = readRows();
  const good = rows.filter((r) => r.ok), bad = rows.filter((r) => !r.ok);
  const width = I.roles.length;
  let grid = "";
  if (!I.fromFile) {
    const head = `<tr><td class="n"></td>${I.roles.map((role, c) => `<td><select class="chip role ${role ? (I.sure[c] ? "rsure" : "rcheck") : "rignore"}" data-col="${c}">${ROLES.map(([v, l]) => `<option value="${v}" ${v === role ? "selected" : ""}>${l}</option>`).join("")}</select>${role && !I.sure[c] ? `<div class="k2" style="color:var(--warn);font-size:10px;margin-top:2px">check</div>` : ""}</td>`).join("")}</tr>`;
    const shown = I.grid.slice(0, Math.max(I.start + 1, 0) + 8);
    grid = `<div class="panel" style="margin-top:12px;overflow:auto"><div class="bar-row" style="margin:0 0 10px">
        <span class="sec">The file as it is</span>
        <label class="chip">Data starts at row <select id="impstart" style="background:none;border:0;color:var(--accent);font-weight:900">${I.grid.slice(0, 25).map((_, r) => `<option value="${r}" ${I.start + 1 === r ? "selected" : ""}>${r + 1}</option>`).join("")}</select></label>
        <label class="chip">Dates <select id="impdays" style="background:none;border:0;color:var(--accent);font-weight:900"><option value="1" ${I.dayFirst ? "selected" : ""}>day / month</option><option value="0" ${!I.dayFirst ? "selected" : ""}>month / day</option></select></label>
        <label class="chip"><input type="checkbox" id="impflip" ${I.flip ? "checked" : ""} style="accent-color:var(--accent)"> Spending shows as positive</label>
        ${I.known ? `<span class="k2" style="margin-left:auto">Recognised: ${esc(I.known)}</span>` : ""}</div>
      <table class="raw">${head}${shown.map((r, i) => `<tr class="${i < I.start ? "pre" : i === I.start ? "hd" : ""}"><td class="n">${i + 1}</td>${Array.from({ length: width }, (_, c) => `<td>${esc(r[c] || "")}</td>`).join("")}</tr>`).join("")}</table>
      <div class="k2" style="margin-top:6px">Showing ${Math.min(shown.length, I.grid.length)} of ${I.grid.length} lines</div></div>`;
  } else {
    grid = `<div class="panel" style="margin-top:12px"><span class="sec">${I.kind.toUpperCase()} file</span><p class="k2" style="margin-top:6px">This kind of file says what each value is, so there are no columns to match.</p>
      <label class="chip" style="margin-top:10px;display:inline-flex"><input type="checkbox" id="impflip" ${I.flip ? "checked" : ""} style="accent-color:var(--accent)"> Spending shows as positive</label></div>`;
  }
  const preview = good.slice(0, 6).map((r) => `<div class="row" style="padding:7px 12px"><span class="tile" style="background:${payeeColour(r.payee)};width:28px;height:28px">${esc(initial(r.payee))}</span><span class="k2">${fmtDay(r.date, { day: "numeric", month: "short", year: "numeric" })}</span><span>${esc(r.payee)}</span><span class="a" style="${r.amount >= 0 ? "color:var(--in)" : ""}">${r.amount >= 0 ? "+" : ""}${money(r.amount)}</span></div>`).join("")
    + bad.slice(0, 3).map((r) => `<div class="row" style="padding:7px 12px;background:#3a1f1f"><span class="tile" style="background:var(--bad);width:28px;height:28px">!</span><span class="k2">line ${r.line}</span><span>${esc(r.payee)}</span><span class="a" style="color:var(--bad);font-size:12px;font-weight:700">${esc(r.why)} - skipped</span></div>`).join("");
  const c = I.check;
  const out = good.filter((r) => r.amount < 0).reduce((s, r) => s - r.amount, 0), inn = good.filter((r) => r.amount > 0).reduce((s, r) => s + r.amount, 0);
  const range = good.length ? `${fmtDay(good.reduce((m, r) => (r.date < m ? r.date : m), good[0].date), { day: "numeric", month: "short", year: "numeric" })} – ${fmtDay(good.reduce((m, r) => (r.date > m ? r.date : m), good[0].date), { day: "numeric", month: "short", year: "numeric" })}` : "";
  const summary = `<div class="panel" style="flex:1"><div class="sec">Before you send it</div>
    <div style="line-height:2;margin-top:8px">
      ${c ? `<div><b style="color:var(--in)">${c.toAdd}</b> to add${range ? `, ${range}` : ""}</div>
        ${c.alreadyThere ? `<div><b style="color:var(--muted)">${c.alreadyThere}</b> already in SpenDroid - skipped</div>` : ""}
        ${c.coveredByBank ? `<div><b style="color:var(--muted)">${c.coveredByBank}</b> your bank already sends${c.bankFrom ? ` (from ${fmtDay(c.bankFrom, { day: "numeric", month: "short" })})` : ""} - skipped</div>` : ""}`
      : `<div class="k2">${I.checking ? "Asking your phone what's new…" : good.length ? "" : "Match the date and amount columns to see what would be added."}</div>`}
      ${bad.length ? `<div><b style="color:var(--bad)">${bad.length}</b> can't be read - skipped</div>` : ""}
      ${good.length ? `<div>Money out <b>${money(out)}</b> · in <b>${money(inn)}</b></div>` : ""}
    </div>
    ${I.fromFile ? "" : `<label class="check" style="justify-content:flex-start"><input type="checkbox" id="impremember" ${I.remember ? "checked" : ""}> Remember this layout</label>`}
    <button class="btn" id="impsend" ${c && c.toAdd > 0 ? "" : "disabled style='opacity:.4;cursor:default'"}>Send to my phone to approve</button>
    ${I.sendError ? `<p class="err">${esc(I.sendError)}</p>` : ""}
    <button class="chip" id="impagain" style="margin-top:12px">Choose a different file</button></div>`;
  return `${grid}<div style="display:flex;gap:16px;margin-top:16px;flex-wrap:wrap"><div class="panel" style="flex:1.3;min-width:320px"><div style="display:flex"><span class="sec">How SpenDroid reads it</span><span class="k2" style="margin-left:auto">updates as you change the columns</span></div>${preview || `<p class="k2" style="margin-top:8px">Nothing readable yet.</p>`}</div>${summary}</div>`;
}

function waiting() {
  const words = {
    WAITING: ["Approve it on your phone", "A notification on your phone asks to add them - tap Allow. It waits there for ten minutes."],
    ALLOWED: ["Added", "Allowed on the phone. It's being worked into your budget now; it can be undone under Accounts."],
    CANCELLED: ["Cancelled", "Nothing was added."],
    EXPIRED: ["Timed out", "Nothing was added. Send it again when you have your phone to hand."],
  }[I.status || "WAITING"];
  return `<div class="panel" style="margin-top:16px;max-width:620px;text-align:center;padding:30px">
    <div style="font-size:24px;font-weight:900;color:${I.status === "ALLOWED" ? "var(--in)" : I.status === "WAITING" || !I.status ? "var(--accent)" : "var(--muted)"}">${words[0]}</div>
    <p class="k2" style="margin-top:8px">${words[1]}</p>
    ${I.status && I.status !== "WAITING" ? `<button class="btn" id="impagain" style="max-width:260px;margin:22px auto 0">Import another file</button>` : ""}</div>`;
}

// ---- events -------------------------------------------------------------------------------

function importWire() {
  const $ = (id) => document.getElementById(id);
  if ($("impacct")) $("impacct").onchange = (e) => { I.accountId = e.target.value; if (I.step === 2 && !I.fromFile) guess(); I.check = null; askCheck(); render(); };
  if ($("impfile")) $("impfile").onchange = (e) => {
    const file = e.target.files[0];
    if (!file) return;
    const reader = new FileReader();
    reader.onload = () => loaded(file.name, String(reader.result));
    reader.readAsText(file);
  };
  app.querySelectorAll("select.role").forEach((s) => (s.onchange = () => { const c = +s.dataset.col; I.roles[c] = s.value; I.sure[c] = true; I.check = null; askCheck(); render(); }));
  if ($("impstart")) $("impstart").onchange = (e) => { I.start = +e.target.value - 1; guess(); I.check = null; askCheck(); render(); };
  if ($("impdays")) $("impdays").onchange = (e) => { I.dayFirst = e.target.value === "1"; I.check = null; askCheck(); render(); };
  if ($("impflip")) $("impflip").onchange = (e) => { I.flip = e.target.checked; I.check = null; askCheck(); render(); };
  if ($("impremember")) $("impremember").onchange = (e) => { I.remember = e.target.checked; };
  if ($("impsend")) $("impsend").onclick = send;
  if ($("impagain")) $("impagain").onclick = () => { Object.assign(I, { step: 1, fileName: "", grid: [], fromFile: null, check: null, id: null, status: null, sendError: "", known: "" }); render(); };
}

function loaded(name, text) {
  I.fileName = name; I.check = null; I.sendError = "";
  if (/<OFX>|OFXHEADER/i.test(text)) { I.kind = "ofx"; I.fromFile = parseOfx(text); I.flip = false; }
  else if (/^!Type:/im.test(text)) { I.kind = "qif"; I.fromFile = parseQif(text); I.flip = false; }
  else {
    I.kind = "csv"; I.fromFile = null;
    I.grid = parseCsv(text);
    I.start = findStart(I.grid);
    guess();
  }
  I.step = 2;
  askCheck();
  render();
}

/** What would be added, asked of the phone - which only reads to answer. */
function askCheck() {
  clearTimeout(I.timer);
  const rows = readRows().filter((r) => r.ok).map(({ date, amount, payee }) => ({ date, amount, payee }));
  if (!rows.length || I.step !== 2) return;
  I.checking = true;
  I.timer = setTimeout(async () => {
    const r = await fetch("/api/import/check", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ accountId: I.accountId, rows }) }).catch(() => null);
    I.checking = false;
    if (r && r.ok) I.check = await r.json();
    if (S.route === "import") render();
  }, 500);
}

async function send() {
  const rows = readRows().filter((r) => r.ok).map(({ date, amount, payee }) => ({ date, amount, payee }));
  const heads = I.start >= 0 && I.grid[I.start] ? I.grid[I.start] : [];
  const layout = !I.fromFile && I.remember && heads.length ? { signature: signature(heads), roles: I.roles, dayFirst: I.dayFirst, flip: I.flip } : null;
  const r = await fetch("/api/import/send", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ accountId: I.accountId, fileName: I.fileName, rows, layout }) }).catch(() => null);
  if (!r || !r.ok) { I.sendError = r ? await r.text() : "Can't reach your phone."; return render(); }
  I.id = (await r.json()).id;
  I.step = 3; I.status = "WAITING";
  render();
  const poll = async () => {
    const s = await fetch("/api/import/" + I.id).then((x) => x.json()).catch(() => null);
    if (s) I.status = s.status;
    if (S.route === "import") render();
    if (I.status === "WAITING") setTimeout(poll, 2000);
    else if (I.status === "ALLOWED") setTimeout(() => load(true), 3000);
  };
  setTimeout(poll, 2000);
}
