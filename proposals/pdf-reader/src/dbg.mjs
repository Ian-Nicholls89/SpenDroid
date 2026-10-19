import fs from "node:fs";
import * as pdfjs from "/workspace/taleweaver/node_modules/pdfjs-dist/legacy/build/pdf.mjs";
import { extractPages } from "./extract.mjs";
import { toLines } from "./penny-pdf.js";
const pages = await extractPages(pdfjs, new Uint8Array(fs.readFileSync(process.argv[2])));
for (const l of toLines(pages[0])) if (new RegExp(process.argv[3]).test(l.text)) console.log(l.y.toFixed(1), l.cells.map((c) => `[${c.x.toFixed(0)}-${c.right.toFixed(0)}] ${c.text}`).join(" | "));
