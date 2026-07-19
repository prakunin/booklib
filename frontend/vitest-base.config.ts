import {defineConfig} from 'vitest/config';
import angular from '@analogjs/vite-plugin-angular';

const shouldEnforceCoverageGate = process.env['COVERAGE_GATE'] === '1';

export default defineConfig({
  plugins: [angular({tsconfig: 'tsconfig.spec.json'})],
  test: {
    globals: true,
    environment: 'jsdom',
    isolate: true,
    include: ['src/**/*.spec.ts'],
    exclude: ['src/**/._*.spec.ts', 'src/**/.__*.spec.ts'],
    testTimeout: 15000,
    reporters: [
      ['default', {summary: false}],
      ['junit', {outputFile: 'test-results/vitest-results.xml'}]
    ],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json', 'json-summary', 'lcov'],
      reportsDirectory: 'coverage/booklib',
      include: ['src/app/**/*.ts', 'src/main.ts'],
      exclude: ['src/app/**/*.spec.ts', 'src/app/**/*.module.ts', 'src/**/*.d.ts', 'src/**/._*', 'src/**/.__*'],
      thresholds: shouldEnforceCoverageGate ? {
        statements: 90,
        branches: 90,
        functions: 90,
        lines: 90
      } : undefined
    }
  }
});
