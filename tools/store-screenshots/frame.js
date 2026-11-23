const { chromium } = require('playwright');
const path = require('path');
// Usage: node frame.js <screenshots dir> <output dir>
// Inputs are phone screenshots named 1.png, 2.png, ... with the status bar and gesture bar cropped off.
const RAW = process.argv[2] || 'raw', OUT = process.argv[3] || 'out';
const shots = [
  [1, 'Dial or hold,<br>right on your reticle', 'Lead holds for moving targets · FFP and SFP'],
  [2, 'Your DOPE card,<br>to the click', 'Drop, elevation, clicks, wind drift, velocity'],
  [3, 'Wind clock in a<br>compass ring', 'Crosswind and head or tailwind at a glance'],
  [4, 'Pick your hours<br>from the forecast', '12-hour wind chart: mean, gusts and direction'],
  [5, 'Bracket the wind<br>on your reticle', 'Lightest and strongest hold, by wind direction'],
  [6, 'See every effect,<br>read it with a finger', 'Spin drift, Coriolis and Eötvös by azimuth'],
  [7, 'Chronograph files,<br>straight in', 'Labradar, Garmin Xero, Athlon, MagnetoSpeed · SD, ES'],
  [8, 'Stealth mode:<br>go dark', 'No network, GPS only, one tap or a tile'],
  [9, 'Real atmosphere,<br>full physics', 'Garmin barometer, terrain or GPS altitude, Coriolis'],
];
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  let n = 1;
  for (const [i, title, sub] of shots) {
    if (!require('fs').existsSync(path.join(RAW, i + '.png'))) { n++; continue; }
    const img = 'data:image/png;base64,' + require('fs').readFileSync(path.join(RAW, i + '.png')).toString('base64');
    await p.setContent(`<html><body style="margin:0;width:1080px;height:1920px;overflow:hidden;
      background:radial-gradient(ellipse at 30% 10%, #3a4c41 0%, #22302a 45%, #141B17 100%);font-family:Inter;">
      <div style="position:absolute;top:96px;left:0;right:0;text-align:center;">
        <div style="color:#EDE8DA;font-weight:800;font-size:84px;line-height:1.05;letter-spacing:-1.5px">${title}</div>
        <div style="color:#FF7043;font-weight:500;font-size:38px;margin-top:26px">${sub}</div>
      </div>
      <div style="position:absolute;left:50%;top:418px;transform:translateX(-50%);width:704px;height:1466px;
        border-radius:56px;background:#0a0d0b;padding:12px;box-sizing:border-box;
        box-shadow:0 30px 80px rgba(0,0,0,.55), 0 0 0 2px #46564c;">
        <img src="${img}" style="width:100%;height:100%;border-radius:44px;display:block;object-fit:cover;object-position:top">
      </div></body></html>`);
    await p.waitForTimeout(150);
    await p.screenshot({ path: `${OUT}/${String(n).padStart(2,'0')}.png` });
    n++;
  }
  await b.close();
})();
