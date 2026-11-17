#!/usr/bin/env bash
# Builds the LG webOS package: the web player (webOS flavour) plus its JS service, as one .ipk.
#   webos/build.sh [version]      → webos/out/com.bitchord.tv_<version>_all.ipk
set -euo pipefail
here="$(cd "$(dirname "$0")" && pwd)"
root="$here/.."
version="${1:-1.0.0}"
out="$here/out"
rm -rf "$out" && mkdir -p "$out/app" "$out/service"

( cd "$root/web" && corepack pnpm install --frozen-lockfile >/dev/null && cd app && corepack pnpm exec vite build --mode webos --outDir "$out/dist" )
cp -r "$out/dist/." "$out/app/"
cp "$here/app-shell/"*.png "$out/app/"
node -e "const f=process.argv[1],v=process.argv[2],j=require(f);j.version=v;require('fs').writeFileSync(process.argv[3],JSON.stringify(j,null,2))" \
  "$here/app-shell/appinfo.json" "$version" "$out/app/appinfo.json"
cp "$here/service/"{service.js,package.json,services.json} "$out/service/"
node -e "const f=process.argv[1],j=require(f);j.version=process.argv[2];require('fs').writeFileSync(f,JSON.stringify(j,null,2))" "$out/service/package.json" "$version"

ares="${ARES_PACKAGE:-ares-package}"
command -v "$ares" >/dev/null || ares="npx -y -p @webos-tools/cli ares-package"
$ares --no-minify "$out/app" "$out/service" -o "$out"
ls -la "$out"/*.ipk
