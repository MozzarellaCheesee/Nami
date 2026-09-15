// Run: node --test web/tests/discord.test.cjs
const { test } = require('node:test');
const assert = require('node:assert/strict');
const vm = require('node:vm');
const fs = require('node:fs');
const path = require('node:path');
const html = fs.readFileSync(path.join(__dirname, '../app/index.html'), 'utf8');
const source = html.slice(html.indexOf('let discordRequest = 0;'), html.indexOf('async function changeMyPassword()'));
function context(api) {
  const elements = {};
  const ctx = { URL, token: 'user-A', T() { return ctx.token; }, api,
    document: { getElementById(id) { return elements[id] ||= { disabled: false, textContent: '' }; } },
    location: { assign(url) { ctx.destination = url; } }, setInterval() {}, elements };
  vm.createContext(ctx); vm.runInContext(source, ctx);
  return ctx;
}
test('OAuth starts for the signed-in user and navigates only to Discord', async () => {
  const requests = [];
  const ctx = context(async (path, options) => {
    requests.push([path, options.method]);
    return { authorize_url: 'https://discord.com/oauth2/authorize?state=single-use' };
  });
  await ctx.discordAction('authorize');
  assert.deepEqual(requests, [['/me/discord/authorize', 'POST']]);
  assert.equal(ctx.destination, 'https://discord.com/oauth2/authorize?state=single-use');
  const bad = context(async () => ({ authorize_url: 'https://discord.com.attacker.invalid/oauth2/authorize' }));
  await bad.discordAction('authorize');
  assert.equal(bad.destination, undefined);
  assert.match(bad.elements.discordStatus.textContent, /неверный адрес/);
});
test('a delayed response for another Nami login never changes the account UI or redirects', async () => {
  let resolve;
  const ctx = context(() => new Promise(r => { resolve = r; }));
  const pending = ctx.discordAction('authorize');
  ctx.token = 'user-B';
  resolve({ authorize_url: 'https://discord.com/oauth2/authorize?state=user-A' });
  await pending;
  assert.equal(ctx.destination, undefined);
});
test('polling does not cancel a pending OAuth action, and disconnect clears the displayed account', async () => {
  let resolve, calls = 0;
  const ctx = context(() => { calls++; return new Promise(r => { resolve = r; }); });
  const pending = ctx.discordAction('authorize');
  await ctx.loadDiscord(); assert.equal(calls, 1);
  resolve({ authorize_url: 'https://discord.com/oauth2/authorize' });
  await pending; assert.ok(ctx.destination);
  ctx.api = async (_, options = {}) => options.method === 'DELETE'
    ? { linked: false, revoked_remotely: true } : { configured: true, linked: false };
  await ctx.discordAction('disconnect');
  assert.equal(ctx.elements.discordStatus.textContent, 'Discord не подключён.');
  assert.equal(ctx.elements.discordDisconnect.disabled, true);
});
test('missing SDK is visible and is never described as published', () => {
  const ctx = context();
  ctx.renderDiscord({ configured: true, linked: true, username: '<script>bad</script>', presence_supported: false });
  assert.match(ctx.elements.discordStatus.textContent, /Публикация недоступна/);
  assert.doesNotMatch(ctx.elements.discordStatus.textContent, /Активность опубликована/);
  assert.equal(ctx.elements.discordStatus.innerHTML, undefined);
});
test('an old 401 response cannot sign out the newly selected Nami account', async () => {
  const ctx = context();
  let resolve, sentCredential;
  ctx.fetch = (_, options) => {
    sentCredential = options.headers.Authorization;
    return new Promise(r => { resolve = r; });
  };
  ctx.logout = () => { ctx.token = null; };
  vm.runInContext(html.slice(html.indexOf('async function api('), html.indexOf('function show(id)')), ctx);
  const pending = ctx.api('/me/discord');
  ctx.token = 'user-B'; resolve({ status: 401 });
  await assert.rejects(pending, /Сессия истекла/);
  assert.equal(sentCredential, 'Bearer user-A');
  assert.equal(ctx.token, 'user-B');
});
