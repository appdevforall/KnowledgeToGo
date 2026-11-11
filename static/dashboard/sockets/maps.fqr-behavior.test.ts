/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';

// K2GO-566 BEHAVIOR test, owned by the K2GO-567 pre-build belt.
//
// It checks exactly what K2GO-566 fixes: an existing FQR (a "<name>.fqr/" directory with a
// meta.json {"bbox":[...]}) MUST be listed by dash-node, so the in-app "Downloaded regions" list
// shows it and the user can delete it. Before K2GO-566 dash-node read the retired extracts.json and
// listed nothing, so this test FAILS without the fix (listFqrRegions is absent / the region does
// not appear), and PASSES with it.
//
// It is deliberately INDEPENDENT of the upstream contract test (tests/maps/fqr/test_fqr_contract.py):
// that one checks that UPSTREAM still ships the ".fqr"/meta.json/bbox format; this one checks that
// OUR code consumes it. Both must stay aligned; if either side drifts, the matching test breaks.
//
// A dynamic require (not a static import) is used on purpose: when listFqrRegions does not exist yet
// the test reports a clear assertion failure instead of a module-load/compile crash.

test('K2GO-566: an existing .fqr region is listed so it can be managed and deleted', () => {
    const maps = require('./maps.socket') as {
        listFqrRegions?: (dir: string) => Record<string, { bbox?: number[]; ui_bounds?: number[] }>;
    };
    assert.equal(
        typeof maps.listFqrRegions, 'function',
        'dash-node must expose listFqrRegions() to read the new .fqr format (K2GO-566); ' +
        'before the fix it read the retired extracts.json and listed nothing',
    );

    const root = fs.mkdtempSync(path.join(os.tmpdir(), 'fqr-behavior-'));
    try {
        fs.mkdirSync(path.join(root, 'ttest.fqr'));
        fs.writeFileSync(
            path.join(root, 'ttest.fqr', 'meta.json'),
            JSON.stringify({ bbox: [-108.5577, 28.0298, -101.073, 36.1073] }),
        );

        const regions = maps.listFqrRegions!(root);
        assert.ok(
            regions.ttest,
            'an existing FQR must appear in the list so it can be managed and deleted; it did not ' +
            '(this is the K2GO-566 bug: the list read the retired extracts.json)',
        );
        assert.deepEqual(
            regions.ttest.ui_bounds, [-108.5577, 28.0298, -101.073, 36.1073],
            'the listed region must carry its bounds (ui_bounds) from meta.json bbox',
        );
    } finally {
        fs.rmSync(root, { recursive: true, force: true });
    }
});
