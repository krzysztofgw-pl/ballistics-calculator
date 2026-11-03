# Connect IQ store listing

Texts for the public Station Pressure listing in the
[Connect IQ developer dashboard](https://apps.garmin.com/developer/). Images are in this folder.

| Field | Value |
| --- | --- |
| App type | Device App (watch app) |
| File | `kgw-pressure.iq` from the `kgw-pressure-watch-app` CI artifact (public id `84792553…`) |
| Title | Station Pressure |
| Category | Tools (or Outdoor Recreation) |
| Price | Free |
| Website | https://github.com/krzysztofgw-pl/ballistics-calculator |
| Support email | kgwlabs@interia.com |
| Privacy policy | https://krzysztofgw-pl.github.io/ballistics-calculator/privacy.html |
| Icon | `store-icon-24bit-128.png` (`store-icon-64color-128.png` where a 64-colour icon is asked for) |
| Cover image | `cover-500.png` |
| Hero image | `hero-1440x720.png` (Polish listing: `hero-pl-1440x720.png`) |
| Screenshots | `screen-en.png` (Polish listing: `screen-pl.png`) |

## Description (English)

Station Pressure sends your watch's barometer reading to the Ballistics Calculator app on your Android phone, so your ballistic solution uses the real air pressure where you stand.

It sends station pressure: what the barometer measures at your position, not reduced to sea level. It does not depend on the watch's altitude calibration, and the phone needs no altitude to use it. Temperature is not sent, because a wrist sensor reads body heat.

How to use it:
1. In Ballistics Calculator, switch on "Pressure from a Garmin watch" in Settings → External devices.
2. On the Conditions screen, tap Garmin under the station pressure.
3. Confirm on the watch. The reading is sent and the app closes itself a few seconds later.

The watch also shows the pressure in the unit the phone app uses (hPa, mmHg or inHg). Press START to read and send it again.

Requires Ballistics Calculator for Android and Garmin Connect on the same phone. The app runs only while it is open: no background service, no data field, no effect on battery life.

Permissions and privacy:
- Communication & Data Transmission: used only to send the pressure reading to Ballistics Calculator on your own phone, through Garmin Connect. The app has no internet access of its own and talks to no server.
- Fitness & Sensor Data: used only to read the barometer (current pressure and the latest pressure history sample). Heart rate, location, activities and other health data are not read.
No data is collected or shared: a single pressure value goes from your watch to your phone, only when you ask for it. The only thing the app keeps on the watch is your pressure unit. Privacy policy: https://krzysztofgw-pl.github.io/ballistics-calculator/privacy.html

Languages: English, Polish, German, French, Spanish, Italian.

## Description (Polish)

Station Pressure wysyła odczyt barometru z zegarka do aplikacji Ballistics Calculator na telefonie z Androidem, żeby rozwiązanie balistyczne korzystało z rzeczywistego ciśnienia w miejscu, w którym stoisz.

Wysyłane jest ciśnienie stacyjne: to, co barometr mierzy w Twoim położeniu, bez redukcji do poziomu morza. Nie zależy od kalibracji wysokości w zegarku, a telefon nie potrzebuje wysokości, żeby go użyć. Temperatura nie jest wysyłana, bo czujnik na nadgarstku mierzy ciepło ciała.

Jak używać:
1. W Ballistics Calculator włącz „Pressure from a Garmin watch” w Settings → External devices.
2. Na ekranie Conditions dotknij Garmin pod ciśnieniem stacyjnym (station pressure).
3. Potwierdź na zegarku. Odczyt zostanie wysłany, a aplikacja zamknie się po kilku sekundach.

Zegarek pokazuje też ciśnienie w jednostce używanej w aplikacji na telefonie (hPa, mmHg lub inHg). Naciśnij START, żeby odczytać i wysłać je ponownie.

Wymaga aplikacji Ballistics Calculator na Androida i Garmin Connect na tym samym telefonie. Aplikacja działa tylko wtedy, gdy jest otwarta: bez usługi w tle, bez pola danych, bez wpływu na baterię.

Uprawnienia i prywatność:
- Komunikacja i przesyłanie danych: tylko do wysłania odczytu ciśnienia do Ballistics Calculator na Twoim telefonie, przez Garmin Connect. Aplikacja nie ma własnego dostępu do internetu i nie łączy się z żadnym serwerem.
- Dane fitness i z czujników: tylko do odczytu barometru (bieżące ciśnienie i ostatnia próbka z historii ciśnienia). Tętno, lokalizacja, aktywności ani inne dane zdrowotne nie są odczytywane.
Żadne dane nie są zbierane ani udostępniane: jedna wartość ciśnienia trafia z zegarka na Twój telefon, tylko gdy o nią poprosisz. Jedyne, co aplikacja zapamiętuje na zegarku, to wybrana jednostka ciśnienia. Polityka prywatności: https://krzysztofgw-pl.github.io/ballistics-calculator/privacy.html

## What's new (first public version)

First public release. Supports fēnix 7 / 7 Pro / 7X, tactix 7, epix (Gen 2) and epix Pro, fēnix 8, Enduro 3, Forerunner 955 and 965.
