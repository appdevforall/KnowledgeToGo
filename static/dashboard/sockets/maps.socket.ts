import { Socket } from 'socket.io';
import { spawn, ChildProcess } from 'child_process';
import fs from 'fs';
import path from 'path';

// Critical paths in the backend
const SCRIPTS_DIR = '/opt/iiab/maps/tile-extract/';
const EXTRACT_SCRIPT = path.join(SCRIPTS_DIR, 'tile-extract.py');
const CATALOG_JSON = '/library/www/maps/extracts.json';

// ---------------------------------------------------------------------------
// CONTRACT (source of truth: iiab/iiab roles/maps/templates/tile-extract.py.j2)
//   sudo tile-extract.py extract <name> <box> [noninteractive]
//   sudo tile-extract.py delete  <name>
//   sudo tile-extract.py update-json
//   name: [A-Za-z0-9_-], length 1..34 (uppercase and hyphen ARE valid)
//   box : 4 comma floats min_lon,min_lat,max_lon,max_lat; min<max both axes; span<360
// extracts.json: { regions: { <name>: { render_bounds:[..], ui_bounds:[..],
//                 dates:{terrain,vector,satellite} } } }
// Display uses ui_bounds (render_bounds can be -180..180 on antimeridian crossings).
// ---------------------------------------------------------------------------

interface CatalogRegion {
    name: string;
    bbox: string;
    coords: number[];
    render_bbox?: string;
    render_coords?: number[];
    layers: string[];
    dates?: Record<string, string>;
}

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

function getMapsCatalog(): CatalogRegion[] {
    try {
        if (!fs.existsSync(CATALOG_JSON)) return [];

        console.log('[Maps] Reading extracts.json catalog...');
        const fileContent = fs.readFileSync(CATALOG_JSON, 'utf8');
        if (!fileContent.trim()) return [];

        const data = JSON.parse(fileContent);

        const regions: CatalogRegion[] = [];
        if (data && data.regions && typeof data.regions === 'object') {
            for (const name of Object.keys(data.regions)) {
                const regionData = data.regions[name];

                const ui = displayBounds(regionData);
                if (!ui) {
                    console.warn(`[Maps] Skipping region "${name}": no readable bounds.`);
                    continue;
                }

                const render = toBounds(regionData?.render_bounds);
                const dates =
                    regionData && typeof regionData.dates === 'object' && regionData.dates
                        ? (regionData.dates as Record<string, string>)
                        : undefined;

                const layers = dates ? Object.keys(dates) : [];
                const fmt = (b: number[]) => b.map((c) => c.toFixed(4)).join(', ');
                const renderDiffers = render && render.some((v, i) => v !== ui[i]);

                regions.push({
                    name,
                    coords: ui,
                    bbox: fmt(ui),
                    render_coords: render ?? undefined,
                    render_bbox: renderDiffers ? fmt(render as number[]) : undefined,
                    layers,
                    dates,
                });
            }
        }
        return regions;
    } catch (error) {
        console.error('[Maps] Error reading maps catalog:', error);
        return [];
    }
}

const regionNameRegex = /^[A-Za-z0-9_-]{1,34}$/;

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

type CommandType = 'extract' | 'delete' | 'update-json' | '';

interface ValidatedCommand {
    type: CommandType;
    region: string;
    bbox?: string;
    noninteractive?: boolean;
    error?: string;
}

function validateSecureCommand(rawCommand: string): ValidatedCommand {
    const cleaned = (rawCommand || '').replace(/\s+/g, ' ').trim();
    const tokens = cleaned.split(' ').filter(Boolean);

    if (tokens[0] !== 'sudo' || tokens[1] !== EXTRACT_SCRIPT) {
        return { type: '', region: '', error: `The command must start with: sudo ${EXTRACT_SCRIPT}` };
    }

    const action = tokens[2];

    if (action === 'update-json' && tokens.length === 3) {
        return { type: 'update-json', region: '' };
    }

    const regionName = tokens[3];

    if (!regionName || !regionNameRegex.test(regionName)) {
        return {
            type: '',
            region: '',
            error: 'Invalid region name. Allowed: letters (A-Z, a-z), digits, hyphen (-) and underscore (_); length 1-34.',
        };
    }

    if (action === 'delete' && tokens.length === 4) {
        return { type: 'delete', region: regionName };
    }

    if (action === 'extract' && tokens.length >= 5) {
        let rest = tokens.slice(4);
        let noninteractive = false;
        if (rest[rest.length - 1] === 'noninteractive') {
            noninteractive = true;
            rest = rest.slice(0, -1);
        }
        const parsed = parseBox(rest.join(''));
        if (parsed.ok) {
            return { type: 'extract', region: regionName, bbox: parsed.box, noninteractive };
        }
        return { type: '', region: '', error: parsed.error };
    }

    return {
        type: '',
        region: '',
        error:
            'Invalid command format. Allowed:\n' +
            `  sudo ${EXTRACT_SCRIPT} extract {region} {minLon,minLat,maxLon,maxLat} [noninteractive]\n` +
            `  sudo ${EXTRACT_SCRIPT} delete {region}\n` +
            `  sudo ${EXTRACT_SCRIPT} update-json`,
    };
}

function buildArgs(v: ValidatedCommand): string[] {
    if (v.type === 'update-json') return [EXTRACT_SCRIPT, 'update-json'];
    if (v.type === 'delete') return [EXTRACT_SCRIPT, 'delete', v.region];
    const args = [EXTRACT_SCRIPT, 'extract', v.region, v.bbox as string];
    if (v.noninteractive) args.push('noninteractive');
    return args;
}

export const handleMapsEvents = (socket: Socket) => {
    let scriptProcess: ChildProcess | null = null;

    socket.on('request_maps_catalog', () => {
        setTimeout(() => {
            socket.emit('maps_catalog_ready', getMapsCatalog());
        }, 800);
    });

    socket.on('start_command', (config: { rawCommand: string }) => {
        if (scriptProcess) {
            socket.emit('terminal_output', '\n[System] A process is already running.\n');
            return;
        }

        const validation = validateSecureCommand(config.rawCommand);
        if (validation.error) {
            socket.emit('terminal_output', `\n[System] SECURITY ERROR: ${validation.error}\n`);
            return;
        }

        const args = buildArgs(validation);
        const label = validation.type === 'update-json' ? 'update-json' : `${validation.type} ${validation.region}`;
        socket.emit('terminal_output', `[System] Valid command. Starting action [${label}]...\n`);

        scriptProcess = spawn('sudo', args, { env: { ...process.env, PYTHONUNBUFFERED: '1' } });
        socket.emit('process_status', { isRunning: true });

        scriptProcess.stdout?.on('data', (data) => socket.emit('terminal_output', data.toString()));
        scriptProcess.stderr?.on('data', (data) => socket.emit('terminal_output', data.toString()));

        scriptProcess.on('exit', (code) => {
            scriptProcess = null;
            socket.emit('terminal_output', `\n[System] Process [${validation.type}] finished with code ${code}\n`);
            socket.emit('process_status', { isRunning: false });
            if (code === 0) socket.emit('maps_catalog_ready', getMapsCatalog());
        });
    });

    socket.on('delete_map_region', (regionName: string) => {
        if (scriptProcess) return;

        if (!regionNameRegex.test(regionName)) {
            socket.emit('terminal_output', `\n[System] ERROR: Invalid region name for deletion: ${regionName}\n`);
            return;
        }

        socket.emit('terminal_output', `[System] Starting direct deletion for region: ${regionName}...\n`);

        scriptProcess = spawn('sudo', [EXTRACT_SCRIPT, 'delete', regionName], {
            env: { ...process.env, PYTHONUNBUFFERED: '1' },
        });
        socket.emit('process_status', { isRunning: true });

        scriptProcess.stdout?.on('data', (data) => socket.emit('terminal_output', data.toString()));
        scriptProcess.stderr?.on('data', (data) => socket.emit('terminal_output', data.toString()));
        scriptProcess.on('exit', (code) => {
            scriptProcess = null;
            if (code === 0) {
                socket.emit('terminal_output', `\n[System] Region ${regionName} successfully deleted.\n`);
                socket.emit('maps_catalog_ready', getMapsCatalog());
            } else {
                socket.emit('terminal_output', `\n[System] Error deleting region ${regionName}. Code ${code}\n`);
            }
            socket.emit('process_status', { isRunning: false });
        });
    });

    socket.on('send_input', (input: string) => {
        if (scriptProcess && scriptProcess.stdin) {
            scriptProcess.stdin.write(`${input}\n`);
        }
    });

    socket.on('disconnect', () => {
        if (scriptProcess) scriptProcess.kill();
    });
};

export { getMapsCatalog, validateSecureCommand, displayBounds, parseBox, buildArgs };
