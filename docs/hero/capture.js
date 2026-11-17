// Captures a Stageshow stage as evenly timed frames: the stage clock and CSS run SLOW times slower.
const { chromium } = require('playwright');
const fs = require('fs');
const [url, outDir, seconds = '14', fps = '15', slow = '6'] = process.argv.slice(2);
(async () => {
  fs.mkdirSync(outDir, { recursive: true });
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 900, height: 520 }, deviceScaleFactor: 2 });
  await p.addInitScript((s) => { window.__stageshow = { slow: s }; }, Number(slow));
  const cdp = await p.context().newCDPSession(p);
  await cdp.send('Animation.enable');
  await cdp.send('Animation.setPlaybackRate', { playbackRate: 1 / Number(slow) });
  await p.goto(url);
  const el = await p.waitForSelector('#example .ss-stage');
  const start = Date.now();
  const step = 1000 / Number(fps) * Number(slow);
  const total = Number(seconds) * Number(fps);
  const times = [];
  for (let i = 0; i < total; i++) {
    const wait = start + i * step - Date.now();
    if (wait > 0) await p.waitForTimeout(wait);
    const t = (Date.now() - start) / Number(slow) / 1000;
    await el.screenshot({ path: `${outDir}/f${String(i).padStart(4, '0')}.png` });
    times.push(t);
  }
  fs.writeFileSync(`${outDir}/times.json`, JSON.stringify(times));
  console.log('frames', total, 'last t', times.at(-1).toFixed(2));
  await b.close();
})();
