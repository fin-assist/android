// Render Claude Design artboards (.dc.html) to PNG for the design check.
//
//   node design-check/render.js <site dir> <out dir> [Artboard ...]
//
// <site dir> holds the canvas files as published in the artifact (`project/*`) plus the runtime
// `artifact-type/dc-runtime.js` copied as `support.js`. Without artboard names, renders every name that has
// an app snapshot in build/design-check/app. Output: 390 css px wide at ×3 = 1170 px, the same as the app.
//
// Font: the canvas stack is "-apple-system, BlinkMacSystemFont, 'Segoe UI', Roboto, …"; on Linux fontconfig
// answers the Apple/Windows names with whatever sans it has (Inter here) before Roboto. The app is Roboto,
// so tokens.css is served with --font-sans pinned to Roboto (needs Roboto installed: see README).
const { chromium } = require('playwright');
const http = require('http');
const fs = require('fs');
const path = require('path');

const [siteArg, outArg, ...names] = process.argv.slice(2);
const site = path.resolve(siteArg);
const out = path.resolve(outArg);
fs.mkdirSync(out, { recursive: true });
const wanted = names.length ? names
  : fs.readdirSync(path.join(__dirname, '..', 'build/design-check/app')).filter(f => f.endsWith('.png')).map(f => f.slice(0, -4));

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  const p = path.join(site, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(site) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const canvas = JSON.parse(fs.readFileSync(path.join(site, 'canvas.json'), 'utf8'));
  const browser = await chromium.launch();
  const ctx = await browser.newContext({ viewport: { width: 390, height: 844 }, deviceScaleFactor: 3 });
  const page = await ctx.newPage();
  await page.route('**/ds/pf/tokens.css', async (route) => {
    const body = fs.readFileSync(path.join(site, 'ds/pf/tokens.css'), 'utf8') + '\n:root, [data-theme] { --font-sans: Roboto, sans-serif; }\n';
    await route.fulfill({ contentType: 'text/css', body });
  });
  const errors = [];
  page.on('pageerror', e => errors.push(String(e)));
  for (const name of wanted) {
    const file = name + '.dc.html';
    if (!fs.existsSync(path.join(site, file))) { console.log(name, 'MISSING in canvas'); continue; }
    const b = canvas.boards[file] || { w: 390, h: 844 };
    await page.setViewportSize({ width: b.w, height: b.h });
    await page.goto(`http://127.0.0.1:${port}/${file}`, { waitUntil: 'networkidle' });
    await page.evaluate(() => document.fonts.ready);
    await page.waitForTimeout(500);
    await page.screenshot({ path: path.join(out, name + '.png'), clip: { x: 0, y: 0, width: b.w, height: b.h } });
    console.log(name, `${b.w}x${b.h}`);
  }
  if (errors.length) console.log('page errors:', errors.slice(0, 3));
  await browser.close();
  server.close();
});
