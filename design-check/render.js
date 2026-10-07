// Render Claude Design artboards (.dc.html) to PNG for the design check.
//
//   node design-check/render.js <site dir> <out dir> [Artboard ...]
//
// <site dir> holds the canvas files as published in the artifact (`project/*`) plus the runtime
// `artifact-type/dc-runtime.js` copied as `support.js`. Without artboard names, renders every name that has
// an app snapshot in build/design-check/app. Output: 390 css px wide at ×3 = 1170 px, the same as the app.
// Also writes <out dir>/coverage.json: every artboard of the canvas, whether a *DesignCheckTest captures it
// (`covered`: the name is the first argument of a `capture("…"` call in the test sources) and whether this run produced
// its snapshot (`rendered`). The artboards with covered: false are the screens still to add.
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
const appDir = path.join(__dirname, '..', 'build/design-check/app');
const snapshots = fs.existsSync(appDir)
  ? fs.readdirSync(appDir).filter(f => f.endsWith('.png')).map(f => f.slice(0, -4)) : [];
const wanted = names.length ? names : snapshots;

// Artboard names passed to capture("…") (DesignCheck.capture or a test's own capture helper) in every
// *DesignCheckTest.kt under the repo, skipping build output and dependencies.
function capturedNames(dir, acc = new Set()) {
  for (const e of fs.readdirSync(dir, { withFileTypes: true })) {
    if (e.isDirectory()) {
      if (!['build', 'node_modules', '.git', '.gradle'].includes(e.name)) capturedNames(path.join(dir, e.name), acc);
    } else if (e.name.endsWith('DesignCheckTest.kt')) {
      for (const m of fs.readFileSync(path.join(dir, e.name), 'utf8').matchAll(/\bcapture\(\s*(?:name\s*=\s*)?"([A-Za-z0-9]+)"/g)) acc.add(m[1]);
    }
  }
  return acc;
}

const types = { '.html': 'text/html', '.js': 'text/javascript', '.css': 'text/css', '.json': 'application/json' };
const server = http.createServer((req, res) => {
  const p = path.join(site, decodeURIComponent(req.url.split('?')[0]));
  if (!p.startsWith(site + path.sep) || !fs.existsSync(p) || fs.statSync(p).isDirectory()) { res.writeHead(404); return res.end(); }
  res.writeHead(200, { 'content-type': types[path.extname(p)] || 'application/octet-stream' });
  fs.createReadStream(p).pipe(res);
});

server.listen(0, '127.0.0.1', async () => {
  const port = server.address().port;
  const canvas = JSON.parse(fs.readFileSync(path.join(site, 'canvas.json'), 'utf8'));
  const captured = capturedNames(path.join(__dirname, '..'));
  const boards = Object.entries(canvas.boards).map(([file, b]) => {
    const name = file.replace(/\.dc\.html$/, '');
    return { name, title: b.title, page: b.page, covered: captured.has(name), rendered: snapshots.includes(name) };
  });
  fs.writeFileSync(path.join(out, 'coverage.json'), JSON.stringify(boards, null, 2));
  console.log(`coverage: ${boards.filter(b => b.covered).length} of ${boards.length} artboards have a design-check test`);
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
