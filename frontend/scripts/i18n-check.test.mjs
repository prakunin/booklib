import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import test from 'node:test';

import {extractPlaceholders, runI18nCheck} from './i18n-check.mjs';

function writeFile(root, relativePath, contents) {
  const filePath = path.join(root, relativePath);
  fs.mkdirSync(path.dirname(filePath), {recursive: true});
  fs.writeFileSync(filePath, contents);
}

function writeJson(root, relativePath, value) {
  writeFile(root, relativePath, `${JSON.stringify(value, null, 2)}\n`);
}

function writeIndex(root, lang, components) {
  const imports = components.map(component => `import ${component.variable} from './${component.file}.json';`).join('\n');
  const variables = components.map(component => component.variable).join(', ');
  writeFile(root, `src/i18n/${lang}/index.ts`, `import {Translation} from '@jsverse/transloco';\n${imports}\nconst translations: Translation = {${variables}};\nexport default translations;\n`);
}

function fixture(mutator = () => {}) {
  const root = fs.mkdtempSync(path.join(os.tmpdir(), 'grimmory-i18n-check-'));
  writeFile(root, 'src/app/core/config/transloco-loader.ts', `
const LAZY_LANG_LOADERS: Record<string, () => Promise<{default: Translation}>> = {
  de: () => import('../../../i18n/de'),
};
export const AVAILABLE_LANGS = ['en', ...Object.keys(LAZY_LANG_LOADERS)];
`);

  const components = [{file: 'common', variable: 'common'}];
  writeJson(root, 'src/i18n/en/common.json', {
    greeting: 'Hello {{ name }}',
    farewell: 'Goodbye',
  });
  writeJson(root, 'src/i18n/de/common.json', {
    greeting: 'Hallo {{ name }}',
  });
  writeIndex(root, 'en', components);
  writeIndex(root, 'de', components);
  mutator(root);
  return root;
}

function quietLog() {
  return {info() {}, error() {}};
}

test('extractPlaceholders normalizes Transloco placeholders', () => {
  assert.deepEqual(extractPlaceholders('Hello {{ name }} and {{count}}'), ['count', 'name']);
});

test('missing translated keys are reported as Weblate cleanup without failing', () => {
  const root = fixture();
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, true);
  assert.equal(result.errors.length, 0);
  assert.match(result.cleanup.join('\n'), /de\/common\.json missing 1 source key/);
});

test('extra translated keys are reported as Weblate cleanup without failing', () => {
  const root = fixture(rootDir => {
    writeJson(rootDir, 'src/i18n/de/common.json', {
      greeting: 'Hallo {{ name }}',
      stale: 'Old',
    });
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, true);
  assert.match(result.cleanup.join('\n'), /de\/common\.json has 1 stale key/);
});

test('invalid JSON fails the structural check', () => {
  const root = fixture(rootDir => {
    writeFile(rootDir, 'src/i18n/de/common.json', '{');
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /Invalid JSON/);
});

test('missing component file fails the structural check', () => {
  const root = fixture(rootDir => {
    fs.unlinkSync(path.join(rootDir, 'src/i18n/de/common.json'));
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /missing .*de.*common\.json/);
});

test('unknown placeholder drift fails the structural check', () => {
  const root = fixture(rootDir => {
    writeJson(rootDir, 'src/i18n/de/common.json', {
      greeting: 'Hallo {{ user }}',
    });
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /unknown placeholder/);
});

test('omitted source placeholders are reported as Weblate cleanup without failing', () => {
  const root = fixture(rootDir => {
    writeJson(rootDir, 'src/i18n/de/common.json', {
      greeting: 'Hallo',
    });
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, true);
  assert.match(result.cleanup.join('\n'), /omits source placeholder/);
});

test('unsupported locale directories fail the structural check', () => {
  const root = fixture(rootDir => {
    fs.mkdirSync(path.join(rootDir, 'src/i18n/fr'), {recursive: true});
  });
  const result = runI18nCheck({frontendRoot: root, log: quietLog()});

  assert.equal(result.ok, false);
  assert.match(result.errors.join('\n'), /not configured in AVAILABLE_LANGS/);
});
