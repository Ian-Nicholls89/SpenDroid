import re
parser = open("/tmp/pdfimp/penny-pdf.js").read().replace("export function", "function")
extract = open("/tmp/pdfimp/extract.mjs").read().replace("export async function", "async function")
ui = open("/tmp/pdfimp/ui.js").read()
imports = "\n".join(l for l in ui.splitlines() if l.startswith("import "))
ui_body = "\n".join(l for l in ui.splitlines() if not l.startswith("import "))
html = f"""<!doctype html>
<html lang="en-GB"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Penny reads a PDF statement</title>
<style>
:root {{ --bg:#18191d; --panel:#23252b; --high:#2a2c33; --text:#fff; --muted:#9aa0aa; --good:#3ddc84; --warn:#f5a623; --bad:#e5534b; }}
* {{ box-sizing:border-box }} body {{ margin:0; background:var(--bg); color:var(--text); font:15px/1.5 system-ui, -apple-system, Segoe UI, Roboto, sans-serif }}
header {{ background:linear-gradient(180deg,#1f4d32,var(--bg)); padding:28px 24px 18px }}
header h1 {{ margin:0; font-size:26px }} header p {{ margin:6px 0 0; color:#cfd3da; max-width:760px }}
main {{ padding:0 24px 60px; max-width:1100px }}
#drop {{ margin-top:10px; border:2px dashed #3a3d45; border-radius:18px; padding:30px; text-align:center; background:var(--panel) }}
#drop.over {{ border-color:var(--good) }} #drop label {{ color:var(--good); font-weight:700; cursor:pointer; text-decoration:underline }}
.file {{ background:var(--panel); border-radius:18px; padding:18px 20px; margin-top:18px }}
.file h2 {{ margin:0 0 6px; font-size:18px }} h3 {{ margin:14px 0 6px; font-size:14px; letter-spacing:.08em; text-transform:uppercase; color:var(--muted) }}
.verdict {{ font-size:20px; font-weight:800; margin:4px 0 8px }}
.facts {{ display:flex; flex-wrap:wrap; gap:8px 18px; color:#cfd3da }} .facts b {{ color:#fff }}
.checks {{ list-style:none; padding:0; margin:0 }} .checks li {{ padding:2px 0 }}
.good {{ color:var(--good) }} .warn {{ color:var(--warn) }} .bad {{ color:var(--bad) }} .muted {{ color:var(--muted) }}
.buttons {{ display:flex; gap:10px; flex-wrap:wrap; margin:14px 0 }}
button {{ background:var(--high); color:#fff; border:0; border-radius:12px; padding:10px 16px; font-weight:700; cursor:pointer }} button:hover {{ background:#33363e }}
table {{ width:100%; border-collapse:collapse; font-size:14px }} th {{ text-align:left; color:var(--muted); font-weight:600; border-bottom:1px solid #2e3037; padding:6px }}
td {{ padding:6px; border-bottom:1px solid #2a2c31; vertical-align:top }} .num {{ text-align:right; white-space:nowrap }}
.seen {{ margin-top:16px; font:12px/1.45 ui-monospace, Menlo, monospace }} .seen h4 {{ margin:12px 0 4px; font-family:system-ui }}
.line {{ padding:1px 6px; border-left:3px solid transparent; white-space:pre-wrap }} .line .tag {{ display:inline-block; width:92px; color:var(--muted) }}
.line.transaction {{ border-color:var(--good) }} .line.check, .line.summary {{ border-color:var(--warn) }} .line.header {{ border-color:#60a5fa }} .line.continued {{ border-color:#2bb5a0 }}
.line.ignored, .line.footer {{ color:#6b707a }}
.privacy {{ color:var(--muted); font-size:13px; margin-top:10px }}
</style></head><body>
<header><h1>Penny reads a PDF statement</h1>
<p>A test of SpenDroid's PDF importer. Drop a bank or card statement here and see what Penny makes of it: the transactions, and whether the statement's own totals agree.</p></header>
<main>
<div id="drop">Drop PDF statements here, or <label>choose files<input id="pick" type="file" accept="application/pdf,.pdf" multiple hidden></label></div>
<p class="privacy">Your statement is read inside this browser and never uploaded anywhere. The page only downloads pdf.js, Mozilla's PDF reader, to do the reading. "Copy anonymised layout" swaps every letter for x and every digit for 9, keeping only words like Date, Amount and CR, so you can share the shape of a statement Penny gets wrong without sharing what's on it.</p>
<div id="results"></div>
</main>
<script type="module">
{imports}
{parser}
{extract}
{ui_body}
</script>
</body></html>
"""
open("/tmp/pdfimp/penny-pdf-reader.html", "w").write(html)
print(len(html))
