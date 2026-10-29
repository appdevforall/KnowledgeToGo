"""Content-exchange core: an in-box REST service for granular content exchange.

See controller/docs/ADR-content-exchange.md for the frozen Pillar 0 contracts and the
K2GO-507 export long-operation design.
"""

# The core build. Bumped per core change so a box can report which core it runs (GET
# /v1/version), mirroring dash-node's package.json version and /system/version. The
# conex_core Ansible role writes this into the [conex_core] section of iiab.ini.
VERSION = "0.3.0"
__version__ = VERSION
