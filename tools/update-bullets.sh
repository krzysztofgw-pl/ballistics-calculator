#!/bin/sh
# Refreshes the bullet database snapshot shipped inside the app from the community
# database (https://github.com/krzysztofgw-pl/ballistics-bullets). The app downloads
# newer versions itself; this only sets what it starts with offline.
set -eu
cd "$(dirname "$0")/.."
curl -fsSL https://krzysztofgw-pl.github.io/ballistics-bullets/v1/bullets.json -o app/src/main/resources/bullets.json.tmp
mv app/src/main/resources/bullets.json.tmp app/src/main/resources/bullets.json
python3 -c "import json; b = json.load(open('app/src/main/resources/bullets.json')); print(len(b['bullets']), 'bullets, version', b['version'])"
