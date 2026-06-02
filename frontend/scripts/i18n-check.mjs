#!/usr/bin/env node

import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const SOURCE_LANG = 'en';
const I18N_DIR = path.join('src', 'i18n');
const LOADER_PATH = path.join('src', 'app', 'core', 'config', 'transloco-loader.ts');

function readText(filePath) {
  return fs.readFileSync(filePath, 'utf8');
}

function readJson(filePath) {
  return JSON.parse(readText(filePath));
}

function listDirectories(dirPath) {
  return fs.readdirSync(dirPath)
    .filter(entry => fs.statSync(path.join(dirPath, entry)).isDirectory())
    .sort();
}

function listJsonBasenames(dirPath) {
  return fs.readdirSync(dirPath)
    .filter(entry => entry.endsWith('.json'))
    .map(entry => entry.slice(0, -'.json'.length))
    .sort();
}

export function parseAvailableLangs(loaderSource) {
  const loadersMatch = loaderSource.match(/const\s+LAZY_LANG_LOADERS[\s\S]*?=\s*\{([\s\S]*?)\};/);
  if (!loadersMatch) {
    throw new Error('Could not find LAZY_LANG_LOADERS in transloco-loader.ts');
  }

  const langs = new Set([SOURCE_LANG]);
  const loaderBody = loadersMatch[1];
  const langRegex = /^\s*['"]?([a-z][a-z0-9-]*)['"]?\s*:/gm;
  let match;
  while ((match = langRegex.exec(loaderBody)) !== null) {
    langs.add(match[1]);
  }

  return [...langs].sort();
}

export function flattenStringKeys(value, prefix = '') {
  if (!value || typeof value !== 'object' || Array.isArray(value)) {
    return new Map();
  }

  const keys = new Map();
  for (const [key, child] of Object.entries(value)) {
    const nextPrefix = prefix ? `${prefix}.${key}` : key;
    if (child && typeof child === 'object' && !Array.isArray(child)) {
      for (const [childKey, childValue] of flattenStringKeys(child, nextPrefix)) {
        keys.set(childKey, childValue);
      }
    } else if (typeof child === 'string') {
      keys.set(nextPrefix, child);
    }
  }
  return keys;
}

export function extractPlaceholders(value) {
  const placeholders = new Set();
  const placeholderRegex = /\{\{\s*([^{}]+?)\s*\}\}/g;
  let match;
  while ((match = placeholderRegex.exec(value)) !== null) {
    placeholders.add(match[1].replace(/\s+/g, ' ').trim());
  }
  return [...placeholders].sort();
}

function parseIndexImports(indexSource) {
  const imports = new Map();
  const importRegex = /import\s+([A-Za-z_$][\w$]*)\s+from\s+'\.\/([^']+\.json)'/g;
  let match;
  while ((match = importRegex.exec(indexSource)) !== null) {
    imports.set(match[2].slice(0, -'.json'.length), match[1]);
  }
  return imports;
}

function parseTranslationObject(indexSource) {
  const objectMatch = indexSource.match(/const\s+translations\s*:\s*Translation\s*=\s*\{([\s\S]*?)\};/);
  return objectMatch ? objectMatch[1] : '';
}

function objectContainsVariable(objectBody, variableName) {
  const escaped = variableName.replace(/[.*+?^${}()|[\]\\]/g, '\\$&');
  return new RegExp(`(^|[,\\s{])${escaped}([,\\s}:]|$)`).test(objectBody);
}

function compareSets(left, right) {
  return {
    missing: [...left].filter(value => !right.has(value)).sort(),
    extra: [...right].filter(value => !left.has(value)).sort(),
  };
}

function formatList(values, limit = 12) {
  if (values.length <= limit) {
    return values.join(', ');
  }
  return `${values.slice(0, limit).join(', ')} ... (${values.length} total)`;
}

export function runI18nCheck(options = {}) {
  const frontendRoot = options.frontendRoot ?? process.cwd();
  const log = options.log ?? console;
  const i18nDir = path.join(frontendRoot, I18N_DIR);
  const sourceDir = path.join(i18nDir, SOURCE_LANG);
  const loaderPath = path.join(frontendRoot, LOADER_PATH);

  const errors = [];
  const cleanup = [];

  let availableLangs;
  let components;
  try {
    availableLangs = parseAvailableLangs(readText(loaderPath));
    components = listJsonBasenames(sourceDir);
  } catch (error) {
    errors.push(error.message);
    return {ok: false, errors, cleanup};
  }

  const expectedLangSet = new Set(availableLangs);
  const directoryLangs = listDirectories(i18nDir);
  const directoryLangSet = new Set(directoryLangs);
  const langComparison = compareSets(expectedLangSet, directoryLangSet);

  for (const lang of langComparison.missing) {
    errors.push(`Configured locale "${lang}" is missing directory ${path.join(I18N_DIR, lang)}`);
  }
  for (const lang of langComparison.extra) {
    errors.push(`Locale directory ${path.join(I18N_DIR, lang)} is not configured in AVAILABLE_LANGS`);
  }

  const sourceComponentSet = new Set(components);
  const sourceTranslations = new Map();
  for (const component of components) {
    const filePath = path.join(sourceDir, `${component}.json`);
    try {
      sourceTranslations.set(component, flattenStringKeys(readJson(filePath)));
    } catch (error) {
      errors.push(`Invalid source JSON ${path.relative(frontendRoot, filePath)}: ${error.message}`);
    }
  }

  for (const lang of availableLangs) {
    const langDir = path.join(i18nDir, lang);
    const indexPath = path.join(langDir, 'index.ts');
    if (!fs.existsSync(langDir)) {
      continue;
    }

    if (!fs.existsSync(indexPath)) {
      errors.push(`Locale "${lang}" is missing ${path.relative(frontendRoot, indexPath)}`);
      continue;
    }

    const indexSource = readText(indexPath);
    const imports = parseIndexImports(indexSource);
    const importedComponents = new Set(imports.keys());
    const importComparison = compareSets(sourceComponentSet, importedComponents);

    for (const component of importComparison.missing) {
      errors.push(`Locale "${lang}" index.ts does not import ${component}.json`);
    }
    for (const component of importComparison.extra) {
      errors.push(`Locale "${lang}" index.ts imports non-source component ${component}.json`);
    }

    const translationsObject = parseTranslationObject(indexSource);
    if (!translationsObject) {
      errors.push(`Locale "${lang}" index.ts is missing the translations object`);
    }
    for (const [component, variableName] of imports) {
      if (sourceComponentSet.has(component) && !objectContainsVariable(translationsObject, variableName)) {
        errors.push(`Locale "${lang}" index.ts imports ${component}.json as ${variableName} but does not export it`);
      }
    }

    for (const component of components) {
      const filePath = path.join(langDir, `${component}.json`);
      if (!fs.existsSync(filePath)) {
        errors.push(`Locale "${lang}" is missing ${path.relative(frontendRoot, filePath)}`);
        continue;
      }

      let json;
      try {
        json = readJson(filePath);
      } catch (error) {
        errors.push(`Invalid JSON ${path.relative(frontendRoot, filePath)}: ${error.message}`);
        continue;
      }

      if (lang === SOURCE_LANG) {
        continue;
      }

      const sourceKeys = sourceTranslations.get(component) ?? new Map();
      const translatedKeys = flattenStringKeys(json);
      const missingKeys = [...sourceKeys.keys()].filter(key => !translatedKeys.has(key)).sort();
      const extraKeys = [...translatedKeys.keys()].filter(key => !sourceKeys.has(key)).sort();

      if (missingKeys.length > 0) {
        cleanup.push(`${lang}/${component}.json missing ${missingKeys.length} source key(s): ${formatList(missingKeys)}`);
      }
      if (extraKeys.length > 0) {
        cleanup.push(`${lang}/${component}.json has ${extraKeys.length} stale key(s): ${formatList(extraKeys)}`);
      }

      for (const [key, sourceValue] of sourceKeys) {
        if (!translatedKeys.has(key)) {
          continue;
        }
        const translatedValue = translatedKeys.get(key);
        const sourcePlaceholders = new Set(extractPlaceholders(sourceValue));
        const translatedPlaceholders = new Set(extractPlaceholders(translatedValue));
        const unknownPlaceholders = [...translatedPlaceholders].filter(placeholder => !sourcePlaceholders.has(placeholder)).sort();
        const omittedPlaceholders = [...sourcePlaceholders].filter(placeholder => !translatedPlaceholders.has(placeholder)).sort();
        if (unknownPlaceholders.length > 0) {
          errors.push(
            `${lang}/${component}.json key "${key}" uses unknown placeholder(s): ${unknownPlaceholders.join(', ')}`
          );
        } else if (omittedPlaceholders.length > 0) {
          cleanup.push(
            `${lang}/${component}.json key "${key}" omits source placeholder(s): ${omittedPlaceholders.join(', ')}`
          );
        }
      }
    }
  }

  if (cleanup.length > 0 && log.info) {
    const configuredMaxReportItems = Number.parseInt(process.env.I18N_CHECK_MAX_REPORT ?? '120', 10);
    const maxReportItems = Number.isFinite(configuredMaxReportItems) ? configuredMaxReportItems : 120;
    const visibleCleanup = maxReportItems === 0 ? cleanup : cleanup.slice(0, maxReportItems);
    log.info(`Weblate cleanup report (${cleanup.length} item(s), non-blocking):`);
    for (const item of visibleCleanup) {
      log.info(`  - ${item}`);
    }
    if (visibleCleanup.length < cleanup.length) {
      log.info(`  ... ${cleanup.length - visibleCleanup.length} more cleanup item(s). Set I18N_CHECK_MAX_REPORT=0 for no limit.`);
    }
  }

  if (errors.length > 0 && log.error) {
    log.error('i18n structural check failed:');
    for (const error of errors) {
      log.error(`  - ${error}`);
    }
  } else if (log.info) {
    log.info(`i18n structural check passed for ${availableLangs.length} locale(s) and ${components.length} component(s).`);
  }

  return {ok: errors.length === 0, errors, cleanup};
}

const currentFile = fileURLToPath(import.meta.url);
if (process.argv[1] === currentFile) {
  const result = runI18nCheck();
  process.exit(result.ok ? 0 : 1);
}
