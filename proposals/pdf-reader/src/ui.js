// ---- The page: drop a PDF, see what Penny makes of it. The file never leaves this browser.
import * as pdfjs from "https://cdn.jsdelivr.net/npm/pdfjs-dist@5.4.296/build/pdf.min.mjs";
pdfjs.GlobalWorkerOptions.workerSrc = "https://cdn.jsdelivr.net/npm/pdfjs-dist@5.4.296/build/pdf.worker.min.mjs";

const $ = (s) => document.querySelector(s);
const money = (p) => (p < 0 ? "−" : "") + "£" + (Math.abs(p) / 100).toLocaleString("en-GB", { minimumFractionDigits: 2, maximumFractionDigits: 2 });
const esc = (t) => String(t).replace(/[&<>"]/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;" }[c]));

async function open(file) {
  const data = new Uint8Array(await file.arrayBuffer());
  let password;
  for (let tries = 0; tries < 3; tries++) {
    try { return await extractPages(pdfjs, data.slice(), password); }
    catch (e) {
      if (e && e.name === "PasswordException") {
        password = prompt(`${file.name} is password-protected. Its password (it stays in this browser):`);
        if (password == null) throw new Error("No password given.");
      } else throw e;
    }
  }
  throw new Error("Wrong password.");
}

async function handle(files) {
  for (const file of files) {
    const card = document.createElement("section");
    card.className = "file";
    card.innerHTML = `<h2>${esc(file.name)}</h2><p class="muted">Reading…</p>`;
    $("#results").prepend(card);
    try {
      const pages = await open(file);
      const textless = pages.every((p) => p.items.length === 0);
      if (textless) {
        card.innerHTML = `<h2>${esc(file.name)}</h2><p class="bad">There's no text in this PDF - it's a scanned picture of the statement. Penny can't read those yet.</p>`;
        continue;
      }
      render(card, file.name, readStatement(pages));
    } catch (e) {
      card.innerHTML = `<h2>${esc(file.name)}</h2><p class="bad">Couldn't read it: ${esc(e.message || e)}</p>`;
    }
  }
}

function render(card, name, r) {
  const out = r.transactions.filter((t) => t.pence < 0).reduce((s, t) => s - t.pence, 0);
  const inn = r.transactions.filter((t) => t.pence > 0).reduce((s, t) => s + t.pence, 0);
  const verdict = !r.transactions.length ? `<span class="bad">No transactions found</span>`
    : !r.checks.length ? `<span class="warn">Nothing on it to check against</span>`
    : r.allChecksOk ? `<span class="good">Everything adds up</span>` : `<span class="bad">Something doesn't add up - see below</span>`;
  const period = r.period ? (r.period.start ? `${r.period.start} to ${r.period.end}` : `dated ${r.period.end}`) : "no period found";
  card.innerHTML = `
    <h2>${esc(name)}</h2>
    <div class="verdict">${verdict}</div>
    <div class="facts">
      <span><b>${r.transactions.length}</b> transactions</span>
      <span><b>${money(out)}</b> out</span><span><b>${money(inn)}</b> in</span>
      <span>${r.kind === "card" ? "Read as a card statement" : "Read as an account statement"}</span>
      <span>${esc(period)}</span>
    </div>
    ${r.checks.length ? `<h3>Checks</h3><ul class="checks">${r.checks.map((c) => `<li class="${c.ok ? "good" : "bad"}">${c.ok ? "✓" : "✗"} ${esc(c.what)}${c.printed != null ? `: statement says ${money(c.printed)}, Penny counted ${money(c.counted)}` : ""}${c.page ? ` <span class="muted">(page ${c.page})</span>` : ""}</li>`).join("")}</ul>` : ""}
    ${r.notes.map((n) => `<p class="warn">${esc(n)}</p>`).join("")}
    <div class="buttons">
      <button data-act="csv">Download CSV</button>
      <button data-act="layout">Copy anonymised layout</button>
      <button data-act="seen">What Penny saw</button>
    </div>
    <table><thead><tr><th>Date</th><th>Posted</th><th>Description</th><th class="num">Amount</th></tr></thead><tbody>
      ${r.transactions.map((t) => `<tr><td>${t.date}</td><td class="muted">${t.postDate ?? ""}</td><td>${esc(t.description)}${t.reference ? ` <span class="muted">${esc(t.reference)}</span>` : ""}${t.balanceOk === false ? ` <span class="bad">balance doesn't follow</span>` : ""}</td><td class="num ${t.pence > 0 ? "good" : ""}">${money(t.pence)}</td></tr>`).join("")}
    </tbody></table>
    <div class="seen" hidden>${r.report.map((lines, i) => `<h4>Page ${i + 1}</h4>${lines.map((l) => `<div class="line ${l.kind}"><span class="tag">${l.kind}</span>${esc(l.text)}</div>`).join("")}`).join("")}</div>`;
  card.querySelector('[data-act="csv"]').onclick = () => {
    const rows = [["Date", "Description", "Amount", "Posted", "Reference"]].concat(
      r.transactions.map((t) => [t.date, t.description, (t.pence / 100).toFixed(2), t.postDate ?? "", t.reference ?? ""]));
    const csv = rows.map((row) => row.map((v) => /[",\n]/.test(v) ? `"${String(v).replace(/"/g, '""')}"` : v).join(",")).join("\n");
    const a = document.createElement("a");
    a.href = URL.createObjectURL(new Blob([csv], { type: "text/csv" }));
    a.download = name.replace(/\.pdf$/i, "") + ".csv";
    a.click();
  };
  card.querySelector('[data-act="layout"]').onclick = async (e) => {
    const head = [
      `Verdict: ${!r.transactions.length ? "no transactions" : !r.checks.length ? "nothing to check" : r.allChecksOk ? "everything adds up" : "doesn't add up"}`,
      `${r.transactions.length} transactions, read as ${r.kind}, period ${r.period ? "found" : "not found"}`,
      ...r.checks.map((c) => `Check ${c.ok ? "OK" : "FAILED"}: ${c.what.replace(/Balance after .*/, "Balance after a row")}${c.page ? ` (page ${c.page})` : ""}`),
      ...r.notes,
    ].join("\n");
    await navigator.clipboard.writeText(head + "\n" + anonymise(r.report));
    e.target.textContent = "Copied - paste it to Claude";
  };
  card.querySelector('[data-act="seen"]').onclick = () => { const s = card.querySelector(".seen"); s.hidden = !s.hidden; };
}

const drop = $("#drop");
drop.ondragover = (e) => { e.preventDefault(); drop.classList.add("over"); };
drop.ondragleave = () => drop.classList.remove("over");
drop.ondrop = (e) => { e.preventDefault(); drop.classList.remove("over"); handle([...e.dataTransfer.files].filter((f) => /\.pdf$/i.test(f.name))); };
$("#pick").onchange = (e) => handle([...e.target.files]);
