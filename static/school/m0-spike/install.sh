#!/bin/sh
# K2Go School M0 spike: install or remove the class pages on a box.
# Run inside the proot, from a copy of this folder:
#   sh install.sh          install, then reload nginx
#   sh install.sh remove   remove, then reload nginx
set -eu

HERE="$(cd "$(dirname "$0")" && pwd)"
WWW="/library/www/html/school"
CONF="/etc/nginx/conf.d/school-nginx.conf"

if [ "${1:-}" = "remove" ]; then
    rm -f "$CONF"
    rm -rf "$WWW"
else
    mkdir -p "$WWW"
    cp -f "$HERE"/www/* "$WWW"/
    cp -f "$HERE/school-nginx.conf" "$CONF"
    chmod 0600 "$CONF"
fi

nginx -t
nginx -s reload
echo "school spike: ${1:-install} done"
