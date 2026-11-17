/*
 * BitChord Audio for LG webOS — the app's companion service.
 *
 * Runs in the TV's Node runtime (webOS 6+: Node 12 or newer), started by the
 * app over the Luna bus and kept alive by its subscription. It does three
 * jobs on one small HTTP server:
 *
 *  1. Stands in for the browser extension the web player needs: requests to
 *     YouTube, googlevideo, lyrics and addon hosts are made from here, with
 *     the headers the extension's rules give them (Origin/Referer of YouTube,
 *     none for VISIONOS media) and an open CORS answer for the page. The page
 *     reaches it at http://127.0.0.1:PORT, only from the TV itself.
 *  2. Speaks the extension's bridge protocol (ping, yt.fetch, addon.fetch,
 *     lyrics.fetch …) at /bridge, so the player finds "an extension".
 *  3. Lets the BitChord Audio phone app find and drive the TV on the home
 *     network: discovery over UDP, pairing with a code shown on the TV, then
 *     commands (play these songs, pause, seek, these lyrics, this video) passed
 *     to the player over server-sent events, and its state read back.
 *
 * Written for old Node: CommonJS, no dependencies, no optional chaining.
 */
'use strict';

var http = require('http');
var https = require('https');
var dgram = require('dgram');
var fs = require('fs');
var path = require('path');
var urlLib = require('url');
var crypto = require('crypto');
var os = require('os');

var SERVICE_ID = 'com.bitchord.tv.service';
var PORT = 47615;
var DISCOVERY_PORT = 47616;
var VERSION = '1.0.0';

// ---------------------------------------------------------------- storage ----

var dataDir = (function () {
  var candidates = [path.join(__dirname, 'data'), path.join(os.tmpdir(), 'bitchord-tv')];
  for (var i = 0; i < candidates.length; i++) {
    try {
      fs.mkdirSync(candidates[i], { recursive: true });
      fs.accessSync(candidates[i], fs.constants.W_OK);
      return candidates[i];
    } catch (e) { /* next */ }
  }
  return os.tmpdir();
})();

function readJson(name, fallback) {
  try { return JSON.parse(fs.readFileSync(path.join(dataDir, name), 'utf8')); } catch (e) { return fallback; }
}
function writeJson(name, value) {
  try { fs.writeFileSync(path.join(dataDir, name), JSON.stringify(value)); } catch (e) { /* best effort */ }
}

/** Phones that paired: token → name. Kept, so a phone pairs once. */
var paired = readJson('paired.json', {});
/** The code shown on the TV for pairing; a new one each start. */
var pin = String(100000 + crypto.randomBytes(3).readUIntBE(0, 3) % 900000);

// ------------------------------------------------------------------- proxy ---

/** Headers a request needs to look like YouTube's own (web/extension/lib/rules.ts). */
function upstreamHeaders(target, incoming) {
  var host = target.hostname;
  var out = {};
  var skip = { host: 1, origin: 1, referer: 1, 'x-origin': 1, connection: 1, 'accept-encoding': 1, cookie: 1, 'content-length': 1 };
  Object.keys(incoming || {}).forEach(function (k) {
    if (!skip[k.toLowerCase()] && k.toLowerCase().indexOf('sec-') !== 0) out[k] = incoming[k];
  });
  // Plain bytes back, so nothing has to be decoded on the way through.
  out['accept-encoding'] = 'identity';
  if (!out['user-agent'] && !out['User-Agent']) {
    out['user-agent'] = 'Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0 Safari/537.36';
  }
  function as(origin) { out.origin = origin; out.referer = origin + '/'; out['x-origin'] = origin; }
  if (host === 'music.youtube.com') as('https://music.youtube.com');
  else if (host === 'www.youtube.com' && target.pathname.indexOf('/youtubei/') === 0) as('https://www.youtube.com');
  else if (host === 's.youtube.com') { out.origin = 'https://music.youtube.com'; out.referer = 'https://music.youtube.com/'; }
  else if (/(^|\.)googlevideo\.com$/.test(host)) {
    // VISIONOS media URLs go without Origin/Referer, as InnerTubeX sends them.
    if (!/[?&]c=VISIONOS(&|$)/.test(target.search || '')) { out.origin = 'https://www.youtube.com'; out.referer = 'https://www.youtube.com/'; }
  }
  return out;
}

/** One request upstream, following redirects; calls back with the response stream. */
function upstream(method, targetUrl, headers, body, done, hops) {
  hops = hops || 0;
  var target;
  try { target = urlLib.parse(targetUrl); } catch (e) { return done(e); }
  if (target.protocol !== 'https:' && target.protocol !== 'http:') return done(new Error('bad scheme'));
  var lib = target.protocol === 'https:' ? https : http;
  var req = lib.request({
    method: method,
    hostname: target.hostname,
    port: target.port || (target.protocol === 'https:' ? 443 : 80),
    path: target.path,
    headers: upstreamHeaders(target, headers),
    timeout: 30000,
  }, function (res) {
    var location = res.headers.location;
    if (location && res.statusCode >= 300 && res.statusCode < 400 && hops < 6) {
      res.resume();
      var next = urlLib.resolve(targetUrl, location);
      var nextMethod = res.statusCode === 303 ? 'GET' : method;
      return upstream(nextMethod, next, headers, nextMethod === 'GET' ? null : body, done, hops + 1);
    }
    res.finalUrl = targetUrl;
    done(null, res);
  });
  req.on('timeout', function () { req.destroy(new Error('timeout')); });
  req.on('error', function (e) { done(e); });
  if (body && body.length) {
    req.setHeader('content-length', body.length);
    req.end(body);
  } else req.end();
}

function cors(res) {
  res.setHeader('Access-Control-Allow-Origin', '*');
  res.setHeader('Access-Control-Allow-Headers', '*');
  res.setHeader('Access-Control-Allow-Methods', 'GET, POST, HEAD, OPTIONS');
  res.setHeader('Access-Control-Expose-Headers', '*');
}

/** GET|POST|HEAD /p?u=<url>: the page's request, made from here and streamed back. */
function proxy(req, res, target) {
  readBody(req, function (body) {
    upstream(req.method, target, req.headers, body, function (err, up) {
      if (err) {
        res.writeHead(502, { 'content-type': 'text/plain' });
        return res.end(String(err.message || err));
      }
      var headers = {};
      Object.keys(up.headers).forEach(function (k) {
        var l = k.toLowerCase();
        if (l.indexOf('access-control-') === 0 || l === 'set-cookie' || l === 'connection' || l === 'transfer-encoding') return;
        headers[k] = up.headers[k];
      });
      headers['x-final-url'] = up.finalUrl;
      res.writeHead(up.statusCode, headers);
      if (req.method === 'HEAD') { up.resume(); return res.end(); }
      up.pipe(res);
      req.on('close', function () { up.destroy(); });
    });
  });
}

function readBody(req, done) {
  if (req.method === 'GET' || req.method === 'HEAD') return done(null);
  var chunks = [];
  req.on('data', function (c) { chunks.push(c); });
  req.on('end', function () { done(Buffer.concat(chunks)); });
}

/** The bridge's HttpRequest → HttpResponse, buffered (web/shared/src/bridge/protocol.ts). */
function bridgeFetch(request, done) {
  var body = null;
  if (request.body !== undefined && request.body !== null) {
    body = request.bodyEncoding === 'base64' ? Buffer.from(request.body, 'base64') : Buffer.from(String(request.body), 'utf8');
  }
  upstream(request.method || 'GET', request.url, request.headers || {}, body, function (err, up) {
    if (err) return done(err);
    var chunks = [];
    up.on('data', function (c) { chunks.push(c); });
    up.on('error', function (e) { done(e); });
    up.on('end', function () {
      var buf = Buffer.concat(chunks);
      var type = String(up.headers['content-type'] || '');
      var textual = /json|text|javascript|xml/.test(type);
      var headers = {};
      Object.keys(up.headers).forEach(function (k) { headers[k] = String(up.headers[k]); });
      done(null, {
        status: up.statusCode,
        headers: headers,
        url: up.finalUrl,
        bodyEncoding: textual ? 'text' : 'base64',
        body: textual ? buf.toString('utf8') : buf.toString('base64'),
      });
    });
  });
}

// ------------------------------------------------------------------ bridge ---

var SIGNED_OUT = { browserSignedIn: false, connected: false, signedIn: false, accounts: [], active: null, problem: null };

function bridge(envelope, done) {
  function ok(result) {
    done({ channel: 'bitchord-bridge', protocol: 1, direction: 'to-page', id: envelope.id, ok: true, result: result });
  }
  function fail(code, message) {
    done({ channel: 'bitchord-bridge', protocol: 1, direction: 'to-page', id: envelope.id, ok: false, error: { code: code, message: message } });
  }
  var p = envelope.params || {};
  switch (envelope.method) {
    case 'ping':
      return ok({ version: VERSION, protocol: 1, browser: 'chromium', capabilities: ['yt', 'addon', 'lyrics'] });
    case 'yt.fetch':
      if (p.auth) return fail('unauthenticated', 'Not signed in on the TV.');
      // falls through
    case 'addon.fetch':
    case 'lyrics.fetch':
      return bridgeFetch(p, function (err, r) { return err ? fail('network', String(err.message || err)) : ok(r); });
    case 'yt.authState':
    case 'yt.selectChannel':
    case 'yt.setConnected':
      return ok(SIGNED_OUT);
    case 'lyrics.permissions':
      return ok({ granted: p.origins || [] });
    case 'lyrics.requestPermissions':
      return ok({});
    default:
      return fail('unsupported', envelope.method + ' is not available on the TV');
  }
}

// ------------------------------------------------------- phone ↔ TV player ---

/** What the TV player last reported (now playing, position, queue, lyrics). */
var tvState = { playing: false, nowPlaying: null, positionSec: 0, durationSec: 0, queue: [], index: -1, at: 0 };
/** The player's open event streams; commands from phones are written to them. */
var playerStreams = [];
/** Phones listening for state changes. */
var phoneStreams = [];
var keepAlive = null;

function sendEvent(stream, name, data) {
  try { stream.write('event: ' + name + '\ndata: ' + JSON.stringify(data) + '\n\n'); } catch (e) { /* closed */ }
}

function toPlayer(command) {
  playerStreams.forEach(function (s) { sendEvent(s, 'command', command); });
  return playerStreams.length;
}

function openStream(req, res, list) {
  res.writeHead(200, {
    'content-type': 'text/event-stream',
    'cache-control': 'no-cache',
    connection: 'keep-alive',
    'access-control-allow-origin': '*',
  });
  res.write(': open\n\n');
  list.push(res);
  var ping = setInterval(function () { try { res.write(': ping\n\n'); } catch (e) { /* closed */ } }, 15000);
  req.on('close', function () {
    clearInterval(ping);
    var i = list.indexOf(res);
    if (i >= 0) list.splice(i, 1);
  });
}

function isLocal(req) {
  var a = req.socket.remoteAddress || '';
  return a === '127.0.0.1' || a === '::1' || a === '::ffff:127.0.0.1';
}

function tvName() {
  var info = readJson('name.json', null);
  return (info && info.name) || 'LG TV (BitChord Audio)';
}

function json(res, status, value) {
  cors(res);
  res.writeHead(status, { 'content-type': 'application/json' });
  res.end(JSON.stringify(value));
}

function readJsonBody(req, done) {
  readBody(req, function (buf) {
    try { done(buf && buf.length ? JSON.parse(buf.toString('utf8')) : {}); } catch (e) { done(null); }
  });
}

function authorised(req) {
  var token = req.headers['x-bitchord-token'];
  return !!(token && paired[token]);
}

// ------------------------------------------------------------------ server ---

var server = http.createServer(function (req, res) {
  var u = urlLib.parse(req.url, true);
  if (req.method === 'OPTIONS') { cors(res); res.writeHead(204); return res.end(); }

  // ---- the TV's own page only ----
  if (u.pathname === '/p' || u.pathname === '/bridge' || u.pathname.indexOf('/tv/') === 0) {
    if (!isLocal(req)) return json(res, 403, { error: 'local only' });
    cors(res);
    if (u.pathname === '/p') return proxy(req, res, String(u.query.u || ''));
    if (u.pathname === '/bridge') {
      return readJsonBody(req, function (envelope) {
        if (!envelope || typeof envelope.id !== 'string') return json(res, 400, { error: 'bad envelope' });
        bridge(envelope, function (answer) { json(res, 200, answer); });
      });
    }
    if (u.pathname === '/tv/commands') return openStream(req, res, playerStreams);
    if (u.pathname === '/tv/state' && req.method === 'POST') {
      return readJsonBody(req, function (state) {
        if (state) {
          tvState = state;
          tvState.at = Date.now();
          phoneStreams.forEach(function (s) { sendEvent(s, 'state', tvState); });
        }
        json(res, 200, { ok: true });
      });
    }
    if (u.pathname === '/tv/info') return json(res, 200, { pin: pin, name: tvName(), port: PORT, phones: Object.keys(paired).length, addresses: addresses() });
    if (u.pathname === '/tv/name' && req.method === 'POST') {
      return readJsonBody(req, function (b) { if (b && b.name) writeJson('name.json', { name: String(b.name).slice(0, 60) }); json(res, 200, { ok: true }); });
    }
    return json(res, 404, { error: 'not found' });
  }

  // ---- phones on the home network ----
  if (u.pathname === '/remote/info') return json(res, 200, { app: 'bitchord-tv', version: VERSION, name: tvName() });
  if (u.pathname === '/remote/pair' && req.method === 'POST') {
    return readJsonBody(req, function (b) {
      if (!b || String(b.pin) !== pin) return json(res, 403, { error: 'wrong code' });
      var token = crypto.randomBytes(16).toString('hex');
      paired[token] = { name: String(b.phone || 'Phone').slice(0, 60), at: Date.now() };
      writeJson('paired.json', paired);
      toPlayer({ type: 'toast', text: 'Paired with ' + paired[token].name });
      json(res, 200, { token: token, name: tvName() });
    });
  }
  if (u.pathname.indexOf('/remote/') === 0) {
    if (!authorised(req)) return json(res, 401, { error: 'not paired' });
    if (u.pathname === '/remote/state') return json(res, 200, tvState);
    if (u.pathname === '/remote/events') {
      openStream(req, res, phoneStreams);
      if (tvState) sendEvent(res, 'state', tvState);
      return;
    }
    if (u.pathname === '/remote/command' && req.method === 'POST') {
      return readJsonBody(req, function (command) {
        if (!command || typeof command.type !== 'string') return json(res, 400, { error: 'bad command' });
        var players = toPlayer(command);
        json(res, players ? 200 : 409, players ? { ok: true } : { error: 'the BitChord app is not open on the TV' });
      });
    }
  }
  json(res, 404, { error: 'not found' });
});

function addresses() {
  var out = [];
  var ifaces = os.networkInterfaces();
  Object.keys(ifaces).forEach(function (name) {
    (ifaces[name] || []).forEach(function (a) { if (a.family === 'IPv4' && !a.internal) out.push(a.address); });
  });
  return out;
}

var started = false;
function start() {
  if (started) return;
  started = true;
  server.listen(PORT, '0.0.0.0');
  server.on('error', function (e) { console.error('bitchord: server', e); });
  // Discovery: a phone broadcasts "BITCHORD?"; every TV answers with where it is.
  var udp = dgram.createSocket({ type: 'udp4', reuseAddr: true });
  udp.on('message', function (msg, rinfo) {
    if (String(msg).trim() !== 'BITCHORD?') return;
    var answer = Buffer.from(JSON.stringify({ app: 'bitchord-tv', name: tvName(), port: PORT, version: VERSION }));
    udp.send(answer, 0, answer.length, rinfo.port, rinfo.address);
  });
  udp.on('error', function (e) { console.error('bitchord: discovery', e); });
  try { udp.bind(DISCOVERY_PORT); } catch (e) { /* no discovery; pairing by address still works */ }
}

// -------------------------------------------------------------- luna bus ---

var Service = null;
try { Service = require('webos-service'); } catch (e) { Service = null; }

if (Service) {
  var service = new Service(SERVICE_ID);
  // The app subscribes on start: that call is what keeps this process alive,
  // and its answer tells the page where the server is.
  service.register('start', function (message) {
    start();
    if (!keepAlive) {
      service.activityManager.create('bitchord-keepalive', function (activity) { keepAlive = activity; });
    }
    message.respond({ returnValue: true, port: PORT, pin: pin, name: tvName(), addresses: addresses() });
  }, function () {
    // The app went away: let the process idle out.
    if (keepAlive) {
      service.activityManager.complete(keepAlive, function () {});
      keepAlive = null;
    }
  });
} else {
  // Run outside the TV (node service.js) for development.
  start();
  console.log('bitchord tv service on :' + PORT + ', pairing code ' + pin);
}
