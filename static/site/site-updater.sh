#!/bin/bash
# static/site/site-updater.sh — deploy the box landing page to the nginx home.
#
# Mirrors static/site/ -> /library/www/html/home. ADFA-5059: uses tar (proot-safe; rsync's
# fchmodat2 chmod isn't translated inside proot, so a plain rsync -a can fail there — same reason
# tools/dev-push-dashboard.sh switched to tar), and stamps a per-deploy cache-bust token into the
# served index.html + app.js so browsers across the fleet fetch fresh CSS/JS/lang after an update
# instead of serving a stale cached copy. The repo files keep the literal __CACHEBUST__ placeholder.
set -eu

RED='\033[0;31m'; GREEN='\033[0;32m'; CYAN='\033[0;36m'; NC='\033[0m'

SITE_SRC="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
DEST_DIR="/library/www/html/home"

printf "\n${CYAN}Deploying the landing site...${NC}\n"

# ADFA-5339: validate the source is the REAL site BEFORE touching the destination. The mirror below
# is destructive (it wipes DEST first), so a mis-resolved SITE_SRC would delete the served site and
# copy the wrong tree with no rollback. `[ -d ]` is not enough — a wrong-but-existing dir (e.g. /root
# when SITE_SRC resolved empty) passes it; require the landing page's own index.html so only the
# actual site can proceed. Caught on device when this script was invoked with sh instead of bash.
[ -f "$SITE_SRC/index.html" ] || {
    printf "${RED}Refusing to deploy: %s is not the site (no index.html). Nothing was changed.${NC}\n" "$SITE_SRC"
    exit 1
}
mkdir -p "$DEST_DIR"

# Mirror: clear the destination, then copy via tar (proot-safe). Excludes this script and any *.sh.
printf "Mirroring %s -> %s ...\n" "$SITE_SRC" "$DEST_DIR"
find "$DEST_DIR" -mindepth 1 -delete 2>/dev/null || true
( cd "$SITE_SRC" && tar --exclude='*.sh' -cf - . ) | ( cd "$DEST_DIR" && tar -xf - )

# Cache-bust: stamp ONLY the served index.html (it carries the asset ?v= refs and the runtime token
# window.__CACHEBUST__). app.js is intentionally NOT stamped — it reads the token at runtime and
# compares against the "__CB_TOKEN__" placeholder to know it's unstamped, so stamping it would break
# that guard. Prefer the git short SHA (stable per commit, keeps caches warm on a same-commit
# redeploy); fall back to a timestamp if git isn't available.
TOKEN="$(git -C "$SITE_SRC" rev-parse --short HEAD 2>/dev/null || date +%s)"
sed -i "s/__CB_TOKEN__/${TOKEN}/g" "$DEST_DIR/index.html"
printf "Cache-bust token: %s\n" "$TOKEN"

printf "${GREEN}Done. No nginx restart needed (static files served directly).${NC}\n"
