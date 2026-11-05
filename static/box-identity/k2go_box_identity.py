#!/usr/bin/python3
"""Per-box secrets and IDs of a K2Go box (K2GO-549).

The rootfs is baked once in CI and published, so every value that must be unique and secret per
box is removed from the image and made on each box. This file is the one list of those values:

  k2go-box-identity --strip   the bake removes them (tools/rootfs-builder/build-iiab-rootfs.sh)
  k2go-box-identity           each box start makes what is missing (pdsm 00-k2go-box-identity)

Rule at box start: a missing value is made; a present value is never changed. No marker file.
Values never go to stdout, logs or the app. Each item fails alone and the box still starts.
"""
import grp
import os
import re
import shutil
import sqlite3
import subprocess
import sys
import uuid

FORGEJO_BIN = "/library/forgejo/forgejo"
FORGEJO_INI = "/etc/forgejo/app.ini"
# The kinds `forgejo generate secret` makes. Other *_URI lines in app.ini are not secrets.
FORGEJO_KINDS = ("SECRET_KEY", "INTERNAL_TOKEN", "JWT_SECRET", "LFS_JWT_SECRET")
# Forgejo makes these JWT signing keys itself when they are missing.
FORGEJO_KEYS = ("/library/forgejo/data/jwt/private.pem",
                "/library/forgejo/data/actions_id_token/private.pem")
CALIBRE_CONFIG = "/library/calibre-web/config"
KOLIBRI_HOME = "/library/kolibri"
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
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, mode)
    try:
        os.write(fd, data)
        os.fsync(fd)
    finally:
        os.close(fd)
    os.chmod(tmp, mode)
    if group:
        try:
            os.chown(tmp, 0, grp.getgrnam(group).gr_gid)
        except (KeyError, OSError):
            pass
    os.replace(tmp, path)


# --- system IDs -------------------------------------------------------------------------------

def system_make():
    made = []
    if not has_value("/etc/machine-id"):
        write_atomic("/etc/machine-id", (uuid.uuid4().hex + "\n").encode(), 0o444)
        made.append("machine-id")
    # Same format as IIAB 1-prep/tasks/uuid.yml (uuidgen).
    if os.path.isdir("/etc/iiab") and not has_value("/etc/iiab/uuid"):
        write_atomic("/etc/iiab/uuid", (str(uuid.uuid4()) + "\n").encode(), 0o644)
        made.append("iiab-uuid")
    return made


def system_strip():
    # An empty machine-id is the Debian "not yet made" state. The dbus copy is unused in proot.
    if os.path.exists("/etc/machine-id"):
        open("/etc/machine-id", "w").close()
    remove("/var/lib/dbus/machine-id", "/etc/iiab/uuid")


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
        out = subprocess.run([FORGEJO_BIN, "generate", "secret", kind], check=True,
                             stdout=subprocess.PIPE, stderr=subprocess.DEVNULL,
                             timeout=120).stdout.strip()
        if not out:
            raise RuntimeError("forgejo returned an empty secret")
        # Same owner and mode as the forgejo role (roles/forgejo/tasks/install.yml).
        write_atomic(path, out + b"\n", 0o640, group="forgejo")
        made.append(kind)
    return made


def forgejo_strip():
    if os.path.exists(FORGEJO_INI):
        remove(*[path for _, path in forgejo_secrets()])
    remove(*FORGEJO_KEYS)


# --- Calibre-Web ------------------------------------------------------------------------------
# Calibre-Web makes a new Flask session key and a new .key when they are missing, so it has no
# make step. mail_password_e was encrypted with the removed .key and cannot be read any more.

def calibre_strip():
    remove(os.path.join(CALIBRE_CONFIG, ".key"))
    db = os.path.join(CALIBRE_CONFIG, "app.db")
    if os.path.exists(db):
        con = sqlite3.connect(db)
        con.execute("DELETE FROM flask_settings")
        con.execute("UPDATE settings SET mail_password_e = NULL")
        con.commit()
        con.close()


# --- Kolibri ----------------------------------------------------------------------------------

def kolibri_installed():
    return os.path.exists(os.path.join(KOLIBRI_HOME, "db.sqlite3")) and shutil.which("kolibri")


def kolibri_provisioned():
    con = sqlite3.connect("file:%s?mode=ro" % os.path.join(KOLIBRI_HOME, "db.sqlite3"), uri=True)
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


def kolibri_cmd(*args):
    subprocess.run(["kolibri", "manage"] + list(args), check=True, stdin=subprocess.DEVNULL,
                   stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, timeout=900,
                   env=dict(os.environ, KOLIBRI_HOME=KOLIBRI_HOME))


def kolibri_make():
    if not kolibri_installed() or kolibri_provisioned():
        return []
    if kolibri_running():
        raise RuntimeError("Kolibri is running")
    v = iiab_vars(KOLIBRI_VARS)
    # One transaction: the facility, its root key, the DatabaseID and the superuser. If the
    # process dies, nothing is kept and the next start runs it again.
    kolibri_cmd("provisiondevice", "--facility", v["kolibri_facility"],
                "--superusername", v["kolibri_admin_user"],
                "--superuserpassword", v["kolibri_admin_password"],
                "--preset", v["kolibri_preset"], "--language_id", v["kolibri_language"])
    return ["facility"]


def kolibri_strip():
    # Removes the facility (and its root private key), the users, the DatabaseID and the device
    # settings. Content stays.
    if kolibri_installed():
        if kolibri_running():
            raise RuntimeError("Kolibri is running")
        remove(PID_FILE)  # stale: deprovision refuses to run while it names a live pid
        kolibri_cmd("deprovision", "--destroy-all-user-data", "--permanent-irrevocable-data-loss")


# system first: Kolibri derives its InstanceID from /etc/machine-id.
ITEMS = (
    ("system", system_make, system_strip),
    ("forgejo", forgejo_make, forgejo_strip),
    ("calibre-web", None, calibre_strip),
    ("kolibri", kolibri_make, kolibri_strip),
)


def main(argv):
    strip = argv[1:] == ["--strip"]
    failed = 0
    for name, make, do_strip in ITEMS:
        step = do_strip if strip else make
        if step is None:
            continue
        try:
            done = step()
            result = "stripped" if strip else ("made " + ",".join(done) if done else "ok")
        except Exception as e:  # one item must not stop the others
            result = "FAILED (%s)" % type(e).__name__
            failed += 1
        print("[k2go-box-identity] %s: %s" % (name, result), flush=True)
    # The bake must fail on a failed strip. A box start informs and continues.
    return 1 if (strip and failed) else 0


if __name__ == "__main__":
    sys.exit(main(sys.argv))
