// SpenDroid on a computer. Read-only: draws what the phone sends, and changes nothing.
"use strict";

const app = document.getElementById("app");
const S = {
  data: null,
  route: (location.hash || "#overview").slice(1),
  tab: "all",
  q: "",
  account: null,
  category: "",
  period: "cycle",
  selected: null,
  regularTab: "upcoming",
  cycles: 6,
};

// ---- helpers ------------------------------------------------------------------------------

/** Everything from the bank is text, never markup: a payee called "<b>" stays "<b>". */
function esc(s) {
  return String(s ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]));
}
const gbp = new Intl.NumberFormat("en-GB", { style: "currency", currency: "GBP" });
const money = (minor) => (minor < 0 ? "−" : "") + gbp.format(Math.abs(minor) / 100);
const pounds = (minor) => (minor < 0 ? "−" : "") + "£" + Math.round(Math.abs(minor) / 100).toLocaleString("en-GB");
const day = (iso) => new Date(iso + "T12:00:00");
const fmtDay = (iso, opts) => day(iso).toLocaleDateString("en-GB", opts || { weekday: "short", day: "numeric", month: "short" });
const initial = (s) => ((String(s).match(/[A-Za-z0-9]/) || ["£"])[0]).toUpperCase();
const account = (id) => S.data.accounts.find((a) => a.id === id);
const category = (name) => S.data.categories.find((c) => c.name === name) || { label: name, colour: "#757575" };
const NOT_SPENDING = new Set(["SALARY", "TRANSFERS", "CARD_BILL"]);
function payeeColour(name) {
  const palette = ["#7A3BE0", "#E05A3B", "#1B9BD8", "#2FB57A", "#E04A7A", "#B8A12D", "#5A5FD8", "#2FB5A5"];
  const key = String(name).toLowerCase().replace(/[^a-z]/g, "");
  let h = 0;
  for (const ch of key) h = (Math.imul(31, h) + ch.charCodeAt(0)) | 0;
  return palette[((h % palette.length) + palette.length) % palette.length];
}
function bar(fraction, label, colour, tick, extra) {
  const f = Math.max(0, Math.min(1, fraction || 0));
  const x = Math.max(0, Math.min(1 - f, extra || 0));
  return `<div class="bar">
    ${x > 0 ? `<div class="f" style="width:${(f + x) * 100}%;background:${colour};opacity:.35"></div>` : ""}
    <div class="f" style="width:${f * 100}%;background:${colour}"></div>
    ${tick != null ? `<div class="k" style="left:calc(${tick * 100}% - 1.5px)"></div>` : ""}
    <div class="t">${esc(label)}</div></div>`;
}
const paceColour = (p) => (p === "OVER" ? "var(--bad)" : p === "TIGHT" ? "var(--warn)" : "var(--good)");
const paceWord = (p) => (p === "OVER" ? "OVER PACE" : p === "TIGHT" ? "TIGHT" : "ON PACE");

// ---- loading ------------------------------------------------------------------------------

/** A screen of its own for waiting and for failures, so the page never just sits there. */
function notice(text, retry) {
  app.innerHTML = `<div class="pair"><div class="box"><div class="word">SPENDROID</div>
    <p class="${retry ? "err" : "wait"}">${esc(text)}</p>${retry ? `<button class="btn" id="retry">Try again</button>` : ""}</div></div>`;
  if (retry) document.getElementById("retry").onclick = () => boot();
}

async function boot() {
  const me = await fetch("/api/me").then((r) => r.json()).catch(() => null);
  if (!me) return notice(`Can't reach your phone. Is "Open on my computer" still on, and this computer on the same Wi-Fi or hotspot?`, true);
  if (!me.allowed) return pairing();
  if (!(await load())) return;
  if (S.refreshing) return;
  S.refreshing = true;
  setInterval(() => document.visibilityState === "visible" && load(true), 60000);
  document.addEventListener("visibilitychange", () => document.visibilityState === "visible" && load(true));
}

/** Fetches and draws the figures; true when they are on screen. A refresh in the background keeps quiet about failures. */
async function load(quiet) {
  if (!quiet && !S.data) notice("Fetching your figures from the phone…");
  const r = await fetch("/api/data").catch(() => null);
  if (!r) return quiet || notice(`Can't reach your phone. Is "Open on my computer" still on?`, true), false;
  if (r.status === 401) return pairing(), false;
  if (!r.ok) return quiet || notice((await r.text().catch(() => "")) || `The phone answered ${r.status}.`, true), false;
  try {
    S.data = await r.json();
    document.documentElement.style.setProperty("--accent", S.data.accent);
    render();
    return true;
  } catch (e) {
    console.error(e);
    if (!quiet) notice(`Something went wrong showing your figures: ${e.message}`, true);
    return false;
  }
}

function pairing() {
  app.innerHTML = `<div class="pair"><div class="glow" style="height:420px"></div><div class="box">
    <div class="word" style="font-size:30px">SPENDROID</div>
    <p style="color:#cfd2d8;margin:8px 0 26px">Enter the code shown on your phone</p>
    <input class="code" id="code" inputmode="numeric" autocomplete="one-time-code" maxlength="7" placeholder="000 000" autofocus>
    <label class="check"><input type="checkbox" id="remember" checked> Remember this computer for 30 days</label>
    <button class="btn" id="open">Open</button>
    <p class="err" id="err"></p>
    <p class="k2" style="margin-top:14px">On your home Wi-Fi or your phone's hotspot · the phone can forget this computer at any time</p>
  </div></div>`;
  const go = async () => {
    const code = document.getElementById("code").value.replace(/\D/g, "");
    const err = document.getElementById("err");
    const remember = document.getElementById("remember").checked;
    const open = document.getElementById("open");
    if (code.length !== 6) return (err.textContent = "The code is six digits - it's on your phone, under Settings → Computer.");
    open.disabled = true;
    open.textContent = "Checking…";
    err.textContent = "";
    const r = await fetch("/api/pair", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ code, remember }) }).catch(() => null);
    if (r && r.ok) return boot();
    open.disabled = false;
    open.textContent = "Open";
    err.textContent = r ? (await r.text().catch(() => "")) || `The phone answered ${r.status}.` : `Can't reach your phone. Is "Open on my computer" still on?`;
  };
  document.getElementById("open").onclick = go;
  document.getElementById("code").onkeydown = (e) => e.key === "Enter" && go();
}

// ---- the frame ----------------------------------------------------------------------------

const PAGES = [["overview", "▦", "Overview"], ["transactions", "▤", "Transactions"], ["regular", "⇄", "Regular"], ["insights", "◔", "Insights"], ["import", "⇪", "Import"]];

function render() {
  const d = S.data;
  if (!PAGES.some((p) => p[0] === S.route)) S.route = "overview";
  const nav = PAGES.map(([id, icon, label]) => `<button class="ni ${S.route === id ? "on" : ""}" data-go="${id}"><span class="sq">${icon}</span><span class="t">${label}</span></button>`).join("");
  const accts = d.accounts.map((a) => `<button class="ni" data-account="${esc(a.id)}"><span class="sq" style="background:${a.colour}">${esc(initial(a.institution || a.label))}</span><span class="t">${esc(a.label)}</span><span class="v">${a.balance == null ? "" : pounds(a.balance)}</span></button>`).join("");
  const body = { overview, transactions, regular, insights, import: importPage }[S.route]();
  app.innerHTML = `<div class="shell">
    <nav class="side"><div class="ban"><b>SPENDROID</b><div>${d.accounts.length} accounts · ${esc(d.updated)}</div></div>
      ${nav}<div class="sec">Accounts</div>${accts}<span class="ro">READ-ONLY</span></nav>
    <main class="main"><div class="glow"></div>${body}</main></div>`;
  app.querySelectorAll("[data-go]").forEach((b) => (b.onclick = () => go(b.dataset.go)));
  app.querySelectorAll("[data-account]").forEach((b) => (b.onclick = () => { S.account = b.dataset.account; S.tab = "all"; go("transactions"); }));
  wire();
}

function go(route) {
  S.route = route;
  history.replaceState(null, "", "#" + route);
  render();
  window.scrollTo(0, 0);
}

/** Inputs and clicks inside the page being shown. */
function wire() {
  const $ = (id) => document.getElementById(id);
  app.querySelectorAll("[data-tab]").forEach((b) => (b.onclick = () => { S.tab = b.dataset.tab; render(); }));
  app.querySelectorAll("[data-rtab]").forEach((b) => (b.onclick = () => { S.regularTab = b.dataset.rtab; render(); }));
  app.querySelectorAll("[data-acc]").forEach((b) => (b.onclick = () => { S.account = b.dataset.acc || null; render(); }));
  app.querySelectorAll("[data-cycles]").forEach((b) => (b.onclick = () => { S.cycles = +b.dataset.cycles; render(); }));
  app.querySelectorAll("tr.tx").forEach((r) => (r.onclick = () => { S.selected = +r.dataset.i; render(); }));
  if ($("q")) {
    $("q").oninput = (e) => { S.q = e.target.value; S.selected = null; renderKeepingFocus("q"); };
  }
  if ($("cat")) $("cat").onchange = (e) => { S.category = e.target.value; render(); };
  if ($("period")) $("period").onchange = (e) => { S.period = e.target.value; render(); };
  if ($("close")) $("close").onclick = () => { S.selected = null; render(); };
  if (S.route === "import") importWire();
}

function renderKeepingFocus(id) {
  const el = document.getElementById(id);
  const at = el ? el.selectionStart : 0;
  render();
  const again = document.getElementById(id);
  if (again) { again.focus(); again.setSelectionRange(at, at); }
}

// ---- Overview -----------------------------------------------------------------------------

function overview() {
  const d = S.data, b = d.budget;
  if (!b) return `<div class="word">OVERVIEW</div><p class="empty">Nothing yet - open SpenDroid on your phone and sync.</p>`;
  const until = b.nextIncome ? `Until payday · ${fmtDay(b.nextIncome)} · ${b.days} day${b.days === 1 ? "" : "s"}` : "Your budget";
  const perDay = b.days > 0 ? `${money(Math.floor(b.available / b.days))} a day` : "";
  const total = (start, end) => Math.round((day(end) - day(start)) / 864e5) + 1;
  const dayOf = b.cycleEnd ? `day ${Math.round((day(b.asOf) - day(b.cycleStart)) / 864e5) + 1} of ${total(b.cycleStart, b.cycleEnd)}` : "";
  const others = (d.others || []).map((o) => {
    const label = o.against != null ? `${pounds(o.spent)} / ${pounds(o.against)}${o.toCome > 0 ? ` · ${pounds(o.toCome)} to come` : ""}` : pounds(o.spent);
    return `<div class="panel" style="background:linear-gradient(110deg,color-mix(in srgb,${o.colour} 40%,#000),var(--panel) 60%)">
      <div style="display:flex"><span class="sec">${esc(o.label)}</span><span class="k2" style="margin-left:auto">${o.kind === "STATEMENT" ? "this statement" : "this pay cycle"}</span></div>
      <div style="margin-top:10px">${bar(o.used, label, o.colour, o.gone, o.against ? o.toCome / o.against : 0)}</div></div>`;
  }).join("");
  const posters = (d.upcoming || []).map((p) => `<div class="poster"><div class="art" style="background:linear-gradient(160deg,${p.colour},color-mix(in srgb,${p.colour} 40%,#000))"><span class="c">${p.card ? "~" : ""}${fmtDay(p.date, { day: "numeric", month: "short" })}</span><span class="g">${p.card ? "▭" : esc(initial(p.name))}</span></div><div class="t">${esc(p.name)}</div><div class="d">${p.variable ? "about " : ""}${money(p.amount)}</div></div>`).join("");
  const notes = [b.model === "ROLLOVER" ? "balance carried over" : "", b.setAside > 0 ? `${money(b.setAside)} set aside towards quarterly and yearly bills` : ""].filter(Boolean).join(" · ");
  return `<div class="word">OVERVIEW</div>
  <div class="grid2"><div>
    <div style="color:#fff;opacity:.8;font-weight:700">${esc(until)}</div>
    <div class="hero-fig">${money(b.available)}</div>
    <div style="display:flex;gap:10px;align-items:center;color:#cfd2d8;margin-top:4px"><span class="badge" style="background:${paceColour(b.pace)}">${paceWord(b.pace)}</span>${esc([perDay, dayOf].filter(Boolean).join(" · "))}</div>
    ${notes ? `<div class="k2" style="margin-top:6px">left to spend, ${esc(notes)}</div>` : ""}
    <div class="panel" style="margin-top:18px"><div style="display:flex"><span class="sec">This cycle</span><span class="k2" style="margin-left:auto">used of budget</span></div>
      <div style="margin-top:10px">${bar(b.used, b.spendable > 0 ? `${pounds(b.usedMinor)} / ${pounds(b.spendable)}` : pounds(b.spentThisCycle), paceColour(b.pace), b.elapsed)}</div>
      <div style="display:flex;gap:28px;margin-top:12px"><div><div class="k2">Spent today</div><b>${money(b.spentToday)}</b></div><div><div class="k2">This cycle</div><b>${money(b.spentThisCycle)}</b></div></div></div>
  </div><div class="stack">${others || ""}</div></div>
  ${posters ? `<div class="accentbar"></div><span class="h2">Coming up</span> <span class="k2">before payday · ${money(d.upcoming.reduce((s, p) => s + p.amount, 0))}</span><div class="posters">${posters}</div>` : ""}
`;
}

// ---- Transactions -------------------------------------------------------------------------

function filtered() {
  const d = S.data, b = d.budget;
  const q = S.q.trim().toLowerCase();
  const since = S.period === "cycle" && b ? b.cycleStart : S.period === "30" ? iso(-30) : S.period === "90" ? iso(-90) : null;
  return d.transactions
    .map((t, i) => ({ ...t, i }))
    .filter((t) =>
      (S.tab !== "pending" || t.pending) &&
      (S.tab !== "bills" || t.recurring) &&
      (S.tab === "transfers" ? t.transfer : true) &&
      (!S.account || t.accountId === S.account) &&
      (!S.category || t.category === S.category) &&
      (!since || t.date >= since) &&
      (!q || t.payee.toLowerCase().includes(q) || t.description.toLowerCase().includes(q)));
}
function iso(offsetDays) {
  const d = new Date(); d.setDate(d.getDate() + offsetDays);
  return d.toISOString().slice(0, 10);
}

function transactions() {
  const d = S.data;
  const rows = filtered();
  const today = d.budget ? d.budget.asOf : iso(0);
  const yesterday = (() => { const x = day(today); x.setDate(x.getDate() - 1); return x.toISOString().slice(0, 10); })();
  let html = "", last = null;
  const groups = {};
  rows.forEach((t) => (groups[t.date] = (groups[t.date] || 0) + (t.amount < 0 ? -t.amount : 0)));
  for (const t of rows.slice(0, 600)) {
    if (t.date !== last) {
      last = t.date;
      const label = t.date === today ? "Today" : t.date === yesterday ? "Yesterday" : fmtDay(t.date, t.date.slice(0, 4) === today.slice(0, 4) ? undefined : { weekday: "short", day: "numeric", month: "short", year: "numeric" });
      html += `<tr class="day"><td colspan="4">${label}</td><td>${groups[t.date] ? money(groups[t.date]) : ""}</td></tr>`;
    }
    const c = category(t.category), a = account(t.accountId) || { label: "", colour: "#555" };
    const status = t.seen ? ["Seen", "var(--blue)"] : t.pending ? ["Pending", "var(--blue)"] : t.transfer ? ["Transfer", "var(--muted)"] : t.recurring ? ["Regular", "var(--muted)"] : null;
    html += `<tr class="tx ${S.selected === t.i ? "sel" : ""}" data-i="${t.i}">
      <td><div class="payee"><span class="tile" style="background:${c.colour}">${esc(initial(t.payee))}</span><span class="n">${esc(t.payee || "Unknown")}</span></div></td>
      <td>${esc(c.label)}</td>
      <td class="acc"><span class="acct"><i style="background:${a.colour}"></i>${esc(a.label)}</span></td>
      <td>${status ? `<span class="status" style="color:${status[1]}">${status[0]}</span>` : ""}</td>
      <td class="amt" style="${t.amount >= 0 ? "color:var(--in)" : ""}">${money(t.amount)}</td></tr>`;
  }
  const tabs = [["all", "All"], ["pending", "Pending"], ["bills", "Bills"], ["transfers", "Transfers"]].map(([id, l]) => `<button class="${S.tab === id ? "on" : ""}" data-tab="${id}">${l}</button>`).join("");
  const accChips = `<button class="chip ${!S.account ? "on" : ""}" data-acc="">All accounts</button>` + d.accounts.map((a) => `<button class="chip ${S.account === a.id ? "on" : ""}" data-acc="${esc(a.id)}">${esc(a.label)}</button>`).join("");
  const cats = `<select class="chip" id="cat"><option value="">Any category</option>${d.categories.map((c) => `<option value="${c.name}" ${S.category === c.name ? "selected" : ""}>${esc(c.label)}</option>`).join("")}</select>`;
  const period = `<select class="chip" id="period">${[["cycle", "This cycle"], ["30", "Last 30 days"], ["90", "Last 90 days"], ["all", "All time"]].map(([v, l]) => `<option value="${v}" ${S.period === v ? "selected" : ""}>${l}</option>`).join("")}</select>`;
  const spent = rows.filter((t) => t.amount < 0 && !t.transfer).reduce((s, t) => s - t.amount, 0);
  return `<div class="top"><span class="word">TRANSACTIONS</span><div class="tabs">${tabs}</div></div>
    <div class="bar-row"><input class="search" id="q" placeholder="Search ${d.transactions.length.toLocaleString("en-GB")} transactions" value="${esc(S.q)}">${period}${cats}</div>
    <div class="bar-row" style="margin-top:0">${accChips}<span class="k2" style="margin-left:auto">${rows.length.toLocaleString("en-GB")} shown · ${money(spent)} out</span></div>
    <div class="layout"><div class="list">${rows.length ? `<table><tr><th>Payee</th><th>Category</th><th class="acc">Account</th><th>Status</th><th style="text-align:right">Amount</th></tr>${html}</table>${rows.length > 600 ? `<p class="k2">Showing the first 600 - narrow the search to see more.</p>` : ""}` : `<p class="empty">Nothing matches.</p>`}</div>
    ${detail()}</div>`;
}

/** The transaction's page, beside the list: its tile as a poster, how its payee is going, what the bank sent. */
function detail() {
  if (S.selected == null) return `<aside class="detail hidden"></aside>`;
  const d = S.data, t = d.transactions[S.selected];
  if (!t) return "";
  const c = category(t.category), a = account(t.accountId) || { label: "" };
  const same = d.transactions.filter((x) => x.payee === t.payee && x.amount < 0);
  const since = d.budget ? d.budget.cycleStart : iso(-30);
  const now = same.filter((x) => x.date >= since);
  const spent = now.reduce((s, x) => s - x.amount, 0);
  return `<aside class="detail panel" style="padding:0;overflow:hidden"><button class="close" id="close">×</button>
    <div style="background:linear-gradient(180deg,color-mix(in srgb,${c.colour} 70%,#000),var(--panel));padding:18px">
      <span class="tile" style="width:64px;height:84px;font-size:28px;background:linear-gradient(160deg,${c.colour},color-mix(in srgb,${c.colour} 40%,#000))">${esc(initial(t.payee))}</span>
      <div style="font-size:20px;font-weight:900;margin-top:10px">${esc(t.payee || "Unknown")}</div>
      <div class="k2" style="color:#ddd">${fmtDay(t.date)} · ${esc(a.label)}</div>
      <div style="font-size:26px;font-weight:900;margin-top:4px;${t.amount >= 0 ? "color:var(--in)" : ""}">${money(t.amount)}</div></div>
    <div style="padding:14px 18px">
      <div style="display:flex;gap:6px;flex-wrap:wrap"><span class="badge" style="background:${c.colour};color:#fff">${esc(c.label)}</span><span class="badge" style="background:var(--high);color:#cfd2d8">${t.seen ? "Seen, not yet listed" : t.pending ? "Pending" : "Booked"}</span>${t.transfer ? `<span class="badge" style="background:var(--high);color:#cfd2d8">Transfer</span>` : ""}${t.recurring ? `<span class="badge" style="background:var(--high);color:#cfd2d8">Regular</span>` : ""}</div>
      ${t.amount < 0 && now.length ? `<div class="sec" style="margin-top:16px">Here this cycle</div><div style="margin-top:8px">${bar(1, `${money(spent)} · ${now.length} visit${now.length === 1 ? "" : "s"}`, c.colour)}</div>` : ""}
      ${t.description ? `<div class="sec" style="margin-top:16px">As the bank sent it</div><div style="margin-top:6px;word-break:break-word">${esc(t.description)}</div>` : ""}
      <p class="k2" style="margin-top:16px;font-style:italic">Read-only for now - change it in the app.</p>
    </div></aside>`;
}

// ---- Regular ------------------------------------------------------------------------------

/** The month as the regular payments make it, at the head of Regular as in the app. */
function eachMonth() {
  const b = S.data.budget;
  if (!b) return "";
  return `<div class="panel" style="margin:16px 0 4px;max-width:820px"><div class="sec">Each month</div><div style="display:flex;gap:28px;margin-top:10px"><div><div class="k2">Income</div><b>${money(b.income)}</b></div><div><div class="k2">Bills</div><b>${money(b.bills)}</b></div><div><div class="k2">To spend</div><b style="color:var(--in)">${money(b.toSpend)}</b></div></div>
    ${b.income > 0 ? `<div style="margin-top:10px">${bar(b.bills / b.income, `${Math.round((b.bills / b.income) * 100)}% of income on bills`, b.bills / b.income > 0.8 ? "var(--bad)" : b.bills / b.income > 0.6 ? "var(--warn)" : "var(--good)")}</div>` : ""}</div>`;
}

function regular() {
  const d = S.data, b = d.budget;
  const tabs = [["upcoming", "Upcoming"], ["bills", "Bills"], ["income", "Income"], ["ignored", "Ignored"]].map(([id, l]) => `<button class="${S.regularTab === id ? "on" : ""}" data-rtab="${id}">${l}</button>`).join("");
  const cadence = { WEEKLY: "Weekly", FORTNIGHTLY: "Fortnightly", MONTHLY: "Monthly", QUARTERLY: "Quarterly", ANNUAL: "Yearly" };
  let body = "";
  if (S.regularTab === "upcoming") {
    const up = d.upcoming || [];
    const asOf = b ? b.asOf : iso(0);
    const week = (() => { const x = day(asOf); x.setDate(x.getDate() + 6); return x.toISOString().slice(0, 10); })();
    const row = (p) => `<div class="row"><span class="tile" style="width:44px;height:52px;background:${p.colour}">${p.card ? "▭" : esc(initial(p.name))}</span><div><b>${esc(p.name)}</b><div class="k2">${p.card ? "Card statement · estimated" : p.variable ? "Varies" : ""}</div></div><div class="a">${p.variable ? "about " : ""}${money(p.amount)}<small>${fmtDay(p.date)}</small></div></div>`;
    const soon = up.filter((p) => p.date <= week), later = up.filter((p) => p.date > week);
    body = `<div style="margin-top:16px"><div class="k2">Before payday${b && b.nextIncome ? " · " + fmtDay(b.nextIncome) : ""}</div><div style="font-size:24px;font-weight:900">${money(up.reduce((s, p) => s + p.amount, 0))} to go out</div></div>
      ${soon.length ? `<div style="color:var(--accent);font-weight:700;margin-top:14px">This week</div>${soon.map(row).join("")}` : ""}
      ${later.length ? `<div style="color:var(--accent);font-weight:700;margin-top:14px">Later</div>${later.map(row).join("")}` : ""}
      ${up.length ? "" : `<p class="empty">Nothing more due before payday.</p>`}`;
  } else {
    const list = d.rules.filter((r) => (S.regularTab === "ignored" ? r.ignored : !r.ignored && (S.regularTab === "income" ? r.income : !r.income)));
    body = list.length ? list.map((r) => {
      const from = r.paidFrom ? account(r.paidFrom) : null;
      return `<div class="row"><span class="tile" style="width:38px;height:38px;background:${payeeColour(r.payee)}">${esc(initial(r.payee))}</span><div><b>${esc(r.payee)}</b><div class="k2">${cadence[r.cadence] || r.cadence}${r.variable ? " · varies" : ""}${r.manual ? " · added by you" : ""}${from ? " · from " + esc(from.label) : ""} · last ${fmtDay(r.last, { day: "numeric", month: "short" })}</div></div><div class="a" style="${r.income ? "color:var(--in)" : ""}">${r.variable ? "about " : ""}${money(Math.abs(r.amount))}</div></div>`;
    }).join("") : `<p class="empty">None.</p>`;
  }
  return `<div class="top"><span class="word">REGULAR</span><div class="tabs">${tabs}</div></div>${eachMonth()}<div style="max-width:820px">${body}</div>`;
}

// ---- Insights -----------------------------------------------------------------------------

function insights() {
  const d = S.data, b = d.budget;
  const spend = d.transactions.filter((t) => t.amount < 0 && !t.transfer && !NOT_SPENDING.has(t.category));
  // Cycles from the dates the main income landed; without them, calendar months.
  let starts = (d.cycleStarts || []).slice();
  if (starts.length < 2) {
    const months = [...new Set(spend.map((t) => t.date.slice(0, 7)))].sort();
    starts = months.map((m) => m + "-01");
  }
  starts = starts.slice(-S.cycles);
  const bounds = starts.map((s, i) => [s, starts[i + 1] || "9999-12-31"]);
  const totals = bounds.map(([from, to]) => {
    const m = {};
    spend.filter((t) => t.date >= from && t.date < to).forEach((t) => (m[t.category] = (m[t.category] || 0) - t.amount));
    return m;
  });
  const overall = {};
  totals.forEach((m) => Object.entries(m).forEach(([k, v]) => (overall[k] = (overall[k] || 0) + v)));
  const top = Object.entries(overall).sort((x, y) => y[1] - x[1]).slice(0, 6).map((e) => e[0]);
  const peak = Math.max(1, ...totals.map((m) => Object.values(m).reduce((s, v) => s + v, 0)));
  const cols = totals.map((m, i) => {
    const sum = Object.values(m).reduce((s, v) => s + v, 0);
    const other = sum - top.reduce((s, k) => s + (m[k] || 0), 0);
    const parts = top.map((k) => [category(k).colour, m[k] || 0, category(k).label]).concat([["#5a5f68", other, "Everything else"]]).filter((p) => p[1] > 0);
    const last = i === totals.length - 1;
    return `<div class="col" style="${last ? "opacity:.55" : ""}" title="${money(sum)}">${parts.map(([c, v, l]) => `<div style="height:${(v / peak) * 100}%;background:${c}" title="${esc(l)}: ${money(v)}"></div>`).join("")}</div>`;
  }).join("");
  const labels = bounds.map(([from], i) => `<span class="k2">${fmtDay(from, { month: "short" })}${i === bounds.length - 1 ? " (so far)" : ""}</span>`).join("");
  const legend = top.map((k) => `<span class="k2"><b style="color:${category(k).colour}">■</b> ${esc(category(k).label)}</span>`).join("") + `<span class="k2"><b style="color:#5a5f68">■</b> Everything else</span>`;
  const thisCycle = totals[totals.length - 1] || {};
  const ranked = Object.entries(thisCycle).sort((x, y) => y[1] - x[1]).slice(0, 5);
  const since = b ? b.cycleStart : starts[starts.length - 1];
  const visits = {};
  spend.filter((t) => t.date >= since).forEach((t) => (visits[t.payee] = (visits[t.payee] || 0) + 1));
  const topVisits = Object.entries(visits).sort((x, y) => y[1] - x[1]).slice(0, 5);
  const earlier = totals.slice(0, -1);
  const usual = (k) => (earlier.length ? earlier.reduce((s, m) => s + (m[k] || 0), 0) / earlier.length : 0);
  const change = Object.keys(thisCycle).map((k) => [k, thisCycle[k] - usual(k)]).sort((x, y) => Math.abs(y[1]) - Math.abs(x[1])).slice(0, 4);
  const chips = [3, 6, 12].map((n) => `<button class="chip ${S.cycles === n ? "on" : ""}" data-cycles="${n}">${n} cycles</button>`).join("");
  return `<div class="top"><span class="word">INSIGHTS</span><div style="margin-left:auto;display:flex;gap:8px">${chips}</div></div>
    <div class="panel" style="margin-top:18px"><div style="display:flex"><span class="sec">Spending by cycle</span><span class="k2" style="margin-left:auto">stacked by category · hover a block for its figure</span></div>
      <div class="chart">${cols}</div><div class="chart lbl" style="height:auto;margin:0">${labels}</div><div class="legend">${legend}</div></div>
    <div style="display:grid;grid-template-columns:repeat(auto-fit,minmax(260px,1fr));gap:16px;margin-top:16px">
      <div class="panel"><div class="sec">Most spent · this cycle</div>${ranked.map(([k, v], i) => `<div class="rank"><span>${i + 1} ${esc(category(k).label)}</span><b class="v" style="color:var(--accent)">${money(v)}</b></div>`).join("") || `<p class="k2">Nothing yet.</p>`}</div>
      <div class="panel"><div class="sec">Most visited · this cycle</div>${topVisits.map(([k, v], i) => `<div class="rank"><span>${i + 1} ${esc(k)}</span><b class="v" style="color:var(--blue)">${v}</b></div>`).join("") || `<p class="k2">Nothing yet.</p>`}</div>
      <div class="panel"><div class="sec">Biggest change · against usual</div>${change.map(([k, v]) => `<div class="rank"><span>${esc(category(k).label)}</span><b class="v" style="color:${v > 0 ? "var(--bad)" : "var(--in)"}">${v > 0 ? "+" : "−"}${money(Math.abs(v))}</b></div>`).join("") || `<p class="k2">Needs a cycle or two.</p>`}</div>
    </div>`;
}

boot();
