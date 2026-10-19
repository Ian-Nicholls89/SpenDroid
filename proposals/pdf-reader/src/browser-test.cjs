const { chromium } = require("/workspace/taleweaver/node_modules/playwright-core");
const fs = require("fs");
(async () => {
  const exe = ["/opt/pw-browsers/chromium", process.env.HOME + "/.cache/ms-playwright/chromium-1243/chrome-linux64/chrome", process.env.HOME + "/.cache/ms-playwright/chromium-1243/chrome-linux/chrome"].find((p) => { try { return fs.statSync(p).isFile(); } catch { return false; } });
  const browser = await chromium.launch({ executablePath: exe, args: ["--no-sandbox"] });
  const page = await browser.newPage({ viewport: { width: 1100, height: 1400 } });
  page.on("console", (m) => console.log("console:", m.text()));
  page.on("pageerror", (e) => console.log("pageerror:", e.message));
  await page.goto("file:///tmp/pdfimp/penny-pdf-reader.html");
  await page.setInputFiles("#pick", ["/tmp/pdfimp/samples/d-odd-layout.pdf", "/tmp/pdfimp/samples/c-card-cr.pdf", "/tmp/pdfimp/samples/b-current-balance.pdf", "/tmp/pdfimp/samples/a-card-subtotals.pdf"]);
  await page.waitForFunction(() => document.querySelectorAll(".verdict").length === 4, null, { timeout: 30000 });
  for (const s of await page.$$eval(".file", (els) => els.map((e) => e.querySelector("h2").textContent + " | " + e.querySelector(".verdict").textContent + " | " + e.querySelector(".facts").textContent.replace(/\s+/g, " ")))) console.log(s);
  await page.click(".file:last-child [data-act=seen]");
  await page.screenshot({ path: "/tmp/pdfimp/page.png", fullPage: true });
  await browser.close();
})();
