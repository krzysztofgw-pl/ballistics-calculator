# Station Pressure (Connect IQ watch app)

A minimal Garmin watch app that sends the barometer's **station pressure** to the Ballistics phone app.
Station pressure is what the watch measures where you stand; it is not reduced to sea level, so it does
not depend on the watch's altitude calibration. Temperature is not sent: a wrist sensor reads body heat.

## How it works

1. On the Conditions screen, switch on **Pressure from a Garmin watch** in Settings → External devices, then tap **Garmin** under the station pressure.
2. The phone asks the watch to open Station Pressure; confirm on the watch.
3. The watch sends its reading, and the phone fills in the pressure and switches it to station pressure.

The watch app also shows the pressure itself, in the unit the phone app asks for (hPa, mmHg or inHg; the reading
always travels in Pa), and sends it again when you press START. It runs only
while open (no background service) and closes itself 5 s after a reading reaches the phone. Messages go
through Garmin Connect on the phone (Connect IQ Mobile SDK), so the watch must be paired there.

## Languages

The watch shows English, Polish, German, French, Spanish or Italian, following the watch's language
(`resources-<lang>/strings/strings.xml`). The app name stays Station Pressure everywhere, as the phone app refers to it.

## Install (sideload)

GitHub Actions builds one `.prg` per watch model; download the `kgw-pressure-watch-app` artifact from the
workflow run.

Pick the `kgw-pressure-<device>.prg` for your watch; the device names are the product ids in
`manifest.xml`. For example:

| File | Watches |
| --- | --- |
| `kgw-pressure-fenix7x.prg` | tactix 7 (Standard, Pro), fēnix 7X, Enduro 2 |
| `kgw-pressure-fenix7xpro.prg` | fēnix 7X Pro |
| `kgw-pressure-epix2pro51mm.prg` | tactix 7 AMOLED, epix Pro 51 mm |

1. Connect the watch to a computer with its USB cable (on a Mac use Garmin Express or OpenMTP).
2. Copy the `.prg` into `GARMIN/APPS/` on the watch.
3. Unplug; the app appears in the watch's app list as Station Pressure.

If the watch does not list the app, try the file for the other model.

## Connect IQ store

Garmin Connect can open only apps it installed itself, so with a sideloaded `.prg` the watch has to be
opened by hand. Installing from the store fixes that.

There are two listings, with two app ids. A beta can't be turned into a public app, so the public app
got a new id; the phone app (`GarminPressure.kt`) talks to both and opens the public one first.

| Listing | App id | Package from CI |
| --- | --- | --- |
| Public | `8479255366624599996860607684e2eb` (in `manifest.xml`) | `kgw-pressure.iq` |
| Beta (visible only to the uploader, not reviewed) | `d8c4bc4d63d94a6cb0071345dc138c56` | `kgw-pressure-beta.iq` |

1. Signing: the store accepts updates only with the same developer key. CI reads it from the
   `CIQ_DEVELOPER_KEY_BASE64` secret (base64 of the DER key); keep the key safe, it cannot be replaced.
2. Download the `.iq` from the `kgw-pressure-watch-app` artifact of a build that used that secret.
3. Sign in at the [Connect IQ developer dashboard](https://apps.garmin.com/developer/) with the Garmin
   account used in Garmin Connect. For the public app, upload `kgw-pressure.iq` as a new app (no
   **Beta app** tick) with the texts and images in [`store/listing.md`](store/listing.md); Garmin
   reviews it, usually within a few days. For a beta update, upload `kgw-pressure-beta.iq` as a new
   version of the beta app.
4. Delete any sideloaded `kgw-pressure-*.prg` from `GARMIN/APPS/` on the watch.
5. Install from the Connect IQ store in Garmin Connect (the app page link from the dashboard,
   with `apps-developer.garmin.com` changed to `apps.garmin.com`, opens it on the phone). Once the
   public app is on the watch, the beta can be removed.

## Build locally

With the Connect IQ SDK and the Monkey C extension for VS Code: open this folder, run
*Monkey C: Build for Device* and pick the watch. Or from a shell:

```sh
monkeyc -f monkey.jungle -d fenix7x -o bin/kgw-pressure.prg -y developer_key.der -r
```

The app id in `manifest.xml` must match `APP_ID` in
`app/src/main/kotlin/pl/ballistics/app/data/GarminPressure.kt`.
