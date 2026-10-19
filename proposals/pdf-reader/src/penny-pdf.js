// Penny's PDF statement reader. Pure logic: give it each page's words with their positions
// (as pdf.js reports them) and it gives back the transactions it found, what it made of every
// line, and whether the statement's own totals agree. Nothing here touches the network.

const MONTHS = { jan: 1, feb: 2, mar: 3, apr: 4, may: 5, jun: 6, jul: 7, aug: 8, sep: 9, sept: 9, oct: 10, nov: 11, dec: 12 };
const MONTH_RE = "(jan(?:uary)?|feb(?:ruary)?|mar(?:ch)?|apr(?:il)?|may|june?|july?|aug(?:ust)?|sept?(?:ember)?|oct(?:ober)?|nov(?:ember)?|dec(?:ember)?)";

const MONTH_NC = "(?:" + MONTH_RE.slice(1); // the same, without capturing

// A date at the very start of a line: "27 MAR", "27 Mar 26", "27 March 2026", "27/03/2026", "27-03-26", "27/03".
const LEAD_DATE = new RegExp(
  "^(?:(\\d{1,2})[ \\-]" + MONTH_RE + "\\.?(?:[ \\-,]+(\\d{4}|\\d{2})(?![\\d])(?!\\s*[ \\-]?" + MONTH_NC + "))?(?![a-z])|(\\d{1,2})[/\\-.](\\d{1,2})(?:[/\\-.](\\d{4}|\\d{2}))?)(?=\\s|$)",
  "i",
);
// A sum of money: "1,234.56", "£1,234.56", "-£3.20", "(626.04)", "626.04 -", "12.50CR", "12.50 DR".
const AMOUNT = /^(\()?([-+])?\s?£?\s?(\d{1,3}(?:,\d{3})*|\d+)\.(\d{2})\)?(?:\s?(CR|DR|C|D|-|\+))?$/i;
const TRAILING_AMOUNT = /(?:^|\s)((?:\()?[-+]?\s?£?\s?(?:\d{1,3}(?:,\d{3})*|\d+)\.\d{2}\)?(?:\s?(?:CR|DR|C|D|-|\+))?)$/i;

// Lines that are the statement checking itself, never transactions.
const CHECK_LINES = [
  { kind: "opening", re: /balance (brought|carried) forward from|balance brought|brought forward|balance from previous statement|previous (statement )?balance|opening balance|start balance/i },
  { kind: "closing", re: /balance carried|carried forward|closing balance|new balance|statement balance|end balance|current balance/i },
  { kind: "subtotal", re: /sub[- ]?total/i },
  { kind: "total", re: /^\s*total\b/i },
];

const heading = (words) => new RegExp("^(?:\\(?£\\)?\\s*)?(?:" + words + ")(?:\\s*\\(?£\\)?)?$", "i");
const HEADER_AMOUNT_WORDS = [
  { kind: "out", re: heading("paid out|money out|out|debits?|withdrawals?|withdrawn|payments? out|payments|spent") },
  { kind: "in", re: heading("paid in|money in|in|credits?|deposits?|receipts?|payments? in") },
  { kind: "balance", re: heading("(?:total |running |available |account )?balance") },
  { kind: "amount", re: heading("amount|value|sum|how much") },
];
// A bank's code for the kind of payment, at the start of its details: dropped from the description.
const TYPE_CODE = /^(\){3,4}|VIS|DD|BP|SO|STO|CR|DR|TFR|CHQ|ATM|CPT|FPI|FPO|BGC|POS|DEB|DEP|CC|FEE|INT|OBP|PAY|BAC|COR|CSH|MPI|MPO|SBT|TLR|CHG|ADV)\s+/;
const TYPE_CELL = /^(\){3,4}|VIS|DD|BP|SO|STO|CR|DR|TFR|CHQ|ATM|CPT|FPI|FPO|BGC|POS|DEB|DEP|CC|FEE|INT|OBP|PAY|BAC|COR|CSH|MPI|MPO|SBT|TLR|CHG|ADV)$/;
// Totals in a summary box worth checking against: "Payments In £1,234.56", "Payments Out ...".
const SUMMARY_TOTALS = [
  { kind: "totalIn", re: /(?:payments|money|paid|total) in\b|total deposits|deposits\b/i },
  { kind: "totalOut", re: /(?:payments|money|paid|total) out\b|withdrawn\b|total withdrawals|withdrawals\b/i },
];
const HEADER_DATE = /\b(date|when)\b/i;
const HEADER_DESC = /\b(description|details|transaction|what|narrative|merchant|payee)\b/i;
const FOOTER = /^(page \d+( of \d+)?|continued( overleaf| on next page)?|\d+ of \d+)$/i;
const CARD_HINTS = /credit limit|card ending|available credit|credit card statement/i;

const WHOLE = /^(\()?([-+])?\s?£?\s?(\d{1,3}(?:,\d{3})+|\d{1,7})\)?(?:\s?(CR|DR|-))?$/i;

export function parseAmount(text, whole = false) {
  let m = AMOUNT.exec(text.trim());
  if (!m && whole) {
    // A statement in whole pounds - "2,000", "15" - as mock statements are; never a real bank's.
    const w = WHOLE.exec(text.trim());
    if (w) m = [w[0], w[1], w[2], w[3], "00", w[4]];
  }
  if (!m) return null;
  const pence = parseInt(m[3].replace(/,/g, ""), 10) * 100 + parseInt(m[4], 10);
  const marker = (m[5] || "").toUpperCase();
  // How the statement marked it: "-" before or after, brackets, CR or DR - or nothing at all.
  let mark = "none";
  if (marker === "-") mark = "trail-minus"; // "626.04 -": a card's credit
  else if (m[1] || m[2] === "-") mark = "minus";
  else if (marker === "CR" || marker === "C") mark = "cr";
  else if (marker === "DR" || marker === "D") mark = "dr";
  else if (m[2] === "+" || marker === "+") mark = "plus";
  return { pence, mark };
}

function signedForCheck(a, card) {
  // A balance as printed. A card's is what's owed, so a minus or CR is below zero; an account's
  // is what's in it, so a minus, DR or D (overdrawn) is.
  const below = card ? ["minus", "trail-minus", "cr"] : ["minus", "trail-minus", "dr"];
  return below.includes(a.mark) ? -a.pence : a.pence;
}

const SCREEN_READER = /^(column|[a-z ]+(\(£\))?\s*blank)$/i;
const HEADING_WORD = /^(date|description|details|type|money (in|out)|paid (in|out)|balance|amount)(\s*\(£\))?$/i;

/** Words to lines: items on the same baseline, left to right, merged into cells where they touch. */
export function toLines(page) {
  const items = page.items
    .filter((it) => it.str && it.str.trim())
    .map((it) => ({ str: it.str, x: it.x, y: it.y, w: it.w, h: Math.max(it.h || 0, 4) }))
    .sort((a, b) => a.y - b.y || a.x - b.x);
  const lines = [];
  for (const it of items) {
    const line = lines.length ? lines[lines.length - 1] : null;
    if (line && Math.abs(it.y - line.y) <= Math.max(2, line.h * 0.45)) line.items.push(it);
    else lines.push({ y: it.y, h: it.h, items: [it] });
  }
  return lines.map((line) => {
    line.items.sort((a, b) => a.x - b.x);
    const cells = [];
    for (const it of line.items) {
      const cell = cells[cells.length - 1];
      const gap = cell ? it.x - (cell.x + cell.w) : Infinity;
      if (cell && gap < line.h * 0.9) {
        cell.text += (gap > line.h * 0.12 && !cell.text.endsWith(" ") && !it.str.startsWith(" ") ? " " : "") + it.str;
        cell.w = it.x + it.w - cell.x;
      } else {
        cells.push({ text: it.str, x: it.x, w: it.w });
      }
    }
    cells.forEach((c) => {
      // Lloyds writes "01 Feb 24." and "186.57."; and its screen-reader labels - "Column",
      // "Money In (£)blank." - are in the text too, though nothing shows on the page.
      c.text = c.text.replace(/\s+/g, " ").trim().replace(/\.$/, "").trim();
      // Print-room marks a statement carries invisibly - "%%eyeball2" - even run on to a date.
      c.text = c.text.replace(/%%[a-z]+?\d(?=\d{2}[-\/.]\d{2}[-\/.]\d{2,4})/i, "").replace(/%%[a-z]+\d*/gi, "").trim();
      if (SCREEN_READER.test(c.text)) c.text = "";
      c.right = c.x + c.w;
    });
    const kept = cells.filter((c) => c.text);
    return { y: line.y, h: line.h, cells: kept, text: kept.map((c) => c.text).join("  ").trim() };
  });
}

/** The period the statement covers, for the year of dates printed without one. */
export function findPeriod(allText) {
  const full = new RegExp("(\\d{1,2})(?:st|nd|rd|th)? " + MONTH_RE + ",? (\\d{4})", "gi");
  const range = new RegExp(
    "(\\d{1,2}) " + MONTH_RE + "(?:,? (\\d{4}))?\\s*(?:-|–|to)\\s*(\\d{1,2}) " + MONTH_RE + ",? (\\d{4})", "i",
  );
  const slashRange = /(\d{1,2})\/(\d{1,2})\/(\d{4})\s*(?:-|–|to)\s*(\d{1,2})\/(\d{1,2})\/(\d{4})/i;
  let m = range.exec(allText);
  if (m) {
    const endY = +m[6];
    const end = ymd(endY, monthOf(m[5]), +m[4]);
    const start = ymd(m[3] ? +m[3] : (monthOf(m[2]) > monthOf(m[5]) ? endY - 1 : endY), monthOf(m[2]), +m[1]);
    return { start, end, how: "period" };
  }
  m = slashRange.exec(allText);
  if (m) return { start: ymd(+m[3], +m[2], +m[1]), end: ymd(+m[6], +m[5], +m[4]), how: "period" };
  // Else the latest full date written on it: a statement date.
  let latest = null;
  for (const f of allText.matchAll(full)) {
    const d = ymd(+f[3], monthOf(f[2]), +f[1]);
    if (!latest || d > latest) latest = d;
  }
  if (latest) return { start: null, end: latest, how: "latest date" };
  // "Issue date: 15 MAR 13", "Statement date 01/02/26": a two-digit year where it's labelled.
  const labelled = new RegExp("(?:statement|issue|print(?:ed)?)\\s+date:?\\s*(\\d{1,2})[ \\-]" + MONTH_RE + "[ \\-](\\d{2})\\b", "i").exec(allText);
  if (labelled) return { start: null, end: ymd(2000 + +labelled[3], monthOf(labelled[2]), +labelled[1]), how: "statement date" };
  const slash = /(?:statement|issue|print(?:ed)?)\s+date:?\s*(\d{1,2})\/(\d{1,2})\/(\d{2,4})\b/i.exec(allText);
  if (slash) return { start: null, end: ymd(slash[3].length === 2 ? 2000 + +slash[3] : +slash[3], +slash[2], +slash[1]), how: "statement date" };
  return null;
}

function monthOf(name) { return MONTHS[name.toLowerCase().slice(0, name.toLowerCase().startsWith("sept") ? 4 : 3)] || MONTHS[name.toLowerCase().slice(0, 3)]; }
function ymd(y, m, d) { return `${y}-${String(m).padStart(2, "0")}-${String(d).padStart(2, "0")}`; }

/** The dates at the start of a line, and the text left after them. */
function leadingDates(text) {
  const dates = [];
  let rest = text;
  for (let i = 0; i < 2; i++) {
    const m = LEAD_DATE.exec(rest);
    if (!m) break;
    if (m[1]) dates.push({ d: +m[1], m: monthOf(m[2]), y: m[3] ? +m[3] : null });
    else {
      const d = +m[4], mo = +m[5];
      if (mo < 1 || mo > 12 || d < 1 || d > 31) break;
      dates.push({ d, m: mo, y: m[6] ? +m[6] : null });
    }
    rest = rest.slice(m[0].length).trim();
  }
  return { dates, rest };
}

function resolve(date, period) {
  let y = date.y;
  if (y != null && y < 100) y += 2000;
  if (y == null) {
    const end = period ? period.end : null;
    y = end ? +end.slice(0, 4) : new Date().getFullYear();
    // "27 DEC" on a statement ending in January is last year's.
    if (end && ymd(y, date.m, date.d) > addDays(end, 31)) y -= 1;
  }
  return ymd(y, date.m, date.d);
}
function addDays(iso, n) { const t = new Date(iso + "T00:00:00Z"); t.setUTCDate(t.getUTCDate() + n); return t.toISOString().slice(0, 10); }

/** Headings stacked over two or three lines - "Money" over "out", "Trans" over "Date" - as one. */
function stacked(line, above, below) {
  const words = (l) => l && Math.abs(l.y - line.y) < line.h * 1.9 && !LEAD_DATE.test(l.text) &&
    l.cells.every((c) => c.text.length <= 20 && !/\d/.test(c.text)) ? l.cells : [];
  const parts = [...words(above).map((c) => ({ ...c, order: 0 })), ...line.cells.map((c) => ({ ...c, order: 1 })), ...words(below).map((c) => ({ ...c, order: 2 }))];
  const groups = [];
  for (const c of parts.sort((a, b) => a.x - b.x)) {
    const g = groups.find((g) => c.x <= g.right + 3 && g.x <= c.right + 3 && !g.parts.some((q) => q.order === c.order));
    if (g) { g.parts.push(c); g.x = Math.min(g.x, c.x); g.right = Math.max(g.right, c.right); }
    else groups.push({ x: c.x, right: c.right, parts: [c] });
  }
  return groups.map((g) => ({ x: g.x, right: g.right, w: g.right - g.x, text: g.parts.sort((a, b) => a.order - b.order).map((q) => q.text).join(" ") }));
}

const ANY_HEADING = /(paid out|paid in|money out|money in|withdrawn|withdrawals?|deposits?|debits?|credits?|(?:total |running )?balance|amount)(\s*\(?£\)?)?/gi;

/** "Paid In(£) Withdrawn(£)" run together in one cell: two headings, each where it sits. */
function splitHeadings(cells) {
  return cells.flatMap((c) => {
    const found = [...c.text.matchAll(ANY_HEADING)];
    if (found.length < 2) return [c];
    const per = c.w / Math.max(c.text.length, 1);
    return found.map((m) => ({ ...c, text: m[0].trim(), x: c.x + m.index * per, right: c.x + (m.index + m[0].length) * per, w: m[0].length * per }));
  });
}

/** A header row: a date heading, and at least one money heading. Returns its money columns. */
function headerOf(line, above, below) {
  if (!HEADER_DATE.test(line.text)) return null;
  const columns = [];
  for (const c of splitHeadings(stacked(line, above, below))) {
    // A cell may hold several headings run together: "Paid out  Paid in  Balance" stays split by cells.
    for (const w of HEADER_AMOUNT_WORDS) {
      if (w.re.test(c.text.trim())) { columns.push({ kind: w.kind, x: c.x, right: c.right, label: c.text }); break; }
    }
  }
  if (!columns.length) return null;
  const desc = stacked(line, above, below).find((c) => HEADER_DESC.test(c.text));
  return { columns, descX: desc ? desc.x : null };
}

function nearestColumn(cell, columns) {
  let best = null, bestD = Infinity;
  for (const col of columns) {
    // Figures sit under their heading, usually right-aligned to it.
    const d = Math.min(Math.abs(cell.right - col.right), Math.abs((cell.x + cell.right) / 2 - (col.x + col.right) / 2));
    if (d < bestD) { bestD = d; best = col; }
  }
  return bestD <= 70 ? best : null;
}

/** The money on a line: cells that are amounts, or end in one, matched to the header's columns. */
function amountsOf(line, columns, whole = false) {
  const found = [];
  const textCells = [];
  for (const c of line.cells) {
    let amt = parseAmount(c.text);
    if (!amt && whole && columns) {
      const a = parseAmount(c.text, true);
      const col = a && nearestColumn(c, columns);
      // Exactly under a money column: right-aligned to its heading, or centred under it.
      if (col && Math.min(Math.abs(c.right - col.right), Math.abs((c.x + c.right) / 2 - (col.x + col.right) / 2)) < 25) amt = a;
    }
    let cell = c, rest = null;
    if (!amt) {
      const t = TRAILING_AMOUNT.exec(c.text);
      if (t && c.text.length > t[1].length) {
        const a = parseAmount(t[1]);
        // A figure run on to the end of the text before it: only where it sits under a money column.
        const width = (c.w / c.text.length) * t[1].length;
        const tail = { text: t[1], x: c.right - width, right: c.right, w: width };
        if (a && (!columns || nearestColumn(tail, columns))) { amt = a; cell = tail; rest = c.text.slice(0, c.text.length - t[1].length).trim(); }
      }
    }
    if (amt) {
      const col = columns ? nearestColumn(cell, columns) : null;
      found.push({ ...amt, col, x: cell.x, right: cell.right, text: cell.text });
      if (rest) textCells.push({ ...c, text: rest });
    } else {
      textCells.push(c);
    }
  }
  return { amounts: found, textCells };
}

/**
 * The whole statement. [pages] is a list of { items: [{ str, x, y, w, h }] } with y measured
 * down from the top of the page.
 */
export function readStatement(pages) {
  const pageLines = pages.map(toLines);
  const allText = pageLines.map((ls) => ls.map((l) => l.text).join("\n")).join("\n");
  const period = findPeriod(allText);
  // Which way round its figures go. A statement with Paid out and Paid in columns says so;
  // one with a single Amount column says it with its marks: "626.04 -" or "12.50CR" on the odd
  // line means the plain figures are spending (a card); "-3.20" or "DR" means they're money in.
  const headersOf = (ls) => ls.map((l, i) => headerOf(l, ls[i - 1], ls[i + 1]));
  const pageHeaders = pageLines.map(headersOf);
  const hasInOut = pageHeaders.some((hs) => hs.some((h) => h && h.columns.some((c) => c.kind === "out" || c.kind === "in")));
  // Pence on any figure after a heading means figures without them aren't money.
  const afterHeading = pageLines.flatMap((ls, p) => ls.filter((l, i) => pageHeaders[p].slice(0, i).some(Boolean)));
  const whole = afterHeading.length > 0 && !afterHeading.some((l) => /\d\.\d{2}\b/.test(l.text));
  const marks = { credit: 0, signed: 0 };
  pageLines.forEach((ls) => ls.forEach((l) => l.cells.forEach((c) => {
    const t = TRAILING_AMOUNT.exec(c.text);
    const a = parseAmount(c.text) || (t && parseAmount(t[1]));
    if (!a) return;
    if (a.mark === "cr" || a.mark === "trail-minus") marks.credit++;
    if (a.mark === "minus" || a.mark === "dr") marks.signed++;
  })));
  const isCard = !hasInOut && (marks.credit > 0 || marks.signed === 0 || CARD_HINTS.test(allText));
  let undated = false;
  const notes = [];
  const transactions = [];
  const report = []; // per page, what each line was taken as
  const checks = [];

  let columns = null, descX = null, sawHeader = false;
  // Where descriptions really start, learnt from dated rows: a heading is often centred over them.
  let descStart = null;
  let closed = false; // after the closing balance: the table is over until a heading starts another
  let closedForPage = false; // after "carried forward": over for this page, on again at the next
  let openAtPageEnd = false; // the list ran to the foot of the last page without a closing line
  let dateX = null; // where dates sit, for a year printed on the line below one
  let pendingSummary = null; // "Balance brought forward from" / "£1,234.56" / "previous statement"
  let lastDate = null; // for statements that print the date once per day
  // Running sums, the way the statement keeps them.
  let section = 0, sectionRows = 0; // since the last sub-total, as the statement adds them (owed, for a card)
  let running = null; // a current account's balance, row by row
  let opening = null, total = 0;
  const summary = {};

  // Where the amount goes: on the first line of a transaction (most cards), or on the last, after
  // the date and a line or two of details (First Direct, HSBC and others). Dated lines with no
  // money on them, in a table, mean the second.
  let amountLast = false;
  {
    let afterHeader = false, dateOnly = 0;
    for (const [p, ls] of pageLines.entries()) for (const [i, l] of ls.entries()) {
      if (pageHeaders[p][i]) { afterHeader = true; continue; }
      if (!afterHeader) continue;
      const lead = leadingDates(l.text);
      const hasMoney = l.cells.some((c) => parseAmount(c.text, whole) || TRAILING_AMOUNT.exec(c.text));
      if (lead.dates.length && !hasMoney && !CHECK_LINES.some((c) => c.re.test(lead.rest))) dateOnly++;
    }
    amountLast = dateOnly >= 2;
  }

  /** A description without the bank's payment-type code in front: ")))", "DD", "VIS". */
  const tidy = (text) => {
    let t = leadingDates(text.trim()).rest;
    const code = TYPE_CODE.exec(t);
    if (code) t = t.slice(code[0].length);
    return t.replace(/\s{2,}/g, "  ").trim();
  };

  pageLines.forEach((lines, p) => {
    if (closedForPage) { closed = false; closedForPage = false; }
    const seen = [];
    let headerHere = false;
    let prevTx = null, prevY = null;
    // Details waiting for their amount, or run on from the transaction above.
    let pending = null; // { dates, parts, y, before } - before: no transaction above it to belong to
    const flush = () => {
      // Left over: on a statement whose amounts come first, the end of the transaction above.
      if (pending && !amountLast && !pending.dates && !pending.before && prevTx && pending.parts.length) prevTx.description += " " + pending.parts.join(" ");
      pending = null;
    };
    const tableRows = lines.filter((l) => leadingDates(l.text).dates.length).length;
    for (const [li, line] of lines.entries()) {
      const tag = (kind, extra = {}) => seen.push({ text: line.text, kind, ...extra });
      if (!line.cells.length) continue; // nothing but screen-reader labels
      const h = pageHeaders[p][li];
      if (h) {
        flush();
        // The same heading again, row by row - as a screen-reader-friendly PDF repeats it - keeps
        // the full heading's columns; it only says a new row starts.
        const repeat = columns && headerHere && h.columns.every((c) => columns.some((k) => k.kind === c.kind));
        if (!repeat) {
          if (h.descX !== descX) descStart = null;
          columns = h.columns; descX = h.descX ?? descX;
        }
        sawHeader = true; headerHere = true; closed = false;
        tag("header", { columns: h.columns.map((c) => c.kind), repeat });
        prevTx = null;
        continue;
      }
      // Headings alone - "Description", "Money In (£)" - are labels, not details.
      if (line.cells.length && line.cells.every((c) => HEADING_WORD.test(c.text))) { tag("ignored"); continue; }
      // Before any table on this page - a cover page, the top of a page - only checks are read.
      const tableOpen = sawHeader && !closed && (headerHere || tableRows >= 3 || (openAtPageEnd && tableRows >= 1));
      const { dates, rest } = leadingDates(line.text);
      const { amounts, textCells } = amountsOf(line, tableOpen ? columns : null, whole);
      // A check line names itself before its figure: "Closing balance 1,234.56", never a note
      // after one ("... 25,092  closing balance,").
      const firstMoney = amounts.length ? (rest || line.text).indexOf(amounts[0].text.trim()) : -1;
      const check = CHECK_LINES.find((c) => {
        const m = c.re.exec(rest || line.text);
        return m && (firstMoney < 0 || m.index < firstMoney);
      });

      if (!tableOpen) {
        // A cover page or summary box: "Previous balance £410.20", "Opening Balance £1,234.56D",
        // "Payments In £2,000.00" - kept to check against at the end, never counted.
        let found = false;
        const alone = parseAmount(line.text.replace(/^[=+]\s*/, ""));
        if (pendingSummary && alone) {
          if (summary[pendingSummary] == null) summary[pendingSummary] = signedForCheck(alone, isCard);
          pendingSummary = null;
          tag("summary");
          continue;
        }
        pendingSummary = null;
        // "Balance on 01 February 2024. £4,978.68." - the first such is the opening, the next the closing.
        const on = /balance on \d{1,2} [a-z]+ \d{4}[^£\d]{0,6}(£?[\d,]+\.\d{2})/i.exec(line.text);
        if (on) {
          const a = parseAmount(on[1]);
          if (a) { found = true; if (summary.opening == null) summary.opening = signedForCheck(a, isCard); else if (summary.closing == null) summary.closing = signedForCheck(a, isCard); }
        }
        for (const k of [...CHECK_LINES.filter((c) => c.kind === "opening" || c.kind === "closing"), ...SUMMARY_TOTALS]) {
          const m = new RegExp("(?:" + k.re.source + ")[^£\\d(\\-]{0,25}(\\(?-?£?\\s?[\\d,]+\\.\\d{2}\\)?(?:\\s?(?:CR|DR|C|D|-(?!\\d)))?)", "i").exec(line.text);
          const a = m && parseAmount(m[m.length - 1]);
          if (!a) { if (k.re.test(line.text) && !SUMMARY_TOTALS.includes(k)) pendingSummary = k.kind; continue; }
          found = true;
          const value = SUMMARY_TOTALS.includes(k) ? a.pence : signedForCheck(a, isCard);
          const weak = k.kind === "closing" && /carried/i.test(m[0]);
          if (k.kind === "closing" && summary.closing != null && summary.closingWeak && !weak) {
            // "Balance carried forward £0.00 ... Statement balance £877.97": the first was last time's.
            if (summary.opening == null) summary.opening = summary.closing;
            summary.closing = value; summary.closingWeak = false;
          } else if (summary[k.kind] == null) {
            summary[k.kind] = value;
            if (k.kind === "closing") summary.closingWeak = weak;
          }
        }
        tag(found ? "summary" : "ignored");
        continue;
      }

      if (check && amounts.length) {
        flush();
        // "14 Feb Interim balance brought forward": the day the rows below it belong to.
        if (dates.length) lastDate = dates;
        const a = amounts[amounts.length - 1];
        const value = signedForCheck(a, isCard);
        const counted = isCard ? total : (running ?? total);
        if (check.kind === "opening" && opening == null) {
          opening = value; section += value; running = value; total += value;
          tag("check", { check: "opening", value });
        } else if (check.kind === "opening") {
          // Brought forward again at the top of a page: it should be where the last page ended.
          const ok = Math.abs(counted - value) < 1;
          checks.push({ what: "Brought forward", printed: value, counted, ok, page: p + 1 });
          tag("check", { check: "opening", value, ok });
          if (!ok) { running = value; }
        } else if (check.kind === "subtotal" || check.kind === "total") {
          const ok = Math.abs(section - value) < 1;
          checks.push({ what: check.kind === "subtotal" ? "Sub-total" : "Total", printed: value, counted: section, ok, page: p + 1 });
          tag("check", { check: check.kind, value, ok });
          if (check.kind === "subtotal") { section = 0; sectionRows = 0; }
        } else {
          // A card adds up what's owed; an account follows its balance.
          const ok = Math.abs(counted - value) < 1;
          checks.push({ what: /carried/i.test(line.text) ? "Carried forward" : "Closing balance", printed: value, counted, ok, page: p + 1 });
          tag("check", { check: "closing", value, ok });
          // Whatever follows - interest rates, a summary of fees - isn't the statement's list. A
          // balance carried forward only ends the page: the list goes on overleaf.
          closed = true;
          closedForPage = /carried/i.test(line.text);
        }
        prevTx = null;
        continue;
      }

      // A transaction: money in a money column, and a date - its own, the day's above it, or the
      // one its details started under.
      const money = amounts.filter((a) => a.col && a.col.kind !== "balance");
      const balance = amounts.find((a) => a.col && a.col.kind === "balance");
      const descFrom = descStart ?? descX;
      const inDescColumn = descFrom == null || (line.cells[0] && line.cells[0].x >= descFrom - 8);
      const near = (y) => line.y - y < line.h * 4.5;
      if (money.length && !/\d%/.test(line.text)) {
        let ds = null, prefix = [];
        if (dates.length) {
          if (pending && pending.before && !pending.dates && near(pending.y)) { prefix = pending.parts; pending = null; }
          flush(); ds = dates; dateX = line.cells[0].x;
          const first = line.cells.find((c) => !LEAD_DATE.test(c.text) && !parseAmount(c.text, whole));
          if (first && first.x > line.cells[0].x) descStart = descStart == null ? first.x : Math.min(descStart, first.x);
        }
        else if (amountLast && pending && near(pending.y)) { ds = pending.dates || lastDate; prefix = pending.parts; pending = null; }
        else { flush(); if (lastDate && (inDescColumn || amountLast)) ds = lastDate; }
        if (ds) {
          lastDate = ds;
          let description = textCells.filter((c) => !TYPE_CELL.test(c.text)).map((c) => c.text).join("  ");
          description = leadingDates(description).rest;
          let reference = null;
          // A reference before the details: 8 digits on a consumer card, 23 on a commercial one.
          const r = /^(\d{6,24})\s+(.*)$/.exec(description);
          if (r) { reference = r[1]; description = r[2]; }
          description = tidy([...prefix, description].filter(Boolean).join("  "));
          const a = money.find((m) => m.pence !== 0) ?? money[0];
          // Signs: by the column where there's one for each way, else by the statement's marks.
          // Some banks fill both - "Payments Out £0.00  Payments In £30.00" - so they're netted.
          let pence;
          if (money.every((m) => m.col.kind === "out" || m.col.kind === "in")) pence = money.reduce((t, m) => t + (m.col.kind === "in" ? m.pence : -m.pence), 0);
          else if (isCard) pence = ["minus", "trail-minus", "cr"].includes(a.mark) ? a.pence : -a.pence; // a card: plain figures are spending
          else pence = ["minus", "trail-minus", "dr"].includes(a.mark) ? -a.pence : a.pence;
          if (ds.some((d) => d.y == null)) undated = true;
          const tx = {
            date: resolve(ds[0], period),
            postDate: ds[1] ? resolve(ds[1], period) : null,
            description,
            reference,
            pence,
            page: p + 1,
          };
          Object.defineProperty(tx, "ds", { value: ds, enumerable: false });
          transactions.push(tx);
          // What the statement adds up: owed, for a card; the balance, for an account.
          section += isCard ? -pence : pence; sectionRows++;
          total += isCard ? -pence : pence;
          if (running != null) running += pence;
          if (balance) {
            const printed = signedForCheck(balance, isCard);
            Object.defineProperty(tx, "printed", { value: printed, enumerable: false });
            if (running == null) running = printed; // the first balance shown starts the count
            tx.balanceOk = Math.abs(running - printed) < 1;
            if (!tx.balanceOk) { checks.push({ what: `Balance after ${tx.description.slice(0, 24)}`, printed, counted: running, ok: false, page: p + 1 }); running = printed; }
            else tx.balanceChecked = true;
          }
          tag("transaction", { pence, date: tx.date });
          prevTx = tx; prevY = line.y;
          continue;
        }
      }

      if (FOOTER.test(line.text.trim())) { tag("footer"); continue; }
      // "03 Nov" with "25" printed under it: that row's year.
      const yy = /^(\d{2})$/.exec(line.cells[0].text);
      const above = prevTx && near(prevY) ? prevTx
        : !prevTx && !transactions.some((t) => t.page === p + 1) ? transactions[transactions.length - 1] : null; // over the page
      if (yy && above && dateX != null && Math.abs(line.cells[0].x - dateX) < 8 && above.ds.every((d) => d.y == null)) {
        const year = 2000 + +yy[1];
        above.ds.forEach((d) => { d.y = year; });
        above.date = resolve(above.ds[0], period);
        if (above.ds[1]) above.postDate = resolve(above.ds[1], period);
        tag("year");
        continue;
      }
      if (dates.length && !amounts.length && !amountLast && tableOpen) { flush(); lastDate = dates; dateX = line.cells[0].x; tag("date"); continue; }
      // A dated line with no money: where a transaction's details begin, on a statement that puts
      // the amount last.
      if (dates.length && !amounts.length && amountLast) {
        flush();
        lastDate = dates;
        pending = { dates, parts: [rest], y: line.y };
        tag("details");
        continue;
      }
      // Details without a date or money: more of the transaction above, or the start of the next.
      if (!amounts.length && !dates.length && line.text.length < 80 && tableOpen &&
          ((pending && near(pending.y)) || (prevTx && near(prevY)) || (!prevTx && headerHere)) &&
          (descFrom == null || line.cells[0].x >= descFrom - 8 || amountLast)) {
        if (!pending) pending = { dates: null, parts: [], y: line.y, before: !prevTx };
        pending.parts.push(line.text);
        pending.y = line.y;
        tag(amountLast ? "details" : "continued");
        continue;
      }
      flush();
      tag("ignored");
      if (line.y - (prevY ?? 0) > line.h * 2.2) prevTx = null;
    }
    flush();
    openAtPageEnd = sawHeader && !closed && transactions.some((t) => t.page === p + 1);
    report.push(seen);
  });

  // The statement's own summary: what was owed before, plus this period, should be what's owed now.
  if (summary.closing != null) {
    const start = summary.opening ?? opening ?? 0;
    const counted = isCard ? start + transactions.reduce((s, t) => s - t.pence, 0) : (running ?? start + transactions.reduce((s, t) => s + t.pence, 0));
    checks.push({ what: "Statement summary", printed: summary.closing, counted, ok: Math.abs(counted - summary.closing) < 1, page: 1 });
  }
  if (summary.totalIn != null && !isCard) {
    const counted = transactions.filter((t) => t.pence > 0).reduce((s, t) => s + t.pence, 0);
    checks.push({ what: "Money in", printed: summary.totalIn, counted, ok: Math.abs(counted - summary.totalIn) < 1, page: 1 });
  }
  if (summary.totalOut != null && !isCard) {
    const counted = transactions.filter((t) => t.pence < 0).reduce((s, t) => s - t.pence, 0);
    checks.push({ what: "Money out", printed: summary.totalOut, counted, ok: Math.abs(counted - summary.totalOut) < 1, page: 1 });
  }
  // Newest first - as an online-banking printout lists them - each balance follows from the row
  // below it, not above: checked again oldest first, in place of the checks made on the way down.
  const newestFirst = transactions.length > 2 && transactions[0].date > transactions[transactions.length - 1].date;
  if (newestFirst) {
    for (let i = checks.length - 1; i >= 0; i--) if (checks[i].what.startsWith("Balance after")) checks.splice(i, 1);
    let run = null;
    for (const t of [...transactions].reverse()) {
      delete t.balanceOk; delete t.balanceChecked;
      if (run != null) run += t.pence;
      if (t.printed == null) continue;
      if (run == null) { run = t.printed; continue; }
      t.balanceOk = Math.abs(run - t.printed) < 1;
      if (t.balanceOk) t.balanceChecked = true;
      else { checks.push({ what: `Balance after ${t.description.slice(0, 24)}`, printed: t.printed, counted: run, ok: false, page: t.page }); run = t.printed; }
    }
    notes.push("Listed newest first - read and checked oldest first.");
  }
  const balanceRows = transactions.filter((t) => t.balanceChecked).length;
  if (balanceRows) checks.push({ what: `Running balance on ${balanceRows} rows`, ok: true });
  if (!sawHeader) notes.push("No table heading found - this layout isn't one Penny knows yet.");
  if (!period && undated) notes.push("No statement period or date found - dates without a year are taken as this year.");
  return {
    kind: isCard ? "card" : "account",
    period,
    transactions,
    checks,
    allChecksOk: checks.length > 0 && checks.every((c) => c.ok),
    notes,
    report,
  };
}

/** The layout without the content: letters to x, digits to 9, the words that give the shape kept. */
export function anonymise(report) {
  const keep = new RegExp(
    "^(" + MONTH_RE + "|date|trans|post|description|details|amount|balance|paid|in|out|money|credit|debit|cr|dr|sub-?total|total|" +
      "brought|carried|forward|previous|opening|closing|new|statement|page|of|from|to|card|ending|payment|direct|minimum|limit|£|-)$", "i",
  );
  return report.map((lines, p) => `--- page ${p + 1}\n` + lines.map((l) =>
    `[${l.kind}] ` + l.text.split(/(\s+)/).map((w) => (keep.test(w.replace(/[.,:]$/, "")) ? w : w.replace(/[A-Za-z]/g, "x").replace(/\d/g, "9"))).join(""),
  ).join("\n")).join("\n");
}
