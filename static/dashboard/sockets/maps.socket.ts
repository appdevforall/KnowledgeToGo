import fs from 'fs';
import path from 'path';

// Critical paths in the backend. TILE_EXTRACT is the one path every maps caller uses.
export const TILE_EXTRACT = '/opt/iiab/maps/tile-extract/tile-extract.py';
const HOSTING_DIR = '/library/www/maps';   // where tile-extract.py keeps the <name>.fqr/ region dirs

// ---------------------------------------------------------------------------
// CONTRACT (source of truth: iiab/iiab roles/maps/templates/tile-extract.py.j2; the
// tools/rootfs-builder/tests/maps/fqr contract test pins the markers used here)
//   Current CLI (iiab#4574, argparse):
//     extract <name> --bbox=<box> [--yes] [--progress json] [--threads N]
//     delete  <name> [--yes]
//   --yes asks no questions. Without it, `delete` can ask twice (unfinished files, then the
//   region). One Full Quality Region download or delete runs at a time (a lock file); a second
//   one exits 1 with LOCK_BUSY_RE. Exit 3 = paused (the .resume journal is saved).
//   Older CLI (a rootfs baked before iiab#4574): extract <name> <box> [noninteractive] with the
//   IIAB_PMTILES_* env vars, and an interactive `delete <name>` that asks once and does not
//   remove an unfinished download. hasModernCli() tells them apart.
//   name: [A-Za-z0-9_-], length 1..34 (uppercase and hyphen ARE valid)
//   box : 4 comma floats min_lon,min_lat,max_lon,max_lat; min<max both axes; span<360
// Regions are <name>.fqr/ directories under HOSTING_DIR, each with meta.json {"bbox":[minLon,
// minLat,maxLon,maxLat]} and the layer pmtiles (K2GO-566). See listFqrRegions().
// ---------------------------------------------------------------------------

function toBounds(candidate: unknown): number[] | null {
    if (!Array.isArray(candidate) || candidate.length < 4) return null;
    const nums = candidate.slice(0, 4).map((c: unknown) => Number(c));
    if (nums.some((n: number) => !Number.isFinite(n))) return null;
    return nums;
}

function displayBounds(regionData: any): number[] | null {
    if (!regionData) return null;
    return (
        toBounds(regionData.ui_bounds) ??
        toBounds(regionData.render_bounds) ??
        toBounds(regionData.bbox) ??
        toBounds(regionData)
    );
}

// K2GO-566: list downloaded Full Quality Regions by scanning HOSTING_DIR for <name>.fqr/
// directories (upstream replaced the central extracts.json catalog). Every .fqr dir is returned
// by name so an incomplete one can still be deleted; ui_bounds/bbox are filled only when the
// region's meta.json is readable. Pure over `hostingDir` so it is unit-testable off the box.
export function listFqrRegions(
    hostingDir: string = HOSTING_DIR,
): Record<string, { bbox?: number[]; ui_bounds?: number[] }> {
    const out: Record<string, { bbox?: number[]; ui_bounds?: number[] }> = {};
    let entries: string[];
    try {
        entries = fs.readdirSync(hostingDir);
    } catch {
        return out;   // no maps dir yet -> no regions, not an error
    }
    for (const entry of entries) {
        if (!entry.endsWith('.fqr')) continue;
        const name = entry.slice(0, -4);   // strip ".fqr"
        if (!name) continue;
        const dir = path.join(hostingDir, entry);
        try {
            if (!fs.statSync(dir).isDirectory()) continue;
        } catch {
            continue;
        }
        let bbox: number[] | null = null;
        try {
            const meta = JSON.parse(fs.readFileSync(path.join(dir, 'meta.json'), 'utf8'));
            bbox = toBounds((meta as { bbox?: unknown })?.bbox);
        } catch {
            bbox = null;   // missing/malformed meta.json: still list the region (deletable), no bounds
        }
        out[name] = bbox ? { bbox, ui_bounds: bbox } : {};
    }
    return out;
}

// A region name: letters, digits, hyphen, underscore; 1..34 (tile-extract.py validate_extract_name).
export const REGION_NAME_RE = /^[A-Za-z0-9_-]{1,34}$/;

type BoxResult = { ok: true; box: string } | { ok: false; error: string };

function parseBox(raw: string): BoxResult {
    const cleaned = raw.replace(/\s+/g, '');
    const parts = cleaned.split(',');
    if (parts.length !== 4) {
        return { ok: false, error: 'The Bounding Box must be exactly 4 comma-separated numbers: minLon,minLat,maxLon,maxLat' };
    }
    if (parts.some((p) => p === '')) {
        return { ok: false, error: 'The Bounding Box has an empty coordinate (check for stray/duplicate commas).' };
    }
    const nums = parts.map(Number);
    if (nums.some((n) => !Number.isFinite(n))) {
        return { ok: false, error: 'The Bounding Box contains a non-numeric value.' };
    }
    const [minLon, minLat, maxLon, maxLat] = nums;
    if (!(minLon < maxLon)) return { ok: false, error: 'Longitudes out of order: minLon must be < maxLon.' };
    if (!(minLat < maxLat)) return { ok: false, error: 'Latitudes out of order: minLat must be < maxLat.' };
    if (!(maxLon - minLon < 360)) return { ok: false, error: 'A region cannot wrap around the world (span must be < 360 degrees).' };
    return { ok: true, box: cleaned };
}

// ADFA-4879: parse tile-extract.py's interactive size prompt into bytes, for the in-app consent
// step. The script prints (see approve_download_size in tile-extract.py):
//   "New region will be 8.54 MB downloaded, 8.34 MB on disk. This will
//    leave about 73.58 GB free space on this partition. Continue?"
// Pure + unit-tested so the /api/maps/estimate route stays thin. Returns null until it appears.
const EST_UNITS: Record<string, number> = { kB: 1e3, MB: 1e6, GB: 1e9, TB: 1e12 };

export interface MapsEstimate { transfer: number; archive: number; free: number; free_after: number; }

function parseEstimate(text: string): MapsEstimate | null {
    const m = text.match(
        /New region will be ([\d.]+)\s*(kB|MB|GB|TB) downloaded, ([\d.]+)\s*(kB|MB|GB|TB) on disk\.\s*This will\s+leave about ([\d.]+)\s*(kB|MB|GB|TB) free/,
    );
    if (!m) return null;
    const b = (n: string, u: string) => Math.round(parseFloat(n) * (EST_UNITS[u] ?? 1));
    const transfer = b(m[1], m[2]);
    const archive = b(m[3], m[4]);
    const freeAfter = b(m[5], m[6]);
    return { transfer, archive, free: freeAfter + archive, free_after: freeAfter };
}

// Older tile-extract.py prints the size prompt on stdout, newer on stderr. Read both streams so
// any dash-node works with any rootfs; each stream is parsed alone so an interleave never splits it.
function parseEstimateFrom(stdout: string, stderr: string): MapsEstimate | null {
    return parseEstimate(stdout) ?? parseEstimate(stderr);
}

// ---- tile-extract.py CLI (iiab#4574) -------------------------------------------------------
// A box can run a newer dash-node than its rootfs (dash-node updates itself), so the arguments
// follow what the INSTALLED script supports. Both markers come from its argparse setup.
export function hasModernCli(scriptSource: string): boolean {
    return scriptSource.includes('"--yes"') && scriptSource.includes('"--bbox"');
}

/** Reads the installed script on each call: a re-run of the maps role can replace it. */
export function installedHasModernCli(script: string = TILE_EXTRACT): boolean {
    try {
        return hasModernCli(fs.readFileSync(script, 'utf8'));
    } catch {
        return true;   // unreadable: assume the current CLI (every rootfs from now on has it)
    }
}

// sudo drops the environment, so PYTHONUNBUFFERED (live stdout for the progress lines) and the
// older CLI's IIAB_PMTILES_* settings go through `env` after sudo.
// 8 threads: the measured throughput knee (iiab#4565 benchmarks; more was not faster).
const DOWNLOAD_THREADS = '8';

export function extractArgs(name: string, box: string, modern: boolean): string[] {
    if (modern) {
        return ['env', 'PYTHONUNBUFFERED=1', TILE_EXTRACT, 'extract', name, `--bbox=${box}`,
            '--yes', '--progress', 'json', '--threads', DOWNLOAD_THREADS];
    }
    return ['env', 'IIAB_PMTILES_PROGRESS=json', `IIAB_PMTILES_DOWNLOAD_THREADS=${DOWNLOAD_THREADS}`,
        'PYTHONUNBUFFERED=1', TILE_EXTRACT, 'extract', name, box, 'noninteractive'];
}

/** The size estimate: an interactive extract. With stdin at EOF its confirm aborts by itself after
 *  it prints the size, so nothing downloads. --bbox= keeps a negative longitude off argparse. */
export function estimateArgs(probeName: string, box: string, modern: boolean): string[] {
    return [TILE_EXTRACT, 'extract', probeName, modern ? `--bbox=${box}` : box];
}

/** The older delete has no --yes: the caller answers its one question on stdin. */
export function deleteArgs(name: string, modern: boolean): string[] {
    return modern ? [TILE_EXTRACT, 'delete', name, '--yes'] : [TILE_EXTRACT, 'delete', name];
}

// ---- How a stopped extract ends ---------------------------------------------------------------
// One Full Quality Region download or delete at a time on the box (iiab#4574).
export const LOCK_BUSY_RE = /another Full Quality Region download or delete is running/;

// tile-extract.py's OWN network requests (catalog, mirror list, HEAD) failed: pmtiles did not run,
// so there is no exit_code 2, but the cause is the link (K2GO-317). The last three lines are the
// messages of the current script, which catches the requests errors itself.
export const NET_ERR_RE = new RegExp([
    'ConnectionError', 'Max retries exceeded', 'HTTPSConnectionPool',
    'Failed to establish a new connection', 'Connection (refused|reset)',
    'Temporary failure in name resolution', 'Name or service not known',
    'Cannot download the map data catalog',
    'from any of the available mirrors',
].join('|'), 'i');

// The script refused because a region of this name (or an overlapping one) appeared under its lock.
export const REGION_CONFLICT_RE = /a region with this name, or an overlapping region, now exists/;

/** Whether a job made the region dir that a purge would delete: the dir was not there when the job
 *  took its turn, and the script did not stop because another run made it in the meantime. */
export function jobOwnsRegion(existedAtStart: boolean, stderrTail: string): boolean {
    return !existedAtStart && !REGION_CONFLICT_RE.test(stderrTail);
}

export interface ExtractEnd {
    stop: 'paused' | 'canceled' | null;   // the engine's own flags (classifyStop)
    stalled: boolean;                      // our watchdog SIGTERMed a stalled download
    lastJsonExit: number | null;           // exit_code on go-pmtiles' last JSON line
    exitCode: number | null;               // tile-extract.py's exit code (null if killed)
    stderrTail: string;
}

/**
 * What the runner does after a non-zero end of tile-extract.py:
 *  canceled : the user canceled: discard the job's own work.
 *  paused   : keep the partial and its .resume journal; Resume continues from it.
 *  busy     : another Full Quality Region operation held the box lock; nothing ran, retry later.
 *  error    : a real failure.
 * Exit code 2 is read from the JSON line only: argparse also exits 2 (usage error).
 */
export function classifyExtractEnd(e: ExtractEnd): 'canceled' | 'paused' | 'busy' | 'error' {
    if (e.stop === 'canceled') return 'canceled';
    if (e.stop === 'paused' || e.stalled) return 'paused';
    if (e.lastJsonExit === 2) return 'paused';      // go-pmtiles gave up on the network
    if (e.exitCode === 3) return 'paused';          // a SIGTERM or SIGINT from outside: journal saved
    // go-pmtiles itself failed for another cause (1 error, 4 file on the server changed): a network
    // line earlier in the log (a retried reset) must not turn that into a pause that cannot resume.
    if (e.lastJsonExit !== null && e.lastJsonExit !== 0) return 'error';
    if (LOCK_BUSY_RE.test(e.stderrTail)) return 'busy';
    if (NET_ERR_RE.test(e.stderrTail)) return 'paused';
    return 'error';
}

export { displayBounds, parseBox, parseEstimate, parseEstimateFrom };
