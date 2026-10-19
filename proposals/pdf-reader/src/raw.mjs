import fs from "node:fs";
import * as pdfjs from "/workspace/taleweaver/node_modules/pdfjs-dist/legacy/build/pdf.mjs";
import { extractPages } from "./extract.mjs";
import { readStatement } from "./penny-pdf.js";
const pages = await extractPages(pdfjs, new Uint8Array(fs.readFileSync(process.argv[2])));
const r = readStatement(pages);
r.report.forEach((lines, i) => { console.log("--- page", i + 1); lines.forEach((l) => console.log(`[${l.kind}] ${l.text}`)); });
