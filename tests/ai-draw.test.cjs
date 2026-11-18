const assert = require('node:assert/strict');
const test = require('node:test');
const handler = require('../api/ai-draw.js');
function reply() {
  return {
    statusCode: 0,
    headers: {},
    setHeader(name, value) { this.headers[name] = value; },
    status(code) { this.statusCode = code; return this; },
    json(value) { this.body = value; return this; }
  };
}
test('AI handler requires POST', async () => {
  const res = reply();
  await handler({ method: 'GET', headers: {} }, res);
  assert.equal(res.statusCode, 405);
});
test('AI handler denies requests when server secrets are absent', async () => {
  const saved = process.env.OPENAI_API_KEY;
  const savedToken = process.env.AI_DRAW_ACCESS_TOKEN;
  delete process.env.OPENAI_API_KEY;
  delete process.env.AI_DRAW_ACCESS_TOKEN;
  try {
    const res = reply();
    await handler({ method: 'POST', headers: {}, body: {} }, res);
    assert.equal(res.statusCode, 503);
  } finally {
    if (saved) process.env.OPENAI_API_KEY = saved;
    if (savedToken) process.env.AI_DRAW_ACCESS_TOKEN = savedToken;
  }
});
