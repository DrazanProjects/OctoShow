// A stand-in for OctoPrint 1.11 and the plugins the display can use, good enough to
// drive the display app: REST, the raw push socket, thumbnails and an MJPEG webcam.
// The shapes of what it sends follow the OctoPrint and plugin sources, not guesswork.
//
//   node server.js [port]
//   GET /mock/set?scene=printing|paused|ready|done|offline|error|prompt|heating|dead&progress=0.4&tool=180&total=86000&display=Some_name.gcode
//
// It also plays the Tasmota smart plug the printer and the Pi are powered through
// (GET /cm?cmnd=Power%20Off). "dead" is the Pi with its power cut: nothing answers but the plug.
//
// Add &without=camera,dashboard,thumbs,lights,presets,shutdown,bed,appkeys (any of them) to be
// an OctoPrint that lacks those: no webcam set up, plugins not installed, no temperature
// presets, no shutdown command, a printer with no heated bed, no way to ask for a key.
// "deadcamera" is the usual state of an OctoPi with no camera plugged in: one is set up,
// and nothing answers at its address.
const http = require('http');
const fs = require('fs');
const path = require('path');
const { WebSocketServer } = require('ws');

const PORT = +process.argv[2] || 5000;
// Only this machine can reach it, which is all the tests need. To point a real display
// at it, set MOCK_HOST=0.0.0.0: it has a fixed key and takes orders from anyone.
const HOST = process.env.MOCK_HOST || '127.0.0.1';
const KEY = 'MOCKKEY0123456789ABCDEF';
const ASSETS = path.join(__dirname, 'assets');
const now = () => Date.now() / 1000;

const FILES = [
  { name: 'CHESSBOARD_top_front_left_PLA_8h8m.gcode', thumb: 'chessboard', est: 29280, fil: 47210, layers: 33, maxZ: 6.6, age: 1.4 * 3600, last: null },
  { name: 'Cable_bracket_v3_PETG_1h52m.gcode', thumb: 'bracket', est: 6720, fil: 11840, layers: 270, maxZ: 54.0, age: 26 * 3600, last: true },
  { name: 'Spiral_vase_tall_PLA_3h40m.gcode', thumb: 'vase', est: 13200, fil: 21900, layers: 1100, maxZ: 220.0, age: 3 * 86400, last: true },
  { name: 'Phone stand (angled)_PLA_2h14m.gcode', thumb: 'stand', est: 8040, fil: 15300, layers: 320, maxZ: 64.0, age: 6 * 86400, last: false },
  { name: 'Desk_clips_x6_PLA_48m.gcode', thumb: 'clips', est: 2880, fil: 4100, layers: 85, maxZ: 17.0, age: 9 * 86400, last: true },
  { name: 'calibration_cube_PLA_22m.gcode', thumb: null, est: 1320, fil: 1900, layers: 100, maxZ: 20.0, age: 40 * 86400, last: true },
];
const FOLDER = { name: 'Organizers', children: ['Drawer_divider_60mm_PLA_1h10m.gcode', 'Pegboard_hook_PLA_35m.gcode'] };

function entry(f, folder) {
  const p = (folder ? folder + '/' : '') + f.name;
  const e = {
    name: f.name, display: f.name, path: p, type: 'machinecode', typePath: ['machinecode', 'gcode'], origin: 'local',
    size: 4_000_000, date: Math.floor(now() - f.age), hash: 'abc',
    gcodeAnalysis: {
      estimatedPrintTime: f.est, filament: { tool0: { length: f.fil, volume: f.fil * 0.0024 } },
      // PrintTimeGenius-sized ballast, to prove the parser skips it
      progress: Array.from({ length: 600 }, (_, i) => [i / 600, f.est * (1 - i / 600)]),
      analysisPending: false, compensatedPrintTime: f.est, dimensions: { width: 100, depth: 100, height: f.maxZ },
      printingArea: { maxX: 150, maxY: 150, maxZ: f.maxZ, minX: 50, minY: 50, minZ: 0 },
    },
    refs: { resource: `http://localhost/api/files/local/${p}`, download: `http://localhost/downloads/files/local/${p}` },
  };
  if (!lacks('dashboard')) e.dashboard = { layer_move_array: JSON.stringify(Array.from({ length: f.layers + 1 }, () => 400)), filament_change_array: '[]' };
  if (f.thumb && !lacks('thumbs')) {
    e.thumbnail = `plugin/prusaslicerthumbnails/thumbnail/${folder ? folder + '/' : ''}${encodeURIComponent(f.name.replace(/\.gcode$/, ''))}.png?20261004222323`;
    e.thumbnail_src = 'prusaslicerthumbnails';
  }
  if (f.last !== null) e.prints = { success: f.last ? 2 : 0, failure: f.last ? 0 : 1, last: { success: f.last, date: Math.floor(now() - f.age / 2), printTime: f.est } };
  return e;
}
function listing() {
  const files = FILES.map(f => entry(f));
  files.push({
    name: FOLDER.name, display: FOLDER.name, path: FOLDER.name, type: 'folder', typePath: ['folder'], origin: 'local', size: 123,
    children: FOLDER.children.map((n, i) => entry({ name: n, thumb: i === 0 ? 'bracket' : null, est: 4200 - i * 2100, fil: 6000, layers: 120, maxZ: 24, age: 12 * 86400, last: null }, FOLDER.name)),
  });
  return { files, free: 20e9, total: 30e9 };
}
function find(p) {
  for (const e of listing().files) {
    if (e.path === p) return e;
    for (const c of e.children || []) if (c.path === p) return c;
  }
  return null;
}

// ---------------- G-code for files that have no slicer preview ----------------
// The display draws its own picture of such files from their G-code, so the mock
// needs some: laid out the way OrcaSlicer writes it (labelled sections, a purge
// line under ";TYPE:Custom", relative extrusion), for a few simple solids. Each
// layer change is followed by the mark the Dashboard plugin adds to the files
// OctoPrint stores, which is what the display counts layers by.
const SOLIDS = {
  // a 20 mm cube with a square pocket in the top
  cube: { height: 20, slabs: z => z <= 16 ? [[100, 100, 120, 120]]
      : [[100, 100, 120, 106], [100, 114, 120, 120], [100, 106, 106, 114], [114, 106, 120, 114]] },
  // a wall hook: plate, upright and a lip at the top
  hook: { height: 30, slabs: z => [[90, 100, 96, 112]].concat(z <= 4 ? [[96, 100, 130, 112]] : [], z > 25 ? [[96, 100, 106, 112]] : []) },
  // a stepped divider
  steps: { height: 24, slabs: z => [[80, 95, 140 - Math.floor(z / 6) * 14, 125]] },
};
function gcodeFor(name) {
  const solid = /cube/i.test(name) ? SOLIDS.cube : /hook/i.test(name) ? SOLIDS.hook : SOLIDS.steps;
  const out = ['; generated by OrcaSlicer (mock)', ';TYPE:Custom', 'G28', 'G92 E0',
    'G1 X-2.1 Y20 Z0.28 F5000', 'G1 X-2.1 Y145 Z0.28 F1500 E15', 'G92 E0', 'G90', 'M83'];
  const f = v => v.toFixed(3);
  let layer = 0;
  for (let z = 0.2; z <= solid.height + 1e-6; z += 0.2) {
    layer++;
    out.push(';LAYER_CHANGE');
    if (!lacks('dashboard')) out.push(`M117 DASHBOARD_LAYER_INDICATOR ${layer}`);
    out.push(`;Z:${f(z)}`, `G1 Z${f(z)} F600`);
    for (const [x0, y0, x1, y1] of solid.slabs(z)) {
      out.push(';TYPE:Outer wall', ';WIDTH:0.45', `G1 X${x0} Y${y0} F6000`,
        `G1 X${x1} Y${y0} E${f((x1 - x0) * 0.033)}`, `G1 X${x1} Y${y1} E${f((y1 - y0) * 0.033)}`,
        `G1 X${x0} Y${y1} E${f((x1 - x0) * 0.033)}`, `G1 X${x0} Y${y0} E${f((y1 - y0) * 0.033)}`,
        ';TYPE:Internal solid infill');
      // filled one way on one layer and across it on the next, as slicers do
      let there = true;
      if (layer % 2) for (let y = y0 + 0.45; y < y1; y += 0.45, there = !there) {
        out.push(`G1 X${there ? x0 : x1} Y${f(y)} F6000`, `G1 X${there ? x1 : x0} Y${f(y)} E${f((x1 - x0) * 0.033)}`);
      } else for (let x = x0 + 0.45; x < x1; x += 0.45, there = !there) {
        out.push(`G1 X${f(x)} Y${there ? y0 : y1} F6000`, `G1 X${f(x)} Y${there ? y1 : y0} E${f((y1 - y0) * 0.033)}`);
      }
    }
  }
  return out.join('\n') + '\n';
}

// ---------------- simulated printer ----------------
const S = {
  phase: 'printing',      // offline | error | ready | printing | paused | finishing
  file: FILES[0], folder: null,
  started: now() - 29280 * 0.165,   // elapsed
  total: 29280,
  pausedAt: null,
  done: false,
  tool: { actual: 219.8, target: 220 }, bed: { actual: 55, target: 55 },
  fan: 100, lights: { 18: 60 }, prompt: null,
  speed: 60,              // simulated seconds per real second
  plug: true,             // the smart plug's relay
  dead: false,            // the Pi is off: OctoPrint answers nothing
  without: new Set(),     // the optional parts this OctoPrint doesn't have
};
const lacks = what => S.without.has(what);
const elapsed = () => (S.pausedAt ?? now()) - S.started;
const frac = () => Math.max(0, Math.min(1, elapsed() / S.total));

function stateFlags() {
  const p = S.phase;
  const printing = p === 'printing' || p === 'finishing';
  return {
    text: { offline: 'Offline', error: 'Error', ready: 'Operational', printing: 'Printing', paused: 'Paused', finishing: 'Finishing' }[p],
    error: p === 'error' ? 'Heating failed, system stopped! Heater_ID: 0 - Printer halted. kill() called!' : '',
    flags: {
      operational: !['offline', 'error'].includes(p), printing, cancelling: false, pausing: false, resuming: false,
      finishing: p === 'finishing', closedOrError: ['offline', 'error'].includes(p), error: p === 'error',
      paused: p === 'paused', ready: p === 'ready', sdReady: false,
    },
  };
}
function jobBlock() {
  const selected = S.file && S.phase !== 'offline';
  const f = S.file;
  const p = selected ? (S.folder ? S.folder + '/' : '') + f.name : null;
  return {
    file: selected ? { name: f.name, path: p, display: S.display || f.name, origin: 'local', size: 4_000_000, date: Math.floor(now() - f.age) }
                   : { name: null, path: null, display: null, origin: null, size: null, date: null },
    estimatedPrintTime: selected ? f.est : null, averagePrintTime: null, lastPrintTime: null,
    filament: selected ? { tool0: { length: f.fil, volume: f.fil * 0.0024 } } : null, user: selected ? '_api' : null,
  };
}
function progressBlock() {
  const active = ['printing', 'paused', 'finishing'].includes(S.phase);
  if (S.done && S.phase === 'ready') return { completion: 100, filepos: 4_000_000, printTime: Math.floor(S.total), printTimeLeft: 0, printTimeLeftOrigin: null };
  if (!active) return { completion: S.file && S.phase === 'ready' ? 0 : null, filepos: null, printTime: null, printTimeLeft: null, printTimeLeftOrigin: null };
  return { completion: frac() * 100, filepos: Math.floor(frac() * 4_000_000), printTime: Math.floor(elapsed()), printTimeLeft: Math.floor(S.total - elapsed()), printTimeLeftOrigin: 'genius' };
}
function layerNow() {
  const n = S.file.layers;
  const at = frac() * n;
  return { current: Math.min(n, Math.floor(at) + 1), within: Math.floor((at % 1) * 100), z: Math.min(S.file.maxZ, (Math.floor(at) + 1) * S.file.maxZ / n) };
}
function tempEntry() {
  const sample = { time: Math.floor(now()), tool0: { actual: +S.tool.actual.toFixed(2), target: S.tool.target } };
  // a printer with no heated bed has no bed in its temperatures at all
  if (!lacks('bed')) sample.bed = { actual: +S.bed.actual.toFixed(2), target: S.bed.target };
  return sample;
}
function current(withTemp) {
  const active = ['printing', 'paused', 'finishing'].includes(S.phase);
  return {
    state: stateFlags(), job: jobBlock(), progress: progressBlock(),
    currentZ: active ? layerNow().z : null, offsets: {}, resends: { count: 0, transmitted: 100, ratio: 0 },
    temps: withTemp && S.phase !== 'offline' ? [tempEntry()] : [], serverTime: now(), busyFiles: [], markings: [],
  };
}
function historyTemps() {
  const out = [];
  const heating = S.scene === 'heating';
  for (let i = 280; i > 0; i--) {
    const t = i / 280;
    const wob = Math.sin(i / 3) * 0.4;
    const sample = {
      time: Math.floor(now() - i * 2),
      tool0: { actual: heating ? S.tool.actual - t * 150 : S.tool.target > 0 ? S.tool.target + wob : 24 + t * 3, target: S.tool.target },
    };
    if (!lacks('bed')) sample.bed = { actual: heating ? S.bed.actual - t * 22 : S.bed.target > 0 ? S.bed.target + wob / 4 : 23 + t * 2, target: S.bed.target };
    out.push(sample);
  }
  return out;
}
function dashboardFull() {
  const active = ['printing', 'paused', 'finishing'].includes(S.phase);
  const l = active ? layerNow() : { current: 0, within: 0, z: 0 };
  return {
    totalLayers: String(active ? S.file.layers + 1 : 0), currentLayer: String(l.current), layerProgress: String(l.within),
    maxZ: String(active ? S.file.maxZ : 0), currentHeight: String(l.z), fanSpeed: String(S.fan), cpuPercent: '0', cpuTemp: '0',
    layerAnalysisError: '[False, False, False]', timeToNextChange: '-',
  };
}

// ---------------- sockets ----------------
const sockets = new Set();
const broadcast = obj => { const s = JSON.stringify(obj); for (const ws of sockets) if (ws.authed) ws.send(s); };
const plugin = (id, data) => broadcast({ plugin: { plugin: id, data } });
const event = (type, payload) => broadcast({ event: { type, payload } });

function step() {
  // temperatures drift to target
  for (const h of [S.tool, S.bed]) {
    const goal = S.phase === 'offline' ? 22 : (h.target > 0 ? h.target : 23);
    const rate = h === S.tool ? 2.4 : 0.5;
    const d = goal - h.actual;
    h.actual += Math.abs(d) < rate ? d + (Math.random() - 0.5) * 0.3 : Math.sign(d) * rate;
  }
  if (S.phase === 'printing') {
    S.started -= (S.speed - 1);           // run the job faster than real time
    if (frac() >= 1) finish();
  }
  broadcast({ current: current(true) });
  if (S.phase === 'printing' && !lacks('dashboard')) {
    const l = layerNow();
    plugin('dashboard', { currentLayer: String(l.current) });
    plugin('dashboard', { layerProgress: String(l.within), layerAnalysisError: '[False, False, False]' });
    plugin('dashboard', { currentHeight: String(l.z) });
    plugin('dashboard', { currentFeedrate: 80.0, avgFeedrate: 60.5, lastFeedChange: now() });
  }
}
setInterval(step, 1000);

function startPrint(f, folder) {
  S.file = f; S.folder = folder || null; S.total = f.est; S.started = now(); S.pausedAt = null; S.done = false;
  S.phase = 'printing'; S.tool.target = 220; S.bed.target = 55; S.scene = 'heating';
  event('PrintStarted', { name: f.name, path: f.name, origin: 'local', size: 1 });
  if (!lacks('dashboard')) plugin('dashboard', dashboardFull());
}
function finish() {
  S.phase = 'ready'; S.done = true; S.tool.target = 0; S.bed.target = 0;
  event('PrintDone', { name: S.file.name, path: S.file.name, origin: 'local', size: 1, time: S.total });
}
function scene(name, q) {
  S.scene = name; S.prompt = null; S.done = false; S.pausedAt = null; S.speed = +(q.speed ?? 1);
  S.without = new Set((q.without || '').split(',').filter(Boolean));
  const p = +(q.progress ?? 0.165);
  S.file = FILES[+(q.file ?? 0)] || FILES[0]; S.total = S.file.est; S.started = now() - S.total * p;
  if (q.total) { S.total = +q.total; S.started = now() - S.total * p; }   // a job of some other length
  S.display = q.display || null;                                            // or shown under another name
  S.tool = { actual: 219.9, target: 220 }; S.bed = { actual: 55, target: 55 };
  switch (name) {
    case 'printing': S.phase = 'printing'; break;
    case 'heating': S.phase = 'printing'; S.started = now() - 40; S.tool = { actual: 148, target: 220 }; S.bed = { actual: 51, target: 55 }; break;
    case 'paused': S.phase = 'paused'; S.pausedAt = now(); break;
    case 'prompt': S.phase = 'paused'; S.pausedAt = now(); S.prompt = { text: 'Filament ran out. Load a new spool, then continue.', choices: ['Continue', 'Cancel print'] }; break;
    case 'ready': S.phase = 'ready'; S.tool = { actual: 24.6, target: 0 }; S.bed = { actual: 23.1, target: 0 }; S.file = null; break;
    case 'done': S.phase = 'ready'; S.done = true; S.tool = { actual: 61, target: 0 }; S.bed = { actual: 38, target: 0 }; break;
    case 'offline': S.phase = 'offline'; S.tool = { actual: 22, target: 0 }; S.bed = { actual: 22, target: 0 }; break;
    case 'error': S.phase = 'error'; break;
  }
  if (q.tool) S.tool.actual = +q.tool;
  S.dead = name === 'dead';
  if (S.dead) { for (const ws of sockets) ws.terminate(); return; }
  broadcast({ history: { ...current(false), temps: historyTemps() } });
  if (!lacks('dashboard')) plugin('dashboard', dashboardFull());
  plugin('action_command_prompt', S.prompt ? { action: 'show', ...S.prompt } : { action: 'close' });
  if (name === 'done') event('PrintDone', { name: S.file.name, path: S.file.name, origin: 'local', size: 1, time: S.total });
}

// ---------------- http ----------------
const pending = new Map();   // app_token -> first poll time
const json = (res, code, obj) => { const b = obj === undefined ? '' : JSON.stringify(obj); res.writeHead(code, { 'Content-Type': 'application/json' }); res.end(b); };
const none = res => { res.writeHead(204); res.end(); };
const body = req => new Promise(r => { let s = ''; req.on('data', c => s += c); req.on('end', () => { try { r(JSON.parse(s || '{}')); } catch { r({}); } }); });

const handler = async (req, res) => {
  const u = new URL(req.url, 'http://x');
  const p = decodeURIComponent(u.pathname);
  const authed = req.headers['x-api-key'] === KEY;
  if (!p.startsWith('/webcam') && !p.includes('thumbnail') && p !== '/cm') console.log(req.method, req.url, authed ? '' : '(no key)');

  // mock controls
  if (p === '/mock/set') { scene(u.searchParams.get('scene') || 'printing', Object.fromEntries(u.searchParams)); return json(res, 200, { ok: true, phase: S.phase }); }

  // the smart plug: Tasmota's web command page. A separate device in real life, so it
  // answers without a key and goes on answering when the Pi is off.
  if (p === '/cm') {
    const c = (u.searchParams.get('cmnd') || '').trim().toLowerCase();
    if (c === 'power on') S.plug = true;
    else if (c === 'power off') S.plug = false;
    else if (c !== 'power') return json(res, 200, { Command: 'Unknown' });
    if (c !== 'power') console.log('  plug switched', S.plug ? 'ON' : 'OFF');
    return json(res, 200, { POWER: S.plug ? 'ON' : 'OFF' });
  }
  if (S.dead) return req.socket.destroy();

  // webcam: camera-streamer's multipart format
  if (p.startsWith('/webcam')) {
    if (lacks('camera') || lacks('deadcamera')) return json(res, 404, {});
    if (u.searchParams.get('action') === 'snapshot') { res.writeHead(200, { 'Content-Type': 'image/jpeg' }); return res.end(fs.readFileSync(path.join(ASSETS, 'cam00.jpg'))); }
    const B = '123456789000000000000987654321';
    res.writeHead(200, { 'Content-Type': `multipart/x-mixed-replace;boundary=${B}` });
    let n = 0;
    const send = () => {
      const img = fs.readFileSync(path.join(ASSETS, `cam${String(S.phase === 'printing' ? n % 16 : 4).padStart(2, '0')}.jpg`));
      res.write(`--${B}\r\nContent-Type: image/jpeg\r\nContent-Length: ${img.length}\r\n\r\n`); res.write(img); res.write('\r\n'); n++;
    };
    send(); const t = setInterval(send, 1000 / 12);
    req.on('close', () => clearInterval(t));
    return;
  }

  // appkeys
  if (p.startsWith('/plugin/appkeys/') && lacks('appkeys')) return json(res, 404, {});
  if (p === '/plugin/appkeys/probe') return none(res);
  if (p === '/plugin/appkeys/request' && req.method === 'POST') { const b = await body(req); const t = 'tok' + Date.now(); pending.set(t, Date.now()); console.log('  appkey requested by', b.app); return json(res, 201, { app_token: t, auth_dialog: `http://x/plugin/appkeys/auth/${t}` }); }
  if (p.startsWith('/plugin/appkeys/request/')) { const t = p.split('/').pop(); if (!pending.has(t)) return json(res, 404, {}); if (Date.now() - pending.get(t) < 6000) return json(res, 202, { message: 'Awaiting decision' }); pending.delete(t); return json(res, 200, { api_key: KEY }); }

  // everything else needs the key; a bad key on a POST is a CSRF 400, like the real thing
  if (!authed) return json(res, req.method === 'GET' ? 403 : 400, { error: req.method === 'GET' ? 'Forbidden' : 'CSRF validation failed' });

  if (p.startsWith('/plugin/prusaslicerthumbnails/thumbnail/')) {
    const name = p.split('/').pop().replace(/\.png$/, '') + '.gcode';
    const f = FILES.find(f => f.name === name) || (name.startsWith('Drawer') ? { thumb: 'bracket' } : null);
    if (!f || !f.thumb) return json(res, 404, {});
    res.writeHead(200, { 'Content-Type': 'image/png' }); return res.end(fs.readFileSync(path.join(ASSETS, f.thumb + '.png')));
  }

  if (p.startsWith('/downloads/files/local/')) {
    const e = find(p.slice('/downloads/files/local/'.length));
    if (!e) return json(res, 404, { error: 'not found' });
    const text = gcodeFor(e.name);
    res.writeHead(200, { 'Content-Type': 'application/octet-stream', 'Content-Length': Buffer.byteLength(text) });
    return res.end(text);
  }

  if (p === '/api/login') return json(res, 200, { name: 'maker', session: 'sess' + Date.now(), active: true, admin: true, user: true, groups: ['admins', 'users'] });
  if (p === '/api/version') return json(res, 200, { server: '1.11.7', api: '0.1', text: 'OctoPrint 1.11.7' });
  if (p === '/api/settings') return json(res, 200, {
    appearance: { name: 'Ender-3 V3 SE', color: 'default' },
    temperature: { profiles: lacks('presets') ? [] : [{ name: 'PLA', extruder: 210, bed: 60, chamber: null }, { name: 'PETG', extruder: 240, bed: 80, chamber: null }, { name: 'ABS', extruder: 250, bed: 100, chamber: null }] },
    webcam: lacks('camera') ? { webcamEnabled: false } : { webcamEnabled: true, streamUrl: '/webcam/?action=stream', flipH: false, flipV: false, rotate90: false },
    plugins: {
      ...(lacks('camera') ? {} : { classicwebcam: { stream: '/webcam/?action=stream' } }),
      ...(lacks('lights') ? {} : { LightControls: { light_controls: [{ name: 'Bed light', pin: '18', ispwm: true, frequency: '250', inverted: false }] } }),
    },
  });

  // system commands, as OctoPi sets them up. The mock can't actually vanish from the
  // network the way a Pi does, so after a shutdown it just carries on answering.
  if (p === '/api/system/commands/core' && req.method === 'GET') return json(res, 200, (lacks('shutdown') ? [] : ['shutdown', 'reboot', 'restart']).map(action => (
    { action, name: action, source: 'core', confirm: `About to ${action}.`, resource: `http://localhost/api/system/commands/core/${action}` })));
  if (p === '/api/system/commands/core/shutdown' && req.method === 'POST') { console.log('  SHUTDOWN asked for'); return none(res); }

  if (p === '/api/files/local' && req.method === 'GET') { await new Promise(r => setTimeout(r, 1500)); return json(res, 200, listing()); }
  if (p.startsWith('/api/files/local/')) {
    const fp = p.slice('/api/files/local/'.length);
    const e = find(fp);
    if (!e) return json(res, 404, { error: 'not found' });
    if (req.method === 'GET') return json(res, 200, e);
    const b = await body(req);
    if (S.phase !== 'ready') return json(res, 409, { error: 'Printer is already printing, cannot select a new file' });
    const folder = fp.includes('/') ? fp.split('/')[0] : null;
    const f = FILES.find(f => f.name === e.name) || { name: e.name, thumb: null, est: e.gcodeAnalysis.estimatedPrintTime, fil: 6000, layers: 120, maxZ: 24, age: 0 };
    if (b.print) startPrint(f, folder); else { S.file = f; S.folder = folder; S.done = false; }
    return none(res);
  }

  if (p === '/api/job' && req.method === 'POST') {
    const b = await body(req);
    if (['offline', 'error'].includes(S.phase)) return json(res, 409, { error: 'Printer is not operational' });
    if (b.command === 'pause') {
      if (!['printing', 'paused'].includes(S.phase)) return json(res, 409, { error: 'Printer is neither printing nor paused' });
      const want = b.action === 'toggle' ? (S.phase === 'printing' ? 'pause' : 'resume') : b.action;
      if (want === 'pause' && S.phase === 'printing') { S.phase = 'paused'; S.pausedAt = now(); event('PrintPaused', {}); }
      if (want === 'resume' && S.phase === 'paused') { S.started += now() - S.pausedAt; S.pausedAt = null; S.phase = 'printing'; event('PrintResumed', {}); }
    } else if (b.command === 'cancel') {
      if (!['printing', 'paused'].includes(S.phase)) return json(res, 409, { error: 'Printer is neither printing nor paused' });
      const t = elapsed(); S.phase = 'ready'; S.pausedAt = null; S.done = false; S.tool.target = 0; S.bed.target = 0; S.started = now();
      event('PrintCancelled', { name: S.file.name, path: S.file.name, origin: 'local', time: t });
      event('PrintFailed', { name: S.file.name, path: S.file.name, origin: 'local', time: t, reason: 'cancelled' });
    } else if (b.command === 'start') {
      if (S.phase !== 'ready' || !S.file) return json(res, 409, { error: 'busy' });
      startPrint(S.file, S.folder);
    }
    return none(res);
  }

  if (p === '/api/connection' && req.method === 'POST') {
    const b = await body(req);
    if (b.command === 'connect' && S.phase === 'offline') setTimeout(() => { S.phase = 'ready'; S.file = null; }, 1500);
    if (b.command === 'disconnect') S.phase = 'offline';
    return none(res);
  }
  if (p.startsWith('/api/printer/') && req.method === 'POST') {
    const b = await body(req);
    if (['offline', 'error'].includes(S.phase)) return json(res, 409, { error: 'Printer is not operational' });
    console.log('  ', JSON.stringify(b));
    if (p.endsWith('/tool') && b.command === 'target') S.tool.target = b.targets.tool0;
    if (p.endsWith('/bed') && b.command === 'target') S.bed.target = b.target;
    if (p.endsWith('/printhead') && ['jog', 'home'].includes(b.command) && S.phase === 'printing') return json(res, 409, { error: 'Printer is currently printing' });
    if (p.endsWith('/command')) for (const c of b.commands || []) { const m = /^M106 S(\d+)/.exec(c); if (m) { S.fan = Math.round(+m[1] * 100 / 255); if (!lacks('dashboard')) plugin('dashboard', { fanSpeed: String(S.fan) }); } }
    return none(res);
  }

  if (p === '/api/plugin/action_command_prompt') {
    if (req.method === 'GET') return json(res, 200, S.prompt ? S.prompt : {});
    const b = await body(req);
    console.log('  prompt answered:', b.choice);
    S.prompt = null; plugin('action_command_prompt', { action: 'close' });
    return none(res);
  }
  if (p === '/api/plugin/LightControls' && req.method === 'POST') {
    const b = await body(req);
    if (b.command === 'setLightValue' && Number.isInteger(b.pin)) { S.lights[b.pin] = b.percentage; plugin('LightControls', { pin: b.pin, value: b.percentage }); }
    if (b.command === 'getLightValues') for (const pin of Object.keys(S.lights)) plugin('LightControls', { pin: +pin, value: S.lights[pin] });
    return none(res);
  }
  json(res, 404, { error: 'no such route ' + p });
};

// Plain http, plus https with a self-signed certificate like the one OctoPi ships,
// so the display's certificate pinning has something real to meet. The certificate
// is made on first run with openssl; without openssl the mock is http only.
function selfSigned() {
  const key = path.join(__dirname, 'key.pem');
  const cert = path.join(__dirname, 'cert.pem');
  if (!fs.existsSync(key) || !fs.existsSync(cert)) {
    try {
      require('child_process').execFileSync('openssl', ['req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-keyout', key, '-out', cert,
        '-days', '3650', '-subj', '/CN=OctoPrint', '-addext', 'subjectAltName=DNS:OctoPrint'], { stdio: 'ignore' });
    } catch { return null; }
  }
  return { key: fs.readFileSync(key), cert: fs.readFileSync(cert) };
}
// a request it can't make sense of is that request's problem, not a reason to stop
const answer = (req, res) => handler(req, res).catch(() => { if (!res.headersSent) res.writeHead(400); res.end(); });
const server = http.createServer(answer);
const servers = [server];
const tls = selfSigned();
if (tls) servers.push(require('https').createServer(tls, answer));

const wss = new WebSocketServer({ noServer: true });
for (const srv of servers) srv.on('upgrade', (req, sock, head) => {
  if (S.dead || !req.url.startsWith('/sockjs/websocket')) return sock.destroy();
  wss.handleUpgrade(req, sock, head, ws => {
    sockets.add(ws); ws.authed = false;
    ws.send(JSON.stringify({ connected: { version: '1.11.7', display_version: '1.11.7', online: true, permissions: [] } }));
    ws.on('message', raw => {
      let m; try { m = JSON.parse(raw); } catch { return; }
      if (m.subscribe) console.log('  socket subscribe', JSON.stringify(m.subscribe));
      if (m.auth) {
        const ok = /^maker:sess\d+$/.test(m.auth);
        console.log('  socket auth', ok ? 'ok' : 'REJECTED ' + m.auth);
        if (!ok) return ws.send(JSON.stringify({ reauthRequired: { reason: 'stale' } }));
        ws.authed = true;
        ws.send(JSON.stringify({ history: { ...current(false), temps: historyTemps() } }));
        // Dashboard sends its full state whenever any client authenticates
        if (!lacks('dashboard')) ws.send(JSON.stringify({ plugin: { plugin: 'dashboard', data: dashboardFull() } }));
      }
    });
    ws.on('close', () => sockets.delete(ws));
  });
});

server.listen(PORT, HOST, () => console.log(`mock OctoPrint on http :${PORT}  key ${KEY}`));
if (servers[1]) servers[1].listen(PORT + 443, HOST, () => console.log(`              and https :${PORT + 443}`));
