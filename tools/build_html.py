"""Genera una versión web de un solo archivo (asientos-tren.html) a partir de la app Android.
Uso: python3 tools/build_html.py  -> crea web/asientos-tren.html"""
import pathlib, re
ROOT = pathlib.Path(__file__).resolve().parent.parent
WWW = ROOT / "app/src/main/assets/www"
html = (WWW / "index.html").read_text(encoding="utf-8")
esc = lambda s: s.replace("</script", "<\\/script")

def inline(m):
    name = m.group(1)
    if name == "tesseract.min.js":   # el lector de texto (OCR) pesa ~8 MB: en la web se descarga solo si hace falta
        return ""
    return "<script>/* " + name + " */\n" + esc((WWW / "lib" / name).read_text(encoding="utf-8")) + "\n</script>"
html = re.sub(r'<script src="lib/([^"]+)"></script>', inline, html)

worker = esc((WWW / "lib/pdf.worker.min.js").read_text(encoding="utf-8"))
# el lector de PDF trabaja en la propia página (sin "worker" aparte): así funciona aunque el archivo se abra
# desde el móvil (content://, visor de HTML…) donde el navegador no deja crear workers
html = html.replace("<script>/* pdf.min.js */", "<script>/* pdf.worker.min.js */\n" + worker + "\n</script>\n<script>/* pdf.min.js */", 1)
old = 'pdfjsLib.GlobalWorkerOptions.workerSrc = BASE + "lib/pdf.worker.min.js";'
assert old in html
html = html.replace(old, 'pdfjsLib.GlobalWorkerOptions.workerSrc = "pdf.worker.js"; /* no se usa: pdfjsWorker ya está cargado */')

old_ocr = re.search(r"function loadOcr\(\)\{.*?\n\}\n", html, re.S).group(0)
new_ocr = '''function loadOcr(){
  if(ocr) return Promise.resolve(ocr);
  if(ocrLoading) return ocrLoading;
  const getLib = () => window.Tesseract ? Promise.resolve() : new Promise((res, rej) => {
    const s = document.createElement("script"); s.src = "https://cdn.jsdelivr.net/npm/tesseract.js@5/dist/tesseract.min.js";
    s.onload = res; s.onerror = () => rej(new Error("sin lector")); document.head.append(s);
  });
  ocrLoading = getLib().then(() => Tesseract.createWorker("spa", 1))
    .then(async w => { await w.setParameters({ tessedit_pageseg_mode: "6" }); ocr = w; return w; })
    .catch(e => { ocrLoading = null; throw e; });
  return ocrLoading;
}
'''
html = html.replace(old_ocr, new_ocr)
import base64
html = re.sub(r"url\((fonts/[^)]+\.woff2)\)", lambda m: "url(data:font/woff2;base64," + base64.b64encode((WWW / m.group(1)).read_bytes()).decode() + ")", html)
out = ROOT / "web/asientos-tren.html"
out.parent.mkdir(exist_ok=True)
out.write_text(html, encoding="utf-8")
print(out, round(out.stat().st_size / 1e6, 2), "MB")
