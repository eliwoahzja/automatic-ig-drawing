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

test('AI handler requires Gemini key and private server token', async () => {
  const savedKey = process.env.GEMINI_API_KEY;
  const savedToken = process.env.AI_DRAW_ACCESS_TOKEN;
  delete process.env.GEMINI_API_KEY;
  delete process.env.AI_DRAW_ACCESS_TOKEN;
  try {
    const res = reply();
    await handler({ method: 'POST', headers: {}, body: {} }, res);
    assert.equal(res.statusCode, 503);
  } finally {
    if (savedKey) process.env.GEMINI_API_KEY = savedKey;
    if (savedToken) process.env.AI_DRAW_ACCESS_TOKEN = savedToken;
  }
});

test('Gemini image analysis and stroke generation preserve Android response contract', async () => {
  const savedKey = process.env.GEMINI_API_KEY;
  const savedToken = process.env.AI_DRAW_ACCESS_TOKEN;
  const savedFetch = global.fetch;
  process.env.GEMINI_API_KEY = 'unit-test-gemini-key';
  process.env.AI_DRAW_ACCESS_TOKEN = 'unit-test-access-token';
  const calls = [];
  global.fetch = async (url, options) => {
    calls.push({ url, options, body: JSON.parse(options.body) });
    const payload = calls.length === 1
      ? { subject: 'A small character', essentialDetails: ['Hat', 'Face'], lettering: [] }
      : { summary: 'Clean outline', strokes: [
        [[0.1, 0.1], [0.2, 0.2]], [[0.4, 0.4], [0.5, 0.5]]
      ] };
    return { ok: true, status: 200, json: async () => ({
      candidates: [{ content: { parts: [{ text: JSON.stringify(payload) }] } }]
    }) };
  };
  try {
    const res = reply();
    await handler({
      method: 'POST',
      headers: { authorization: 'Bearer unit-test-access-token' },
      body: { image: 'data:image/jpeg;base64,AA==' }
    }, res);
    assert.equal(res.statusCode, 200);
    assert.equal(res.body.strokes.length, 2);
    assert.equal(calls.length, 2);
    for (const call of calls) {
      assert.match(call.url, /^https:\/\/generativelanguage.googleapis.com\/v1beta\/models\//);
      assert.equal(call.options.headers['x-goog-api-key'], 'unit-test-gemini-key');
      assert.equal(call.body.contents[0].parts[1].inlineData.mimeType, 'image/jpeg');
      assert.equal(call.body.contents[0].parts[1].inlineData.data, 'AA==');
      assert.equal(call.body.generationConfig.responseMimeType, 'application/json');
    }
  } finally {
    global.fetch = savedFetch;
    if (savedKey === undefined) delete process.env.GEMINI_API_KEY;
    else process.env.GEMINI_API_KEY = savedKey;
    if (savedToken === undefined) delete process.env.AI_DRAW_ACCESS_TOKEN;
    else process.env.AI_DRAW_ACCESS_TOKEN = savedToken;
  }
});
