#!/usr/bin/python3
"""Per-box secrets and IDs of a K2Go box (K2GO-549).

The rootfs is baked once in CI and published, so every value that must be unique and secret per
box is removed from the image and made on each box. This file is the one list of those values:

  k2go-box-identity --strip   the bake removes them (tools/rootfs-builder/build-iiab-rootfs.sh)
  k2go-box-identity           each box start (pdsm 00-k2go-box-identity) makes what is missing,
                              and rotates the values of a box tree that came from another phone

Each box start, per item:
- The app binds its install ID at DEVICE_ID (controller/.../boxidentity/data/DeviceIdStore).
  Clone and Backup do not copy it. Without it (older app), the step only makes missing values.
- BINDING_DIR/<item> holds a salt and HMAC(install ID, salt + item), so it matches only on the
  phone that wrote it. A missing or foreign binding means that the tree came from another phone
  (Clone, Backup) or predates this check: the item is rotated (new values; the records of the
  other phone are removed; user data stays), then the binding is written. An item that is added
  later rotates once on every box.
Values never go to stdout, logs or the app. Each item fails alone and the box still starts.
"""
import contextlib
import hashlib
import hmac
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import threading
import uuid

DEVICE_ID = "/run/k2go/device-id"  # DeviceIdStore.GUEST_PATH
BINDING_DIR = "/etc/k2go/binding"
FORGEJO_BIN = "/library/forgejo/forgejo"
FORGEJO_INI = "/etc/forgejo/app.ini"
# The kinds `forgejo generate secret` makes. Other *_URI lines in app.ini are not secrets.
FORGEJO_KINDS = ("SECRET_KEY", "INTERNAL_TOKEN", "JWT_SECRET", "LFS_JWT_SECRET")
# Forgejo makes these JWT signing keys itself when they are missing.
FORGEJO_KEYS = ("/library/forgejo/data/jwt/private.pem",
                "/library/forgejo/data/actions_id_token/private.pem")
CALIBRE_CONFIG = "/library/calibre-web/config"
CALIBRE_DB = os.path.join(CALIBRE_CONFIG, "app.db")
CALIBRE_LIBRARY_DB = "/library/calibre-web/metadata.db"  # conex_core/config.py calibre_db
KOLIBRI_HOME = "/library/kolibri"
KOLIBRI_DB = os.path.join(KOLIBRI_HOME, "db.sqlite3")
DASH_JOBS_DB = "/library/dashboard/jobs.db"  # static/dashboard/sockets/jobs.ts DB_PATH
# IIAB variable precedence: local_vars > default_vars > role defaults. The kolibri role reads
# the same keys for its own provisiondevice call, so the box is provisioned the same way.
IIAB_VARS = (
    "/etc/iiab/local_vars.yml",
    "/opt/iiab/iiab/vars/default_vars.yml",
    "/opt/iiab/iiab/roles/kolibri/defaults/main.yml",
)
KOLIBRI_VARS = ("kolibri_facility", "kolibri_admin_user", "kolibri_admin_password",
                "kolibri_preset", "kolibri_language")


def has_value(path):
    try:
        return os.path.getsize(path) > 0
    except OSError:
        return False


def remove(*paths):
    for p in paths:
        try:
            os.remove(p)
        except FileNotFoundError:
            pass


def write_atomic(path, data, mode, group=None):
    tmp = path + ".k2go-tmp"
    remove(tmp)  # a read-only tmp left by a killed run would block O_TRUNC for a non-root uid
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, mode)
    if group:
        import grp
        try:
            os.chown(tmp, 0, grp.getgrnam(group).gr_gid)
        except (KeyError, OSError):
            pass
    os.replace(tmp, path)


# While the box boots, the app kills it after 60 s with no output line
# (controller/.../env/domain/EnvironmentEnsure.java, DEFAULT_BOOT_SILENCE_GRACE_MS). A long step
# prints a line every HEARTBEAT_S so a slow phone is not killed in the middle of it.
HEARTBEAT_S = 10


@contextlib.contextmanager
def beating(label):
    stop = threading.Event()

    def beat():
        waited = 0
        while not stop.wait(HEARTBEAT_S):
            waited += HEARTBEAT_S
            print("[k2go-box-identity] %s (%ds)" % (label, waited), flush=True)

    t = threading.Thread(target=beat, daemon=True)
    t.start()
    try:
        yield
    finally:
        stop.set()
        t.join()


def run(label, cmd, timeout, **kw):
    """subprocess.run(check=True) with a heartbeat line. Returns stdout when stdout=PIPE."""
    with beating(label):
        return subprocess.run(cmd, stdin=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
                              timeout=timeout, check=True, **kw).stdout


def scrub(db, statements):
    """Runs each (table, sql) whose table exists, then compacts the file: without VACUUM and a
    WAL checkpoint, the deleted rows stay readable in free pages."""
    if not os.path.exists(db):
        return
    with beating("cleaning " + os.path.basename(db)):
        con = sqlite3.connect(db)
        try:
            have = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
            for table, sql in statements:
                if table in have:
                    con.execute(sql)
            con.commit()
            con.execute("VACUUM")
            if con.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()[0]:
                raise RuntimeError(os.path.basename(db) + " is busy")
        finally:
            con.close()


# The app shows a "preparing system" boot screen between these two lines. Contract with the app:
# "begin" only when this start makes a value, before the first one; "end" only after "begin".
_begun = False


def making():
    global _begun
    if not _begun:
        _begun = True
        print("[k2go-box-identity] begin", flush=True)


# --- binding to this phone --------------------------------------------------------------------

def device_id():
    try:
        with open(DEVICE_ID) as f:
            value = f.read().strip()
    except OSError:
        return None
    return bytes.fromhex(value) if re.fullmatch(r"[0-9a-f]{32}", value) else None


def _mac(dev, salt, item):
    return hmac.new(dev, salt + item.encode(), hashlib.sha256).hexdigest()


def bound(item, dev):
    try:
        with open(os.path.join(BINDING_DIR, item)) as f:
            salt, mac = f.read().split()
        return hmac.compare_digest(_mac(dev, bytes.fromhex(salt), item), mac)
    except (OSError, ValueError):
        return False


def bind(item, dev):
    os.makedirs(BINDING_DIR, 0o700, exist_ok=True)
    salt = os.urandom(16)
    write_atomic(os.path.join(BINDING_DIR, item),
                 ("%s %s\n" % (salt.hex(), _mac(dev, salt, item))).encode(), 0o600)


# --- system IDs -------------------------------------------------------------------------------

def system_make(rotate=False):
    made = []
    if rotate or not has_value("/etc/machine-id"):
        making()
        write_atomic("/etc/machine-id", (uuid.uuid4().hex + "\n").encode(), 0o444)
        made.append("machine-id")
    # Same format as IIAB 1-prep/tasks/uuid.yml (uuidgen).
    if os.path.isdir("/etc/iiab") and (rotate or not has_value("/etc/iiab/uuid")):
        making()
        write_atomic("/etc/iiab/uuid", (str(uuid.uuid4()) + "\n").encode(), 0o644)
        made.append("iiab-uuid")
    return made


# Made again when missing: the aria2 DHT node ID (it also lists peer IPs) and the clock sequence
# of v1 UUIDs (two copies would make the same UUIDs).
SYSTEM_STATE = ("/root/.cache/aria2/dht.dat", "/root/.cache/aria2/dht6.dat",
                "/var/lib/libuuid/clock.txt", "/var/lib/dbus/machine-id")


def system_rotate():
    remove(*SYSTEM_STATE)
    return system_make(rotate=True)


def system_strip():
    # An empty machine-id is the Debian "not yet made" state. The dbus copy is unused in proot.
    if os.path.exists("/etc/machine-id"):
        open("/etc/machine-id", "w").close()
    remove("/etc/iiab/uuid", *SYSTEM_STATE)
    shutil.rmtree(BINDING_DIR, ignore_errors=True)


# --- Forgejo ----------------------------------------------------------------------------------

def forgejo_secrets():
    """(KIND, path) for each `<KIND>_URI = file:<path>` in app.ini, as the forgejo role wrote it."""
    found = []
    with open(FORGEJO_INI) as f:
        for line in f:
            m = re.match(r"^\s*([A-Z_]+)_URI\s*=\s*file:(\S+)", line)
            if m and m.group(1) in FORGEJO_KINDS:
                found.append((m.group(1), m.group(2)))
    return found


def forgejo_make():
    if not os.path.exists(FORGEJO_INI):
        return []
    made = []
    for kind, path in forgejo_secrets():
        if has_value(path):
            continue
        making()
        out = run("forgejo: generating " + kind, [FORGEJO_BIN, "generate", "secret", kind],
                  120, stdout=subprocess.PIPE).strip()
        if not out:
            raise RuntimeError("forgejo returned an empty secret")
        # Same owner and mode as the forgejo role (roles/forgejo/tasks/install.yml).
        write_atomic(path, out + b"\n", 0o640, group="forgejo")
        made.append(kind)
    return made


# Tables with rows that SECRET_KEY encrypts (2FA, Actions secrets, auth sources, mirror keys).
# A new key makes those rows unreadable, so the key is kept when one of them has a row.
FORGEJO_ENCRYPTED = ("two_factor", "secret", "login_source", "push_mirror")


def forgejo_db():
    section = ""
    with open(FORGEJO_INI) as f:
        for line in f:
            m = re.match(r"^\s*\[(.+)\]", line)
            if m:
                section = m.group(1)
                continue
            m = re.match(r"^\s*PATH\s*=\s*(\S+)", line)
            if m and section == "database":
                return m.group(1)
    return "/library/forgejo/data/forgejo.db"


def forgejo_key_in_use(db):
    if not os.path.exists(db):
        return False
    con = sqlite3.connect(db)
    try:
        have = {r[0] for r in con.execute("SELECT name FROM sqlite_master WHERE type = 'table'")}
        return any(con.execute('SELECT 1 FROM "%s" LIMIT 1' % t).fetchone()
                   for t in FORGEJO_ENCRYPTED if t in have)
    finally:
        con.close()


def forgejo_clear(keep_key):
    for kind, path in forgejo_secrets():
        if not (keep_key and kind == "SECRET_KEY"):
            remove(path)
    remove(*FORGEJO_KEYS)
    # Records of the other phone: logins, grants, runners, peers, job and hook history.
    # The activity feed (action) is user data and stays.
    scrub(forgejo_db(), [(t, "DELETE FROM " + t) for t in FORGEJO_RECORDS]
          # Ended migrations only (stopped, failed, finished: modules/structs/task.go). A queued or
          # running one still owns a repo that waits for it.
          + [("task", "DELETE FROM task WHERE status IN (2, 3, 4)")])


FORGEJO_RECORDS = ("forgejo_auth_token", "session", "oauth2_grant", "oauth2_authorization_code",
                   "action_runner_token", "action_runner", "notice", "hook_task",
                   "federation_host", "federated_user", "federated_user_activity", "following_repo")


def forgejo_rotate():
    if not os.path.exists(FORGEJO_INI):
        return []
    making()
    keep = forgejo_key_in_use(forgejo_db())
    forgejo_clear(keep)
    return forgejo_make() + (["SECRET_KEY kept"] if keep else [])


def forgejo_strip():
    if os.path.exists(FORGEJO_INI):
        forgejo_clear(keep_key=False)
    remove(*FORGEJO_KEYS)


# --- Calibre-Web ------------------------------------------------------------------------------
# Calibre-Web makes a new Flask session key and a new .key when they are missing, so it has no
# make step. The settings columns *_e were encrypted with the removed .key and cannot be read.

def calibre_strip():
    remove(os.path.join(CALIBRE_CONFIG, ".key"))
    if not os.path.exists(CALIBRE_DB):
        return
    con = sqlite3.connect(CALIBRE_DB)
    try:
        encrypted = [r[1] for r in con.execute("PRAGMA table_info(settings)") if r[1].endswith("_e")]
    finally:
        con.close()
    if not encrypted:
        raise RuntimeError("Calibre-Web settings schema changed")  # fail the bake, do not ship a key
    scrub(CALIBRE_DB, [("flask_settings", "DELETE FROM flask_settings"),
                       ("user_session", "DELETE FROM user_session"),
                       ("remote_auth_token", "DELETE FROM remote_auth_token"),
                       ("kobo_synced_books", "DELETE FROM kobo_synced_books"),
                       ("downloads", "DELETE FROM downloads")]
          + [("settings", "UPDATE settings SET %s = NULL" % c) for c in encrypted])


def calibre_rotate():
    making()
    calibre_strip()
    # Calibre desktop names its device here. OR REPLACE: the row is unique per device.
    scrub(CALIBRE_LIBRARY_DB, (("last_read_positions",
                                "UPDATE OR REPLACE last_read_positions SET device = '' WHERE device != ''"),))
    return ["key", "sessions"]


# --- Kolibri ----------------------------------------------------------------------------------

def kolibri_installed():
    return os.path.exists(KOLIBRI_DB) and shutil.which("kolibri")


def kolibri_provisioned():
    con = sqlite3.connect("file:%s?mode=ro" % KOLIBRI_DB, uri=True)
    try:
        row = con.execute("SELECT is_provisioned FROM device_devicesettings LIMIT 1").fetchone()
    except sqlite3.OperationalError:
        row = None
    finally:
        con.close()
    return bool(row and row[0])


PID_FILE = os.path.join(KOLIBRI_HOME, "server.pid")


def kolibri_running():
    # The pid alone is not enough: after a crash Android can give that pid to another process.
    try:
        with open(PID_FILE) as f:
            pid = int(f.readline().split()[0])
        with open("/proc/%d/cmdline" % pid, "rb") as f:
            return b"kolibri" in f.read()
    except (OSError, ValueError, IndexError):
        return False


def iiab_vars(keys):
    found = {}
    for path in IIAB_VARS:
        try:
            with open(path) as f:
                for line in f:
                    # Top-level scalars only: a quoted value, or a plain one up to " #".
                    m = re.match(r"""^([a-z_]+)\s*:\s*(?:"([^"]*)"|'([^']*)'|(.*?))(?:\s+#.*)?\s*$""", line)
                    if m and m.group(1) in keys and m.group(1) not in found:
                        found[m.group(1)] = next(g for g in m.group(2, 3, 4) if g is not None)
        except OSError:
            continue
    missing = [k for k in keys if not found.get(k) or "{{" in found[k]]
    if missing:
        raise RuntimeError("no usable value for " + ",".join(missing))
    return found


def kolibri_cmd(*args, home=KOLIBRI_HOME):
    run("kolibri: " + args[0], ["kolibri", "manage"] + list(args), 900,
        stdout=subprocess.DEVNULL, env=dict(os.environ, KOLIBRI_HOME=home))


def kolibri_make():
    if not kolibri_installed() or kolibri_provisioned():
        return []
    if kolibri_running():
        raise RuntimeError("Kolibri is running")
    v = iiab_vars(KOLIBRI_VARS)
    making()
    # One transaction: the facility, its root key, the DatabaseID and the superuser. If it fails
    # or the process dies, nothing is kept, the pdsm kolibri gate keeps the unprovisioned Kolibri
    # down (tools/upstream-patches/patches/0003-*), and the next start runs it again.
    kolibri_cmd("provisiondevice", "--facility", v["kolibri_facility"],
                "--superusername", v["kolibri_admin_user"],
                "--superuserpassword", v["kolibri_admin_password"],
                "--preset", v["kolibri_preset"], "--language_id", v["kolibri_language"])
    return ["facility"]


# The re-key runs on a copy of the db in its own Kolibri home, so the live db is never half done.
REKEY_HOME = "/library/.k2go-kolibri-rekey"
# Records of the other phone: sync state, the devices it saw, its sessions. Learner data stays.
KOLIBRI_SCRUB = (
    ("morango_buffer", "DELETE FROM morango_buffer"),
    ("morango_recordmaxcounterbuffer", "DELETE FROM morango_recordmaxcounterbuffer"),
    ("morango_transfersession", "DELETE FROM morango_transfersession"),
    ("morango_syncsession", "DELETE FROM morango_syncsession"),
    ("morango_nonce", "DELETE FROM morango_nonce"),
    # Per-box keys that Kolibri makes again on demand.
    ("morango_sharedkey", "DELETE FROM morango_sharedkey"),
    ("device_deviceappkey", "DELETE FROM device_deviceappkey"),
    ("discovery_pinneddevice", "DELETE FROM discovery_pinneddevice"),
    ("device_learnerdevicestatus", "DELETE FROM device_learnerdevicestatus"),
    ("device_usersyncstatus", "DELETE FROM device_usersyncstatus"),
    ("device_osuser", "DELETE FROM device_osuser"),
    ("logger_usersessionlog", "UPDATE logger_usersessionlog SET device_info = NULL"),
    ("logger_contentsessionlog", "UPDATE logger_contentsessionlog SET visitor_id = NULL"),
)
KOLIBRI_SIDE_DBS = (
    ("sessions.sqlite3", (("kolibriauth_session", "DELETE FROM kolibriauth_session"),)),
    ("syncqueue.sqlite3", (("device_syncqueue", "DELETE FROM device_syncqueue"),)),
    ("networklocation.sqlite3", (("discovery_networklocation",
                                  "DELETE FROM discovery_networklocation WHERE location_type IS NOT 'reserved'"),)),
    # A peer import job keeps the peer URL. Kolibri schedules its periodic jobs again at start.
    ("job_storage.sqlite3", (("jobs", "DELETE FROM jobs"),)),
)


def kolibri_facilities(db):
    con = sqlite3.connect(db)
    try:
        return {r[0] for r in con.execute("SELECT id FROM kolibriauth_collection WHERE kind = 'facility'")}
    finally:
        con.close()


def kolibri_rotate():
    """New root key, dataset and DatabaseID for each facility (recreatefacility). Users, classes
    and progress stay."""
    if not kolibri_installed():
        return []
    if not kolibri_provisioned():
        return kolibri_make()
    if kolibri_running():
        raise RuntimeError("Kolibri is running")
    making()
    shutil.rmtree(REKEY_HOME, ignore_errors=True)
    os.makedirs(REKEY_HOME, 0o700)
    copy = os.path.join(REKEY_HOME, "db.sqlite3")
    # All of them: Kolibri does not make the tables of a missing side db, and deleting a user
    # also deletes rows in notifications.sqlite3.
    with beating("kolibri: copying the databases"):
        for name in os.listdir(KOLIBRI_HOME):
            if name.endswith(".sqlite3"):
                live = sqlite3.connect(os.path.join(KOLIBRI_HOME, name))
                try:
                    # The swap removes the live WAL, so all of it must be in the main file first.
                    if live.execute("PRAGMA wal_checkpoint(TRUNCATE)").fetchone()[0]:
                        raise RuntimeError(name + " is busy")
                    dst = sqlite3.connect(os.path.join(REKEY_HOME, name))
                    live.backup(dst)
                    dst.close()
                finally:
                    live.close()
    # The same Kolibri setup, so that no upgrade step runs in REKEY_HOME.
    for name in (".data_version", "options.ini", "plugins.json"):
        if os.path.exists(os.path.join(KOLIBRI_HOME, name)):
            shutil.copy2(os.path.join(KOLIBRI_HOME, name), REKEY_HOME)

    con = sqlite3.connect(copy)
    try:
        perms = con.execute(
            "SELECT u.facility_id, u.username, p.is_superuser, p.can_manage_content"
            " FROM device_devicepermissions p JOIN kolibriauth_facilityuser u ON u.id = p.user_id").fetchall()
        # The facilities made below get a DatabaseID of this box, not the one of the other phone.
        con.execute("DELETE FROM morango_instanceidmodel")
        con.execute("DELETE FROM morango_databaseidmodel")
        con.commit()
    finally:
        con.close()
    # Before the re-key: recreatefacility copies the rows into the new facility and deletes the
    # Morango store rows of the old one, so no old value stays in the store.
    scrub(copy, KOLIBRI_SCRUB)

    for old in sorted(kolibri_facilities(copy)):
        before = kolibri_facilities(copy)
        kolibri_cmd("recreatefacility", "--facility", old, "--noninteractive", home=REKEY_HOME)
        new = kolibri_facilities(copy) - before
        if len(new) != 1:
            raise RuntimeError("recreatefacility made %d facilities" % len(new))
        new = new.pop()
        # recreatefacility deletes the device permissions with the old users.
        con = sqlite3.connect(copy)
        try:
            for facility, username, superuser, manage in perms:
                if facility == old:
                    con.execute(
                        "INSERT OR REPLACE INTO device_devicepermissions"
                        " (user_id, is_superuser, can_manage_content)"
                        " SELECT id, ?, ? FROM kolibriauth_facilityuser"
                        " WHERE facility_id = ? AND username = ?",
                        (superuser, manage, new, username))
            con.commit()
        finally:
            con.close()

    # The InstanceID that recreatefacility made includes REKEY_HOME. Kolibri makes the live one at
    # start. Then compact away the old facility, and the notifications of the old user IDs.
    scrub(copy, (("morango_instanceidmodel", "DELETE FROM morango_instanceidmodel"),))
    scrub(os.path.join(REKEY_HOME, "notifications.sqlite3"),
          (("notifications_notificationslog", "DELETE FROM notifications_notificationslog"),))
    for name, statements in KOLIBRI_SIDE_DBS:
        scrub(os.path.join(KOLIBRI_HOME, name), statements)
    # db.sqlite3 last: if the process dies between the two, the next start re-keys again.
    for name in ("notifications.sqlite3", "db.sqlite3"):
        new, live = os.path.join(REKEY_HOME, name), os.path.join(KOLIBRI_HOME, name)
        if os.path.exists(new):
            shutil.copymode(live, new)
            remove(live + "-wal", live + "-shm")
            os.replace(new, live)
    shutil.rmtree(REKEY_HOME, ignore_errors=True)
    return ["facility", "database-id"]


def kolibri_strip():
    # Removes the facility (and its root private key), the users, the DatabaseID and the device
    # settings. Content stays.
    if kolibri_installed():
        if kolibri_running():
            raise RuntimeError("Kolibri is running")
        remove(PID_FILE)  # stale: deprovision refuses to run while it names a live pid
        kolibri_cmd("deprovision", "--destroy-all-user-data", "--permanent-irrevocable-data-loss")


# --- dash-node --------------------------------------------------------------------------------
# No per-box value. Its job history (targets, errors, results) is a record of the other phone.

def dash_strip():
    scrub(DASH_JOBS_DB, (("jobs", "DELETE FROM jobs"),))


def dash_rotate():
    dash_strip()
    return ["jobs"]


# (name, make missing values, rotate, strip). system first: Kolibri derives its InstanceID from
# /etc/machine-id.
ITEMS = (
    ("system", system_make, system_rotate, system_strip),
    ("forgejo", forgejo_make, forgejo_rotate, forgejo_strip),
    ("calibre-web", None, calibre_rotate, calibre_strip),
    ("kolibri", kolibri_make, kolibri_rotate, kolibri_strip),
    ("dash-node", None, dash_rotate, dash_strip),
)


def main(argv):
    strip = argv[1:] == ["--strip"]
    dev = None if strip else device_id()
    failed = 0
    for name, make, rotate, do_strip in ITEMS:
        try:
            if strip:
                do_strip()
                result = "stripped"
            elif dev is not None and not bound(name, dev):
                done = rotate()
                bind(name, dev)  # last: a failed or killed rotation runs again at the next start
                result = "rotated " + ",".join(done) if done else "bound"
            else:
                done = make() if make else []
                result = "made " + ",".join(done) if done else "ok"
        except Exception as e:  # one item must not stop the others
            result = "FAILED (%s)" % type(e).__name__
            failed += 1
        print("[k2go-box-identity] %s: %s" % (name, result), flush=True)
    if _begun:
        print("[k2go-box-identity] end", flush=True)
    # The bake must fail on a failed strip. A box start informs and continues.
    return 1 if (strip and failed) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
