// The computer page's reader (assets/web/import.js) against the same sample statements and
// expected.json as the phone's StatementFileTest, so a file reads the same in either place.
// Run: node app/src/test/web/import-parity.mjs
import fs from "fs"; import vm from "vm"; import path from "path"; import { fileURLToPath } from "url";
const here = path.dirname(fileURLToPath(import.meta.url));
const dir = path.join(here, "../resources/statements");
const src = fs.readFileSync(path.join(here, "../../main/assets/web/import.js"), "utf8");
const expected = JSON.parse(fs.readFileSync(path.join(dir, "expected.json"), "utf8"));
let failed = 0;
for (const [name, want] of Object.entries(expected)) {
  const ctx = { account: () => ({ type: "PERSONAL" }), S: {}, render() {}, setTimeout() {}, clearTimeout() {} };
  vm.createContext(ctx);
  vm.runInContext(src + `
    I.accountId = "a"; loaded(${JSON.stringify(name)}, ${JSON.stringify(fs.readFileSync(path.join(dir, name), "utf8"))});
    globalThis.out = { known: I.known, rows: readRows() };`, ctx);
  const rows = ctx.out.rows;
  const got = rows.filter((r) => r.ok).map((r) => [r.date, r.amount, r.payee]);
  const checks = [
    [JSON.stringify(got), JSON.stringify(want.rows), "rows"],
    [ctx.out.known, want.known, "recognised"],
    [rows.filter((r) => r.left).length, want.left || 0, "left out"],
    [rows.filter((r) => !r.ok && !r.left).length, 0, "unreadable"],
  ];
  for (const [g, w, what] of checks) if (g !== w) { failed++; console.log(`${name} ${what}: got ${g}, want ${w}`); }
}
console.log(failed ? `${failed} differences` : "page reader matches every sample");
process.exit(failed ? 1 : 0);
