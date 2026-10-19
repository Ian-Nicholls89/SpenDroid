import fs from "node:fs";
import * as pdfjs from "/workspace/taleweaver/node_modules/pdfjs-dist/legacy/build/pdf.mjs";
import { extractPages } from "./extract.mjs";
import { readStatement, anonymise } from "./penny-pdf.js";
const files = process.argv.slice(2);
for (const f of files) {
  const pages = await extractPages(pdfjs, new Uint8Array(fs.readFileSync(f)));
  const r = readStatement(pages);
  console.log("=====", f.split("/").pop(), "kind", r.kind, "period", JSON.stringify(r.period));
  for (const t of r.transactions) console.log("  ", t.date, t.postDate ?? "          ", (t.reference ?? "").padEnd(8), (t.pence / 100).toFixed(2).padStart(9), t.balanceOk === false ? "BAL!" : "", t.description);
  for (const c of r.checks) console.log("  check", c.ok ? "OK " : "BAD", c.what, c.printed != null ? (c.printed / 100).toFixed(2) : "", c.counted != null ? (c.counted / 100).toFixed(2) : "");
  for (const n of r.notes) console.log("  note", n);
  if (process.env.REPORT) console.log(anonymise(r.report));
}
