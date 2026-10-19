// Shared by the page and the tests: a PDF's words with positions, y measured down from the top.
export async function extractPages(pdfjs, data, password) {
  const doc = await pdfjs.getDocument({ data, password, isEvalSupported: false }).promise;
  const pages = [];
  for (let n = 1; n <= doc.numPages; n++) {
    const page = await doc.getPage(n);
    const vp = page.getViewport({ scale: 1 });
    const content = await page.getTextContent();
    pages.push({
      items: content.items.filter((it) => "str" in it).map((it) => ({
        str: it.str, x: it.transform[4], y: vp.height - it.transform[5], w: it.width, h: it.height || Math.abs(it.transform[3]),
      })),
    });
  }
  return pages;
}
