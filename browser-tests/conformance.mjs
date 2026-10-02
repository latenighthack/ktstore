import { chromium, firefox, webkit } from 'playwright';
import { createServer } from 'node:http';
import { readFile } from 'node:fs/promises';
import assert from 'node:assert/strict';
const bundle = await readFile(new URL('./build/kotlin-webpack/js/developmentExecutable/storage-fixture.js', import.meta.url));
const results = [];
const server = createServer((req, res) => {
  const url = new URL(req.url, 'http://localhost');
  if (url.pathname === '/fixture.js' || url.pathname === '/worker.js') {
    res.setHeader('Content-Type', 'application/javascript'); res.end(bundle);
  } else if (url.pathname === '/result') {
    results.push(url.searchParams.get('value')); res.end('ok');
  } else { res.setHeader('Content-Type', 'text/html'); res.end('<!doctype html><script src="/fixture.js"></script>'); }
});
await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
const origin = `http://127.0.0.1:${server.address().port}`;
async function until(predicate, description) {
  const deadline = Date.now() + 20000;
  while (!predicate()) {
    if (Date.now() >= deadline) throw new Error(`Timed out: ${description}`);
    await new Promise(resolve => setTimeout(resolve, 25));
  }
}
try {
  for (const [name, launcher] of Object.entries({ chromium, firefox, webkit })) {
    if (process.env.BROWSER && process.env.BROWSER !== name) continue;
    const browser = await launcher.launch(name === "chromium" && process.env.CHROME_PATH ? { executablePath: process.env.CHROME_PATH } : {});
    try {
      const context = await browser.newContext();
      const page = await context.newPage();
      page.on('pageerror', error => console.error(error));
      await page.goto(origin);
      assert.equal(await page.evaluate(() => ktstoreConformance()), 'ok');
      assert.equal(await page.evaluate(() => ktstoreTest('register')), 'register');
      await page.evaluate(async () => { await navigator.serviceWorker.register('/worker.js'); await navigator.serviceWorker.ready; });
      const workerCall = action => page.evaluate(async action => {
        const registration = await navigator.serviceWorker.ready;
        return new Promise((resolve, reject) => {
          const channel = new MessageChannel();
          const timeout = setTimeout(() => reject(new Error('Worker timeout')), 15000);
          channel.port1.onmessage = event => { clearTimeout(timeout); resolve(event.data); };
          registration.active.postMessage(action, [channel.port2]);
        });
      }, action);
      if (name === 'chromium') {
        // Keep the protocol transport on about:blank, never an application page.
        const monitor = await context.newPage();
        const session = await context.newCDPSession(monitor);
        const registrations = new Map();
        const versions = new Map();
        session.on('ServiceWorker.workerRegistrationUpdated', event => event.registrations.forEach(r => registrations.set(r.registrationId, r)));
        session.on('ServiceWorker.workerVersionUpdated', event => event.versions.forEach(v => versions.set(v.versionId, v)));
        await session.send('ServiceWorker.enable');
        await until(() => [...registrations.values()].some(r => r.scopeURL === origin + '/'), 'registration');
        const registration = [...registrations.values()].find(r => r.scopeURL === origin + '/');
        await page.close();
        assert(context.pages().every(p => !p.url().startsWith(origin)));
        await session.send('ServiceWorker.deliverPushMessage', { origin, registrationId: registration.registrationId, data: 'action' });
        await until(() => results.includes('action'), 'worker action with no application page');
        await until(() => [...versions.values()].some(v => v.registrationId === registration.registrationId && v.runningStatus === 'running'), 'running worker');
        const version = [...versions.values()].find(v => v.registrationId === registration.registrationId && v.runningStatus === 'running');
        await session.send('ServiceWorker.stopWorker', { versionId: version.versionId });
        await until(() => versions.get(version.versionId)?.runningStatus === 'stopped', 'worker termination');
        await session.send('ServiceWorker.deliverPushMessage', { origin, registrationId: registration.registrationId, data: 'receipt' });
        await until(() => results.includes('receipt'), 'restarted worker reads operation and writes receipt');
        const reopened = await context.newPage();
        await reopened.goto(origin);
        assert.equal(await reopened.evaluate(() => ktstoreTest('reconcile')), 'reconcile');
      } else {
        assert.equal(await workerCall('action'), 'action');
        assert.equal(await workerCall('receipt'), 'receipt');
        assert.equal(await page.evaluate(() => ktstoreTest('reconcile')), 'reconcile');
      }
      console.log(`${name}: page/worker persistence and explicit reconciliation passed`);
      await context.close();
    } finally { await browser.close(); }
  }
} finally { server.close(); }
