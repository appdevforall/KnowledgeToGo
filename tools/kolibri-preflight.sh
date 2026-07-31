#!/bin/sh
# kolibri-preflight.sh — diagnóstico de viabilidad para sembrar contenido de Kolibri
#                        dentro del proot de K2Go.
#
# Ejecútalo DENTRO del proot, en el dispositivo, antes de escribir o desplegar código.
# Solo lee: no importa contenido ni modifica estado, salvo que pases --seed-locations
# o --probe-import de forma explícita.
#
# Uso:
#   sh kolibri-preflight.sh                      diagnóstico completo, salida legible
#   sh kolibri-preflight.sh --json               salida JSON en stdout (para automatizar)
#   sh kolibri-preflight.sh --seed-locations     además, siembra las NetworkLocations
#                                                'reserved' si faltan (escribe)
#   sh kolibri-preflight.sh --probe-import ID    además, mide el rendimiento real
#                                                importando los metadatos de un canal
#
# Códigos de salida:
#   0  todo listo para sembrar contenido
#   1  hay bloqueadores (revisa las líneas FAIL)
#   2  error de uso o Kolibri no está instalado
#
# POSIX sh a propósito: el rootfs mínimo puede no traer bash en el PATH del servicio.

set -u

# ── Constantes del entorno IIAB/proot ────────────────────────────────────────
KOLIBRI_HOME="${KOLIBRI_HOME:-/library/kolibri}"
KOLIBRI_BIN="$(command -v kolibri 2>/dev/null || echo /usr/bin/kolibri)"
STUDIO_URL="${STUDIO_URL:-https://studio.learningequality.org}"
KOLIBRI_PORT="${KOLIBRI_PORT:-8009}"
NGINX_PORT="${NGINX_PORT:-8085}"
DASH_PORT="${DASH_PORT:-4000}"

JSON=0
SEED=0
PROBE=""

while [ $# -gt 0 ]; do
    case "$1" in
        --json)           JSON=1; shift ;;
        --seed-locations) SEED=1; shift ;;
        --probe-import)
            [ $# -ge 2 ] || { echo "--probe-import requiere un channel_id" >&2; exit 2; }
            PROBE="$2"; shift 2 ;;
        -h|--help) sed -n '2,25p' "$0" | sed 's/^# \?//'; exit 0 ;;
        *) echo "Argumento no reconocido: $1" >&2; exit 2 ;;
    esac
done

# ── Acumuladores ─────────────────────────────────────────────────────────────
FAILURES=0
WARNINGS=0
JSON_KV=""

emit() {   # emit NIVEL "mensaje"
    [ "$JSON" -eq 1 ] && return 0
    case "$1" in
        OK)   printf '  \033[32mOK\033[0m    %s\n' "$2" ;;
        WARN) printf '  \033[33mWARN\033[0m  %s\n' "$2" ;;
        FAIL) printf '  \033[31mFAIL\033[0m  %s\n' "$2" ;;
        INFO) printf '        %s\n' "$2" ;;
    esac
}
ok()   { emit OK   "$1"; }
warn() { emit WARN "$1"; WARNINGS=$((WARNINGS + 1)); }
fail() { emit FAIL "$1"; FAILURES=$((FAILURES + 1)); }
info() { emit INFO "$1"; }

section() {
    [ "$JSON" -eq 1 ] && return 0
    printf '\n\033[1m%s\033[0m\n' "$1"
}

kv() {     # kv clave valor_json  — acumula pares para la salida JSON
    if [ -z "$JSON_KV" ]; then JSON_KV="\"$1\":$2"; else JSON_KV="$JSON_KV,\"$1\":$2"; fi
}
kvs() { kv "$1" "\"$(printf '%s' "$2" | sed 's/\\/\\\\/g; s/"/\\"/g')\""; }

have() { command -v "$1" >/dev/null 2>&1; }

# Compara versiones semánticas: vge 0.19.5 0.19.4 → 0 (cierto)
vge() {
    [ "$(printf '%s\n%s\n' "$2" "$1" | sort -V | head -1)" = "$2" ]
}

[ "$JSON" -eq 0 ] && cat <<BANNER
═══════════════════════════════════════════════════════════════════
 Preflight de siembra de contenido Kolibri — K2Go / IIAB bajo proot
 $(date -u '+%Y-%m-%dT%H:%M:%SZ')
═══════════════════════════════════════════════════════════════════
BANNER

# ═════════════════════════════════════════════════════════════════════════════
section "1. Herramientas del entorno"
# ═════════════════════════════════════════════════════════════════════════════
for tool in sqlite3 find awk df; do
    if have "$tool"; then ok "$tool disponible"; else
        # sqlite3 es imprescindible: el runner y este script lo usan para totales.
        if [ "$tool" = "sqlite3" ]; then
            fail "sqlite3 NO disponible — el cálculo de progreso y el chequeo de red lo necesitan"
        else
            warn "$tool no disponible"
        fi
    fi
done
kv "has_sqlite3" "$(have sqlite3 && echo true || echo false)"

# ═════════════════════════════════════════════════════════════════════════════
section "2. Instalación de Kolibri"
# ═════════════════════════════════════════════════════════════════════════════
if [ ! -x "$KOLIBRI_BIN" ]; then
    fail "No existe el binario de Kolibri en $KOLIBRI_BIN"
    info "Kolibri es opt-in por tier: en el tier 'small' no se instala."
    info "Para instalarlo:  cd /opt/iiab/iiab && ./runrole kolibri"
    info "OJO: runrole PARA el servidor; no puede solaparse con jobs REST."
    kvs "kolibri_bin" "$KOLIBRI_BIN"
    kv  "installed" "false"
    if [ "$JSON" -eq 1 ]; then printf '{%s}\n' "$JSON_KV"; fi
    exit 2
fi
ok "Binario en $KOLIBRI_BIN"
kvs "kolibri_bin" "$KOLIBRI_BIN"
kv  "installed" "true"

# La versión importa: IIAB no la pinnea (apt sobre una suite rodante), así que
# queda congelada en el rootfs según el día del build.
KVER="$("$KOLIBRI_BIN" --version 2>/dev/null | tr -d '\r' | awk '{print $NF}')"
if [ -z "$KVER" ]; then
    warn "No se pudo leer la versión de Kolibri"
    KVER="unknown"
else
    ok "Versión $KVER"
fi
kvs "version" "$KVER"

if have dpkg; then
    DEBVER="$(dpkg-query -W -f='${Version}' kolibri 2>/dev/null || echo '')"
    [ -n "$DEBVER" ] && info "Paquete .deb: $DEBVER" && kvs "deb_version" "$DEBVER"
fi

# Gate de versión: la resolución de TOKENS en importchannel llegó en 0.19.4.
# En versiones anteriores el token se pasa tal cual como channel_id y muere en un
# 404 sin mensaje claro.
if [ "$KVER" != "unknown" ]; then
    if vge "$KVER" "0.19.4"; then
        ok "Acepta tokens en 'importchannel network' (>= 0.19.4)"
        kv "supports_tokens" "true"
    else
        warn "NO acepta tokens en 'importchannel network' (< 0.19.4): resuelve el UUID antes"
        info "Resolución manual: $STUDIO_URL/api/public/v1/channels/lookup/<token>"
        kv "supports_tokens" "false"
    fi
fi

# ═════════════════════════════════════════════════════════════════════════════
section "3. Flags de la CLI realmente disponibles"
# ═════════════════════════════════════════════════════════════════════════════
# Se comprueban contra el --help real, no contra la versión: es la única prueba
# que no se puede equivocar.
IC_HELP="$(KOLIBRI_HOME="$KOLIBRI_HOME" "$KOLIBRI_BIN" manage importcontent --help 2>&1 || true)"
FLAGS_OK=1
for flag in --fail-on-error --all-thumbnails --node_ids --exclude_node_ids --manifest; do
    if printf '%s' "$IC_HELP" | grep -q -- "$flag"; then
        ok "importcontent $flag"
    else
        fail "importcontent $flag NO existe en esta versión"
        FLAGS_OK=0
    fi
done
kv "importcontent_flags_ok" "$([ "$FLAGS_OK" -eq 1 ] && echo true || echo false)"

# --no_detect_manifest solo existe en el subparser 'disk', no en 'network'.
if printf '%s' "$IC_HELP" | grep -q -- '--no_detect_manifest'; then
    info "--no_detect_manifest presente (solo aplica al subcomando 'disk')"
fi

if KOLIBRI_HOME="$KOLIBRI_HOME" "$KOLIBRI_BIN" manage scanforcontent --help 2>&1 \
     | grep -q -- '--channel-import-mode'; then
    ok "scanforcontent --channel-import-mode"
else
    warn "scanforcontent --channel-import-mode no existe (solo afecta a la siembra desde disco)"
fi

# ═════════════════════════════════════════════════════════════════════════════
section "4. KOLIBRI_HOME y estado del dispositivo"
# ═════════════════════════════════════════════════════════════════════════════
kvs "kolibri_home" "$KOLIBRI_HOME"
if [ -d "$KOLIBRI_HOME" ]; then
    ok "$KOLIBRI_HOME existe"
else
    fail "$KOLIBRI_HOME no existe"
fi

DB="$KOLIBRI_HOME/db.sqlite3"
if [ -f "$DB" ]; then
    ok "db.sqlite3 presente"
else
    fail "No hay db.sqlite3 — el dispositivo no está provisionado"
    info "IIAB provisiona en tiempo de instalación (facility 'Kolibri-in-a-Box')."
fi

# Provisioning: IIAB ya corrió 'provisiondevice' en el build del rootfs. Si no,
# la UI de Kolibri redirige al asistente y la siembra no tiene sentido todavía.
if [ -f "$DB" ] && have sqlite3; then
    PROV="$(sqlite3 "file:$DB?mode=ro" \
        "SELECT is_provisioned FROM device_devicesettings LIMIT 1;" 2>/dev/null || echo '')"
    FACS="$(sqlite3 "file:$DB?mode=ro" \
        "SELECT COUNT(*) FROM kolibriauth_facility;" 2>/dev/null || echo '?')"
    SUPER="$(sqlite3 "file:$DB?mode=ro" \
        "SELECT COUNT(*) FROM device_devicepermissions WHERE is_superuser=1;" 2>/dev/null || echo '?')"
    case "$PROV" in
        1) ok "Dispositivo provisionado · facilities=$FACS · superusuarios=$SUPER" ;;
        0) fail "Dispositivo NO provisionado: la app mostraría el asistente inicial" ;;
        *) warn "No se pudo leer is_provisioned (¿esquema distinto?)" ;;
    esac
    [ "$SUPER" = "0" ] && warn "No hay superusuario: crea uno con 'kolibri manage createsuperuser'"
    kv "provisioned" "$([ "$PROV" = "1" ] && echo true || echo false)"
    kv "facilities" "${FACS:-0}"
    kv "superusers" "${SUPER:-0}"
fi

# ═════════════════════════════════════════════════════════════════════════════
section "5. options.ini bajo proot"
# ═════════════════════════════════════════════════════════════════════════════
OPTS="$KOLIBRI_HOME/options.ini"
if [ -f "$OPTS" ]; then
    ok "options.ini presente"
    PORT_CFG="$(awk -F= '/^[[:space:]]*HTTP_PORT/ {gsub(/ /,"",$2); print $2}' "$OPTS" | head -1)"
    ZC="$(awk -F= '/^[[:space:]]*ZEROCONF_ENABLED/ {gsub(/ /,"",$2); print $2}' "$OPTS" | head -1)"
    [ -n "$PORT_CFG" ] && info "HTTP_PORT=$PORT_CFG" && kvs "http_port" "$PORT_CFG"
    case "$ZC" in
        False|false|0)
            ok "ZEROCONF_ENABLED=False — esperado bajo proot (netlink bloqueado)"
            info "Implica: sin descubrimiento de pares en LAN. La siembra es por red."
            kv "zeroconf" "false" ;;
        "") warn "ZEROCONF_ENABLED no está fijado; bajo proot debería ser False"
            kv "zeroconf" "null" ;;
        *)  warn "ZEROCONF_ENABLED=$ZC — bajo proot puede provocar errores de ifaddr"
            kv "zeroconf" "true" ;;
    esac
else
    warn "No hay options.ini en $KOLIBRI_HOME"
fi

# ═════════════════════════════════════════════════════════════════════════════
section "6. NetworkLocations 'reserved' — el bloqueador silencioso"
# ═════════════════════════════════════════════════════════════════════════════
# 'importcontent network' llama SIEMPRE a lookup_channel_listing_status(), que pasa
# por NetworkClient.discover_from_address(). Sin las filas 'reserved' (Studio y KDP)
# eso cae al fallback de variaciones de URL, que invoca ifaddr.get_adapters() — el
# que falla en Android 15. Resultado: traceback sin envolver tras decenas de
# segundos de timeouts.
#
# Solo 'importchannel network <UUID>' es inmune (no toca NetworkClient).
NLDB="$KOLIBRI_HOME/networklocation.sqlite3"
RESERVED="?"
if [ ! -f "$NLDB" ]; then
    warn "No existe networklocation.sqlite3 (¿Kolibri nunca arrancó?)"
elif ! have sqlite3; then
    warn "Sin sqlite3 no se puede comprobar"
else
    RESERVED="$(sqlite3 "file:$NLDB?mode=ro" \
        "SELECT COUNT(*) FROM discovery_networklocation WHERE location_type='reserved';" \
        2>/dev/null || echo 0)"
    if [ "${RESERVED:-0}" -gt 0 ]; then
        ok "$RESERVED NetworkLocation(s) 'reserved' presentes"
        sqlite3 "file:$NLDB?mode=ro" \
            "SELECT '  · '||base_url FROM discovery_networklocation WHERE location_type='reserved';" \
            2>/dev/null | while IFS= read -r l; do info "$l"; done
    elif [ "$SEED" -eq 1 ]; then
        warn "Faltan; sembrando (--seed-locations)"
        if KOLIBRI_HOME="$KOLIBRI_HOME" "$KOLIBRI_BIN" manage --skip-update --no-input shell -c \
             "from kolibri.core.discovery.tasks import _refresh_reserved_locations; _refresh_reserved_locations()" \
             >/dev/null 2>&1; then
            RESERVED="$(sqlite3 "file:$NLDB?mode=ro" \
                "SELECT COUNT(*) FROM discovery_networklocation WHERE location_type='reserved';" 2>/dev/null || echo 0)"
            if [ "${RESERVED:-0}" -gt 0 ]; then ok "Sembradas: $RESERVED"; else fail "La siembra no creó filas"; fi
        else
            fail "Falló la siembra de NetworkLocations"
        fi
    else
        fail "NO hay NetworkLocations 'reserved' — 'importcontent network' fallará"
        info "Siémbralas con:  sh $0 --seed-locations"
        info "O manualmente:"
        info "  KOLIBRI_HOME=$KOLIBRI_HOME $KOLIBRI_BIN manage --no-input shell -c \\"
        info "    \"from kolibri.core.discovery.tasks import _refresh_reserved_locations; _refresh_reserved_locations()\""
    fi
fi
kv "reserved_locations" "${RESERVED:-0}"

# ═════════════════════════════════════════════════════════════════════════════
section "7. Parche de ifaddr (Android 15)"
# ═════════════════════════════════════════════════════════════════════════════
# Red de seguridad del punto 6: si el seed falla o se apunta a un --baseurl no
# sembrado, el fallback llama a ifaddr.get_adapters(), que en Android >= 13/15
# lanza PermissionError porque netlink está bloqueado.
SRV_PY="/usr/lib/python3/dist-packages/kolibri/utils/server/__init__.py"
if [ ! -f "$SRV_PY" ]; then
    warn "No se encontró $SRV_PY (¿instalación no-deb?)"
    kv "ifaddr_patch" "null"
elif grep -q "try" "$SRV_PY" && grep -B3 "ifaddr.get_adapters()" "$SRV_PY" 2>/dev/null | grep -q "try:"; then
    ok "ifaddr.get_adapters() está protegido por try/except"
    kv "ifaddr_patch" "true"
else
    warn "ifaddr.get_adapters() parece sin proteger — riesgo en Android 15"
    info "Parche: roles/proot_services/files/kolibri-00-prevent_ip_error_out_due_A15_restrictions.patch"
    kv "ifaddr_patch" "false"
fi

# ═════════════════════════════════════════════════════════════════════════════
section "8. Servicios y puertos"
# ═════════════════════════════════════════════════════════════════════════════
probe() {  # probe URL etiqueta
    if have curl; then
        code="$(curl -s -o /dev/null -w '%{http_code}' -m 8 "$1" 2>/dev/null || echo 000)"
    elif have wget; then
        code="$(wget -q -S -O /dev/null -T 8 "$1" 2>&1 | awk '/HTTP\//{c=$2} END{print c+0}')"
    else
        warn "Sin curl ni wget: no se puede probar $2"; return 1
    fi
    case "$code" in
        2*|3*) ok "$2 responde ($code)"; return 0 ;;
        000)   fail "$2 no responde"; return 1 ;;
        *)     warn "$2 responde $code"; return 0 ;;
    esac
}

if KOLIBRI_HOME="$KOLIBRI_HOME" "$KOLIBRI_BIN" status >/dev/null 2>&1; then
    ok "Kolibri está corriendo"
    kv "kolibri_running" "true"
    info "Nota: importcontent escribe en db.sqlite3 durante la fase de anotación."
    info "El lock de SQLite es un spin-loop; para canales grandes considera pdsm stop kolibri."
else
    warn "Kolibri no está corriendo (la siembra funciona igual: la CLI no lo necesita)"
    kv "kolibri_running" "false"
fi

probe "http://127.0.0.1:$KOLIBRI_PORT/api/public/info" "Kolibri :$KOLIBRI_PORT"
probe "http://127.0.0.1:$NGINX_PORT/kolibri/"          "nginx :$NGINX_PORT/kolibri/"
probe "http://127.0.0.1:$DASH_PORT/api/kiwix/jobs"     "dash-node :$DASH_PORT"

# ═════════════════════════════════════════════════════════════════════════════
section "9. Conectividad con Kolibri Studio"
# ═════════════════════════════════════════════════════════════════════════════
if probe "$STUDIO_URL/api/public/info" "Studio ($STUDIO_URL)"; then
    kv "studio_reachable" "true"
else
    kv "studio_reachable" "false"
    info "Sin Studio no hay siembra online. Alternativa: importar desde disco/USB."
fi

# ═════════════════════════════════════════════════════════════════════════════
section "10. Espacio en disco"
# ═════════════════════════════════════════════════════════════════════════════
CONTENT_DIR="$KOLIBRI_HOME/content"
MEASURE="$CONTENT_DIR"
while [ -n "$MEASURE" ] && [ ! -d "$MEASURE" ]; do MEASURE="$(dirname "$MEASURE")"; done
AVAIL_KB="$(df -Pk "$MEASURE" 2>/dev/null | awk 'NR==2{print $4}')"
if [ -n "${AVAIL_KB:-}" ]; then
    AVAIL_MB=$((AVAIL_KB / 1024))
    if   [ "$AVAIL_MB" -lt 500 ];  then fail "Solo ${AVAIL_MB} MB libres"
    elif [ "$AVAIL_MB" -lt 2048 ]; then warn "${AVAIL_MB} MB libres — suficiente solo para selecciones pequeñas"
    else                                ok   "${AVAIL_MB} MB libres"
    fi
    kv "free_mb" "$AVAIL_MB"
    info "Referencia: African Storybook Library completo son ~8.300 MB."
    info "Para un teléfono, selecciona subárboles con --node_ids."
else
    warn "No se pudo medir el espacio libre"
fi

if [ -d "$CONTENT_DIR/storage" ]; then
    USED_KB="$(du -sk "$CONTENT_DIR/storage" 2>/dev/null | awk '{print $1}')"
    info "Contenido ya presente: $((${USED_KB:-0} / 1024)) MB"
    kv "content_used_mb" "$((${USED_KB:-0} / 1024))"
fi

# ═════════════════════════════════════════════════════════════════════════════
section "11. Canales ya instalados"
# ═════════════════════════════════════════════════════════════════════════════
if [ -f "$DB" ] && have sqlite3; then
    CH_COUNT="$(sqlite3 "file:$DB?mode=ro" \
        "SELECT COUNT(*) FROM content_channelmetadata;" 2>/dev/null || echo 0)"
    if [ "${CH_COUNT:-0}" -eq 0 ]; then
        info "Ningún canal instalado — es el estado que quieres cambiar"
    else
        ok "$CH_COUNT canal(es) instalado(s)"
        # available se marca de golpe al final del import, así que aquí sí es fiable.
        sqlite3 "file:$DB?mode=ro" <<'SQL' 2>/dev/null | while IFS='|' read -r n avail tot; do
.mode list
.separator |
SELECT cm.name,
       (SELECT COUNT(*) FROM content_localfile lf WHERE lf.available=1 AND lf.id IN (
            SELECT DISTINCT f.local_file_id FROM content_file f
            JOIN content_contentnode cn ON cn.id=f.contentnode_id
            WHERE cn.channel_id=cm.id)),
       (SELECT COUNT(*) FROM content_localfile lf WHERE lf.id IN (
            SELECT DISTINCT f.local_file_id FROM content_file f
            JOIN content_contentnode cn ON cn.id=f.contentnode_id
            WHERE cn.channel_id=cm.id))
FROM content_channelmetadata cm;
SQL
            info "· $n — $avail/$tot ficheros disponibles"
        done
    fi
    kv "channels_installed" "${CH_COUNT:-0}"
fi

# ═════════════════════════════════════════════════════════════════════════════
if [ -n "$PROBE" ]; then
section "12. Prueba real: importar metadatos de $PROBE"
# ═════════════════════════════════════════════════════════════════════════════
# Solo los metadatos (importchannel), que son 1-50 MB. Mide latencia real de red
# y confirma que el camino completo funciona sin comprometer GB de descarga.
    case "$PROBE" in
        [0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f][0-9a-f]) ;;
        *) fail "'$PROBE' no es un UUID hex de 32 sin guiones"; PROBE="" ;;
    esac
    if [ -n "$PROBE" ]; then
        T0="$(date +%s)"
        if KOLIBRI_HOME="$KOLIBRI_HOME" timeout 600 "$KOLIBRI_BIN" manage \
             --skip-update --no-input importchannel network "$PROBE" \
             --baseurl "$STUDIO_URL" >/tmp/kpf_probe.log 2>&1; then
            T1="$(date +%s)"
            ok "importchannel completado en $((T1 - T0))s"
            kv "probe_seconds" "$((T1 - T0))"
            CHDB="$CONTENT_DIR/databases/$PROBE.sqlite3"
            if [ -f "$CHDB" ] && have sqlite3; then
                BYTES="$(sqlite3 "file:$CHDB?mode=ro" \
                    "SELECT COALESCE(SUM(lf.file_size),0) FROM content_localfile lf WHERE lf.id IN (
                       SELECT DISTINCT f.local_file_id FROM content_file f
                       JOIN content_contentnode cn ON cn.id=f.contentnode_id
                       WHERE cn.channel_id='$PROBE');" 2>/dev/null || echo 0)"
                info "El contenido completo de este canal pesa $((BYTES / 1048576)) MB"
                kv "probe_channel_mb" "$((BYTES / 1048576))"
            fi
        else
            fail "importchannel falló (código $?) — revisa /tmp/kpf_probe.log"
            tail -5 /tmp/kpf_probe.log 2>/dev/null | while IFS= read -r l; do info "$l"; done
        fi
    fi
fi

# ═════════════════════════════════════════════════════════════════════════════
# Veredicto
# ═════════════════════════════════════════════════════════════════════════════
kv "failures" "$FAILURES"
kv "warnings" "$WARNINGS"
kv "ready" "$([ "$FAILURES" -eq 0 ] && echo true || echo false)"

if [ "$JSON" -eq 1 ]; then
    printf '{%s}\n' "$JSON_KV"
else
    printf '\n═══════════════════════════════════════════════════════════════════\n'
    if [ "$FAILURES" -eq 0 ]; then
        printf ' \033[32mLISTO\033[0m — %s advertencia(s), ningún bloqueador.\n' "$WARNINGS"
        printf ' Comando canónico de siembra:\n\n'
        printf '   env KOLIBRI_HOME=%s \\\n' "$KOLIBRI_HOME"
        printf '     %s manage --skip-update --no-input \\\n' "$KOLIBRI_BIN"
        printf '       importchannel network <uuid> --baseurl %s\n\n' "$STUDIO_URL"
        printf '   env KOLIBRI_HOME=%s \\\n' "$KOLIBRI_HOME"
        printf '     %s manage --skip-update --no-input \\\n' "$KOLIBRI_BIN"
        printf '       importcontent --fail-on-error --all-thumbnails \\\n'
        printf '         --node_ids <ids> network <uuid> --timeout 30\n'
    else
        printf ' \033[31mBLOQUEADO\033[0m — %s fallo(s), %s advertencia(s).\n' "$FAILURES" "$WARNINGS"
        printf ' Resuelve las líneas FAIL antes de desplegar el runner.\n'
    fi
    printf '═══════════════════════════════════════════════════════════════════\n'
fi

[ "$FAILURES" -eq 0 ] || exit 1
exit 0
