import { defineConfig } from '@playwright/test';
import { resolve } from 'node:path';

const qaRoot = resolve(process.cwd(), '..', '.qa-temp', 'fe-cart-runtime-qa-20261108');

export default defineConfig({
    testDir: './playwright-e2e',
    testMatch: '**/cart-legacy-runtime.e2e.js',
    fullyParallel: false,
    workers: 1,
    retries: 0,
    timeout: 45_000,
    expect: { timeout: 8_000 },
    reporter: [
        ['list'],
        ['json', { outputFile: resolve(qaRoot, 'playwright-results.json') }],
    ],
    outputDir: resolve(qaRoot, 'playwright-output'),
    use: {
        baseURL: 'http://127.0.0.1:3101',
        browserName: 'chromium',
        channel: 'msedge',
        headless: true,
        screenshot: 'off',
        video: 'off',
        trace: 'off',
    },
    webServer: {
        command: 'node node_modules/vite/bin/vite.js --config vite.cart-runtime-qa.config.js --mode cart-qa --host 127.0.0.1 --port 3101 --strictPort --open=false',
        url: 'http://127.0.0.1:3101/__cart-qa__/health',
        reuseExistingServer: false,
        timeout: 30_000,
        stdout: 'pipe',
        stderr: 'pipe',
    },
});
