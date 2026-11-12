const { chromium } = require('playwright');
const path = require('path');
// Usage: node frame.js <screenshots dir> <output dir>
// Inputs are phone screenshots named 1.png, 2.png, ... with the status bar and gesture bar cropped off.
const RAW = process.argv[2] || 'raw', OUT = process.argv[3] || 'out';
const shots = [
  [6, 'Dial or hold,<br>right on your reticle', 'DLR-3, Mil-Dot, tree and more · FFP and SFP'],
  [7, 'Lead moving<br>targets', 'Set speed and course, see the lead hold in blue'],
  [5, 'Your dope card,<br>to the click', 'Drop, elevation, clicks, wind drift, velocity, energy'],
  [3, 'Wind and azimuth<br>on one dial', 'Clock or true bearing, with the phone compass'],
  [2, 'Wind from the<br>local forecast', 'Hourly mean, gusts and steadiness for your spot'],
  [4, 'Real atmosphere,<br>full physics', 'Garmin barometer, Coriolis, spin drift, aero jump'],
  [9, 'See what the wind<br>does to your shot', 'Corrections by wind speed, direction and spin drift'],
  [8, 'Field tools for<br>the first-round hit', 'Hit probability, wind bracket, range error, ranging'],
];
(async () => {
  const b = await chromium.launch();
  const p = await b.newPage({ viewport: { width: 1080, height: 1920 }, deviceScaleFactor: 1 });
  let n = 1;
  for (const [i, title, sub] of shots) {
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
