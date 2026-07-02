/// <reference types="node" />
import test from 'node:test';
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';

const LANG_DIR = path.join(__dirname, '..', 'public', 'lang');
const LOCALES = ['es', 'fr', 'hi', 'pt', 'ru'];

// Evaluate a lang/<code>.js file in a sandbox that only provides a `window`
// object, then return the resulting i18n key set. Each file assigns
// `window.i18n = { ... };`, so after eval `window.i18n` holds the translations.
function loadKeys(code: string): string[] {
    const file = path.join(LANG_DIR, `${code}.js`);
    const source = fs.readFileSync(file, 'utf8');
    const window: { i18n?: Record<string, string> } = {};
    // eslint-disable-next-line no-eval
    eval(source);
    assert.ok(window.i18n, `${code}.js did not assign window.i18n`);
    return Object.keys(window.i18n as Record<string, string>).sort();
}

const enKeys = loadKeys('en');

test('en.js defines a non-empty key set', () => {
    assert.ok(enKeys.length > 0, 'en.js has no keys');
});

for (const locale of LOCALES) {
    test(`${locale}.js has exactly the same keys as en.js`, () => {
        const localeKeys = loadKeys(locale);
        const enSet = new Set(enKeys);
        const localeSet = new Set(localeKeys);

        const missing = enKeys.filter((k) => !localeSet.has(k));
        const extra = localeKeys.filter((k) => !enSet.has(k));

        assert.deepEqual(
            localeKeys,
            enKeys,
            `Locale "${locale}" key mismatch.\n` +
                `Missing (in en, absent in ${locale}): ${missing.join(', ') || '(none)'}\n` +
                `Extra (in ${locale}, absent in en): ${extra.join(', ') || '(none)'}`,
        );
    });
}
