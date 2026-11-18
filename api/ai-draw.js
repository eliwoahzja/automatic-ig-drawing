const crypto = require('node:crypto');

const imageSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    subject: { type: 'string' },
    essentialDetails: { type: 'array', items: { type: 'string' } },
    lettering: { type: 'array', items: { type: 'string' } }
  },
  required: ['subject', 'essentialDetails', 'lettering']
};

const strokeSchema = {
  type: 'object',
  additionalProperties: false,
  properties: {
    summary: { type: 'string' },
    strokes: {
      type: 'array',
      items: { type: 'array', items: { type: 'array', items: { type: 'number' } } }
    }
  },
  required: ['summary', 'strokes']
};

function validToken(received, expected) {
  if (typeof received !== 'string' || !received.startsWith('Bearer ') || !expected)
    return false;
  const provided = Buffer.from(received.slice(7));
  const required = Buffer.from(expected);
  return provided.length === required.length &&
    crypto.timingSafeEqual(provided, required);
}

async function runVision(image, instruction, schema, name, budget) {
  const response = await fetch('https://api.openai.com/v1/responses', {
    method: 'POST',
    headers: {
      Authorization: 'Bearer ' + process.env.OPENAI_API_KEY,
      'Content-Type': 'application/json'
    },
    signal: AbortSignal.timeout(75000),
    body: JSON.stringify({
      model: process.env.AI_MODEL || 'gpt-4.1',
      store: false,
      max_output_tokens: budget,
      text: { format: { type: 'json_schema', name, strict: true, schema } },
      input: [{
        role: 'user',
        content: [
          { type: 'input_text', text: instruction },
          { type: 'input_image', image_url: image, detail: 'high' }
        ]
      }]
    })
  });
  if (!response.ok) {
    const error = await response.text();
    throw new Error('Vision provider returned ' + response.status +
      ': ' + error.slice(0, 150));
  }
  const result = await response.json();
  let content = '';
  for (const item of result.output || []) {
    for (const section of item.content || []) {
      if (section.type === 'output_text') content += section.text || '';
    }
  }
  if (!content) throw new Error('Vision provider returned no structured text');
  return JSON.parse(content);
}

module.exports = async function handler(req, res) {
  res.setHeader('Cache-Control', 'no-store');
  if (req.method !== 'POST') {
    res.setHeader('Allow', 'POST');
    return res.status(405).json({ error: 'Use POST' });
  }
  if (!process.env.OPENAI_API_KEY || !process.env.AI_DRAW_ACCESS_TOKEN)
    return res.status(503).json({ error: 'The private AI drawer server is not configured' });
  if (!validToken(req.headers.authorization, process.env.AI_DRAW_ACCESS_TOKEN))
    return res.status(401).json({ error: 'Invalid private access token' });

  try {
    const body = typeof req.body === 'string' ? JSON.parse(req.body) : req.body;
    const image = body && body.image;
    if (typeof image !== 'string' ||
        image.length > 1700000 ||
        !/^data:image\/jpeg;base64,[A-Za-z0-9+/=]+$/.test(image))
      return res.status(400).json({ error: 'Provide a small JPEG image' });

    const analysis = await runVision(image,
      'You are a careful visual analyst preparing an image for a pen plotter. ' +
      'Describe the main subject, critical shapes such as eyes, fingers, clothing seams, ' +
      'silhouette edges, and any readable text. Ignore irrelevant textures and noisy ' +
      'background details. Do not invent characters or missing words. ' +
      'Keep essentialDetails under 16 short descriptions.',
      imageSchema, 'semantic_analysis', 2000);

    const plan = await runVision(image,
      'You are the SECOND independent AI model stage, an expert vector line artist. ' +
      'Use the following scene analysis to plan ordered connected strokes: ' +
      JSON.stringify(analysis).slice(0, 5000) + '. ' +
      'Output carefully traced smooth line-art paths based on the actual image, ' +
      'not decorative guesses. Use an array of strokes, each stroke is an array of [x,y] ' +
      'coordinates normalized between 0 and 1. ' +
      'Preserve subject silhouette, face details, important internal shapes, and the ' +
      'form of any lettering if you are certain. Up to 80 strokes, up to 28 vertices ' +
      'per stroke. Do not add dots, hatching or watermarks. ' +
      'Avoid line segments that jump through unrelated objects. ' +
      'Do not exaggerate, deform, or invent detail. Return useful line art, ' +
      'not a description of what you would draw.',
      strokeSchema, 'vector_strokes', 8000);

    const strokes = [];
    for (const path of (Array.isArray(plan.strokes) ? plan.strokes : []).slice(0, 100)) {
      if (!Array.isArray(path) || path.length < 2 || path.length > 110) continue;
      const points = [];
      for (const point of path) {
        if (!Array.isArray(point) || point.length !== 2 ||
            !point.every(v => typeof v === 'number' && Number.isFinite(v))) continue;
        points.push(point.map(v => Math.max(0, Math.min(1, v))));
      }
      if (points.length >= 2) strokes.push(points);
    }
    if (strokes.length < 2)
      return res.status(422).json({ error: 'AI did not return enough valid vector strokes' });
    return res.status(200).json({
      summary: String(plan.summary || analysis.subject).slice(0, 180),
      strokes
    });
  } catch (e) {
    return res.status(502).json({
      error: 'AI analysis or drawing failed: ' + (e.message || 'Unknown provider error').slice(0, 140)
    });
  }
};
