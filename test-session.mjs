import { chromium } from 'playwright';
import fs from 'fs';

const BASE = 'http://localhost:8081';
const COOKIE_FILE = 'test-session-cookie.json';
const PHASE = process.argv[2] || 'login';

async function run() {
  const browser = await chromium.launch({
    channel: 'chrome',
    headless: true,
  });
  const context = await browser.newContext();

  if (PHASE === 'login') {
    console.log('[Phase 1] Logging in...');
    const page = await context.newPage();
    await page.goto(`${BASE}/login`, { waitUntil: 'networkidle' });

    await page.fill('input[name="username"]', 'xtc');
    await page.fill('#rawPassword', 'a11111111');

    // The form JS: sha256(rawPassword) -> hidden password field -> form.submit()
    await Promise.all([
      page.waitForNavigation({ waitUntil: 'networkidle', timeout: 15000 }),
      page.click('#submitBtn'),
    ]);

    const finalUrl = page.url();
    console.log(`[Phase 1] After login, URL: ${finalUrl}`);

    if (finalUrl.includes('/login')) {
      const errorText = await page.textContent('.alert-danger').catch(() => '');
      console.log(`[Phase 1] FAIL - Still on login page. Error: ${errorText}`);
      await browser.close();
      process.exit(1);
    }

    const cookies = await context.cookies();
    const jsid = cookies.find(c => c.name === 'JSESSIONID');
    console.log(`[Phase 1] JSESSIONID: ${jsid ? jsid.value.substring(0, 12) + '...' : 'NOT FOUND'}`);
    console.log(`[Phase 1] All cookies: ${cookies.map(c => c.name).join(', ')}`);

    fs.writeFileSync(COOKIE_FILE, JSON.stringify(cookies, null, 2));
    console.log(`[Phase 1] SUCCESS - Cookies saved. Restart the server, then run: node test-session.mjs verify`);

  } else if (PHASE === 'verify') {
    if (!fs.existsSync(COOKIE_FILE)) {
      console.error('[Phase 2] No saved cookies. Run login phase first.');
      process.exit(1);
    }

    const cookies = JSON.parse(fs.readFileSync(COOKIE_FILE, 'utf8'));
    const savedJsid = cookies.find(c => c.name === 'JSESSIONID');
    console.log(`[Phase 2] Saved JSESSIONID: ${savedJsid ? savedJsid.value.substring(0, 12) + '...' : 'NOT FOUND'}`);

    await context.addCookies(cookies);

    const page = await context.newPage();
    const redirects = [];
    page.on('response', resp => {
      if (resp.status() >= 300 && resp.status() < 400) {
        redirects.push(`${resp.status()} ${resp.url()} -> ${resp.headers()['location'] || '?'}`);
      }
    });

    console.log('[Phase 2] Navigating to /build ...');
    await page.goto(`${BASE}/build`, { waitUntil: 'networkidle', timeout: 15000 });

    const finalUrl = page.url();
    const newCookies = await context.cookies();
    const newJsid = newCookies.find(c => c.name === 'JSESSIONID');

    console.log(`[Phase 2] Final URL: ${finalUrl}`);
    if (redirects.length > 0) console.log(`[Phase 2] Redirects: ${redirects.join(' | ')}`);
    console.log(`[Phase 2] New JSESSIONID: ${newJsid ? newJsid.value.substring(0, 12) + '...' : 'NONE'}`);
    if (savedJsid && newJsid) {
      console.log(`[Phase 2] Cookie changed: ${savedJsid.value !== newJsid.value}`);
    }

    if (finalUrl.includes('/login')) {
      console.log('[Phase 2] FAIL - Redirected to login!');
    } else if (finalUrl.includes('/build')) {
      const title = await page.title();
      console.log(`[Phase 2] SUCCESS - Session survived! Title: ${title}`);
    } else {
      console.log(`[Phase 2] UNEXPECTED: ${finalUrl}`);
    }
  }

  await browser.close();
}

run().catch(e => {
  console.error('Test error:', e.message);
  process.exit(1);
});
