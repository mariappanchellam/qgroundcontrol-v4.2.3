'use strict';

// Dhaksha Live desktop: 25 boxes, each showing one drone.
// A box plays a stream from the Dhaksha video server (MediaMTX) as HLS or WebRTC, or shows a Livepush player page.
// Until a box's video is actually playing it shows the Dhaksha logo. The addresses are kept between runs.

const STREAMS = 25;
const STORAGE_KEY = 'dhakshaLive.urls';
const MODE_KEY = 'dhakshaLive.mode';
const SERVER_KEY = 'dhakshaLive.server';
const LIVEPUSH_HOST = 'player.livepush.io';
const HLS_PORT = '8888';
const WEBRTC_PORT = '8889';
const RETRY_MS = 3000;
const CONNECT_TIMEOUT_MS = 10000;

/**
 * Reads a box's address. Returns null for an empty box, { error } when it cannot be played, or one of:
 *   { kind: 'page', url }              Livepush player or another web page, shown as it is
 *   { kind: 'hls', url }               an .m3u8 playlist
 *   { kind: 'webrtc', url }            a WHEP address
 *   { kind: 'server', host, path }     a stream on the Dhaksha server; plays as HLS or WebRTC (see resolve)
 * rtsp:// and rtmp:// addresses are read as the same stream on the server, because a viewer cannot play them directly.
 */
function parseAddress(input) {
  let text = (input || '').trim();
  if (!text) {
    return null;
  }
  if (!text.includes('://')) {
    text = (text.startsWith(LIVEPUSH_HOST) ? 'https://' : 'http://') + text;
  }
  // Older Chromium (the 32-bit Linux build) does not split rtsp:// or rtmp:// addresses into host and path,
  // so the address is read as http:// and its own scheme is kept aside
  const match = text.match(/^([a-z][a-z0-9+.-]*):\/\//i);
  if (!match) {
    return { error: 'Not a valid address' };
  }
  const scheme = match[1].toLowerCase() + ':';
  let url;
  try {
    url = new URL('http' + text.slice(text.indexOf('://')));
  } catch (e) {
    return { error: 'Not a valid address' };
  }
  const path = url.pathname.replace(/\/+$/, '');
  if (url.hostname === LIVEPUSH_HOST) {
    url.protocol = 'https:';
    return path ? { kind: 'page', url: url.toString() } : { error: 'Livepush link has no stream id' };
  }
  if (scheme === 'https:') {
    url.protocol = 'https:';
  }
  switch (scheme) {
  case 'rtsp:':
  case 'rtsps:':
  case 'rtmp:':
  case 'rtmps:':
    return path ? { kind: 'server', host: url.hostname, path } : { error: 'Address has no stream name' };
  case 'http:':
  case 'https:':
    if (path.endsWith('.m3u8')) {
      return { kind: 'hls', url: url.toString() };
    }
    if (path.endsWith('/whep')) {
      return { kind: 'webrtc', url: url.toString() };
    }
    if (url.port === HLS_PORT || url.port === WEBRTC_PORT) {
      return path ? { kind: 'server', host: url.hostname, path, secure: url.protocol === 'https:' }
        : { error: 'Address has no stream name' };
    }
    return { kind: 'page', url: url.toString() };
  default:
    return { error: scheme.replace(':', '').toUpperCase() + ' links cannot be played here' };
  }
}

/** Turns a server stream into the HLS playlist or WebRTC (WHEP) address for `mode`. */
function resolve(source, mode) {
  if (!source || source.kind !== 'server') {
    return source;
  }
  const origin = (source.secure ? 'https://' : 'http://') + source.host;
  return mode === 'webrtc'
    ? { kind: 'webrtc', url: origin + ':' + WEBRTC_PORT + source.path + '/whep' }
    : { kind: 'hls', url: origin + ':' + HLS_PORT + source.path + '/index.m3u8' };
}

function load(key, fallback) {
  try {
    const value = localStorage.getItem(key);
    return value === null ? fallback : value;
  } catch (e) {
    return fallback;
  }
}

function store(key, value) {
  try {
    localStorage.setItem(key, value);
  } catch (e) {
    // Not being able to remember the settings must not stop playback
  }
}

function loadUrls() {
  try {
    const saved = JSON.parse(load(STORAGE_KEY, '[]'));
    return Array.from({ length: STREAMS }, (_, i) => (typeof saved[i] === 'string' ? saved[i] : ''));
  } catch (e) {
    return new Array(STREAMS).fill('');
  }
}

const DRONE_SVG =
  '<svg viewBox="0 0 120 50" xmlns="http://www.w3.org/2000/svg">' +
  '<rect x="20" y="19" width="80" height="5" rx="2" fill="#dee21b"/>' +
  '<rect x="46" y="14" width="28" height="15" rx="5" fill="#dee21b"/>' +
  '<rect x="53" y="29" width="14" height="7" rx="2" fill="#2b2f38"/>' +
  '<circle cx="60" cy="33" r="2.6" fill="#6cf"/>' +
  '<rect x="18" y="11" width="4" height="10" fill="#c9d2dc"/>' +
  '<rect x="98" y="11" width="4" height="10" fill="#c9d2dc"/>' +
  '<ellipse class="rotor" cx="20" cy="10" rx="19" ry="3" fill="#dfe6ee" opacity=".85"/>' +
  '<ellipse class="rotor" cx="100" cy="10" rx="19" ry="3" fill="#dfe6ee" opacity=".85"/>' +
  '<rect x="41" y="29" width="3" height="12" fill="#c9d2dc"/>' +
  '<rect x="76" y="29" width="3" height="12" fill="#c9d2dc"/>' +
  '<rect x="34" y="41" width="16" height="2.5" rx="1" fill="#c9d2dc"/>' +
  '<rect x="70" y="41" width="16" height="2.5" rx="1" fill="#c9d2dc"/>' +
  '</svg>';

/** Sends a quadcopter across `area` once; the element removes itself when it has flown out. */
function flyDrone(area) {
  if (area.querySelector('.drone')) {
    return;
  }
  const drone = document.createElement('div');
  drone.className = 'drone';
  drone.innerHTML = DRONE_SVG;
  drone.addEventListener('animationend', (e) => {
    if (e.target === drone) {
      drone.remove();
    }
  });
  area.appendChild(drone);
}

/** Waits until the browser has listed its network addresses (at most 2 s), so the server can reach it straight away. */
function waitForCandidates(pc) {
  if (pc.iceGatheringState === 'complete') {
    return Promise.resolve();
  }
  return new Promise((done) => {
    const finish = () => {
      clearTimeout(timer);
      pc.removeEventListener('icegatheringstatechange', check);
      done();
    };
    const check = () => {
      if (pc.iceGatheringState === 'complete') {
        finish();
      }
    };
    const timer = setTimeout(finish, 2000);
    pc.addEventListener('icegatheringstatechange', check);
  });
}

let globalMode = load(MODE_KEY, 'hls') === 'webrtc' ? 'webrtc' : 'hls';

class Tile {
  constructor(index, grid) {
    this.source = null;
    this.modeOverride = null;
    this.player = null;
    this.retryTimer = 0;
    this.session = 0;

    this.el = document.createElement('div');
    this.el.className = 'tile';

    const logo = document.createElement('div');
    logo.className = 'logo';
    const img = document.createElement('img');
    img.src = 'logo.svg';
    img.alt = 'Dhaksha Drones';
    img.addEventListener('click', (e) => {
      e.stopPropagation();
      flyDrone(logo);
    });
    const sub = document.createElement('div');
    sub.className = 'sub';
    sub.textContent = 'Drone ' + (index + 1);
    this.note = document.createElement('div');
    this.note.className = 'note';
    logo.append(img, sub, this.note);

    const badge = document.createElement('div');
    badge.className = 'badge';
    badge.textContent = String(index + 1);

    const overlay = document.createElement('div');
    overlay.className = 'overlay';
    this.urlText = document.createElement('span');
    this.urlText.className = 'url';
    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = '↻';
    reload.title = 'Reload';
    reload.addEventListener('click', () => this.start());
    this.modeButton = document.createElement('button');
    this.modeButton.type = 'button';
    this.modeButton.title = 'Switch this box between HLS and WebRTC';
    this.modeButton.addEventListener('click', () => {
      this.modeOverride = this.mode() === 'hls' ? 'webrtc' : 'hls';
      this.start();
    });
    const enlarge = document.createElement('button');
    enlarge.type = 'button';
    enlarge.textContent = '⛶';
    enlarge.title = 'Enlarge';
    enlarge.addEventListener('click', () => toggleFocus(this));
    overlay.append(this.urlText, reload, this.modeButton, enlarge);

    this.el.append(logo, badge, overlay);
    this.el.addEventListener('dblclick', () => toggleFocus(this));
    grid.appendChild(this.el);
  }

  mode() {
    return this.modeOverride || globalMode;
  }

  setAddress(text) {
    this.source = parseAddress(text);
    this.modeOverride = null;
  }

  setNote(text, isError) {
    this.note.textContent = text;
    this.note.classList.toggle('error', !!isError);
  }

  setLive(live) {
    this.el.classList.toggle('live', live);
    this.el.classList.toggle('waiting', !live && !!this.source && !this.source.error);
    if (live) {
      this.setNote('');
    }
  }

  start() {
    this.stop();
    this.modeButton.classList.toggle('hidden', !this.source || this.source.kind !== 'server');
    this.modeButton.textContent = this.mode() === 'hls' ? 'HLS' : 'WebRTC';
    if (!this.source) {
      this.urlText.textContent = 'No address';
      this.setNote('No address');
      return;
    }
    if (this.source.error) {
      this.urlText.textContent = this.source.error;
      this.setNote(this.source.error, true);
      return;
    }
    const target = resolve(this.source, this.mode());
    this.urlText.textContent = target.url;
    const session = this.session;
    if (target.kind === 'page') {
      this.setNote('Loading player page…');
      this.playPage(target.url);
    } else if (target.kind === 'hls') {
      this.setNote('Waiting for feed (HLS)…');
      this.playHls(target.url, session);
    } else {
      this.setNote('Waiting for feed (WebRTC)…');
      this.playWebrtc(target.url, session);
    }
  }

  stop() {
    this.session++;
    clearTimeout(this.retryTimer);
    if (this.player) {
      this.player.close();
      this.player = null;
    }
    this.setLive(false);
  }

  /** Shows the logo again and tries the same stream after a pause, unless the box was stopped or restarted meanwhile. */
  retryLater(session, reason) {
    if (session !== this.session) {
      return;
    }
    this.stop();
    this.setNote(reason + ' · retrying…');
    const next = this.session;
    this.retryTimer = setTimeout(() => {
      if (next === this.session) {
        this.start();
      }
    }, RETRY_MS);
  }

  makeVideo(session) {
    const video = document.createElement('video');
    video.muted = true;
    video.autoplay = true;
    video.playsInline = true;
    video.addEventListener('playing', () => {
      if (session === this.session) {
        this.setLive(true);
      }
    });
    this.el.insertBefore(video, this.el.firstChild);
    return video;
  }

  playPage(url) {
    const frame = document.createElement('iframe');
    frame.allow = 'autoplay; fullscreen; picture-in-picture; encrypted-media';
    // Pages from other sites cannot report when their video plays, so the box counts as live once the page has loaded
    frame.addEventListener('load', () => this.setLive(true));
    frame.src = url;
    this.el.insertBefore(frame, this.el.firstChild);
    this.player = { close: () => frame.remove() };
  }

  playHls(url, session) {
    const video = this.makeVideo(session);
    if (typeof Hls === 'undefined' || !Hls.isSupported()) {
      video.addEventListener('error', () => this.retryLater(session, 'No feed yet'));
      video.src = url;
      this.player = { close: () => { video.removeAttribute('src'); video.load(); video.remove(); } };
      return;
    }
    const hls = new Hls({
      enableWorker: false,
      lowLatencyMode: true,
      liveSyncDurationCount: 2,
      liveMaxLatencyDurationCount: 5,
      maxLiveSyncPlaybackRate: 1.5,
    });
    this.player = { close: () => { hls.destroy(); video.remove(); } };
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (data.fatal) {
        this.retryLater(session, data.response && data.response.code === 404 ? 'No feed yet' : 'Feed lost');
      }
    });
    hls.loadSource(url);
    hls.attachMedia(video);
  }

  async playWebrtc(url, session) {
    const video = this.makeVideo(session);
    const pc = new RTCPeerConnection();
    const timer = setTimeout(() => {
      if (!this.el.classList.contains('live')) {
        this.retryLater(session, 'WebRTC blocked on this network? Try HLS');
      }
    }, CONNECT_TIMEOUT_MS);
    this.player = { close: () => { clearTimeout(timer); pc.close(); video.srcObject = null; video.remove(); } };
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.ontrack = (e) => {
      if (!video.srcObject) {
        video.srcObject = e.streams[0] || new MediaStream([e.track]);
      }
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        this.retryLater(session, 'WebRTC connection lost');
      }
    };
    try {
      await pc.setLocalDescription(await pc.createOffer());
      await waitForCandidates(pc);
      if (session !== this.session) {
        return;
      }
      const response = await fetch(url, {
        method: 'POST',
        headers: { 'Content-Type': 'application/sdp' },
        body: pc.localDescription.sdp,
      });
      if (session !== this.session) {
        return;
      }
      if (response.status !== 201) {
        this.retryLater(session, response.status === 404 ? 'No feed yet' : 'Server answered ' + response.status);
        return;
      }
      await pc.setRemoteDescription({ type: 'answer', sdp: await response.text() });
    } catch (e) {
      this.retryLater(session, 'Server not reachable');
    }
  }
}

const grid = document.getElementById('grid');
const form = document.getElementById('addresses');
const serverField = document.getElementById('server-ip');
const modeSelect = document.getElementById('mode');
const modeToggle = document.getElementById('mode-toggle');
const tiles = [];
const fields = [];
let focused = null;

function buildAddressForm(urls) {
  for (let i = 0; i < STREAMS; i++) {
    const row = document.createElement('div');
    row.className = 'row';
    const label = document.createElement('label');
    label.textContent = 'Drone ' + (i + 1);
    label.htmlFor = 'url' + i;
    const input = document.createElement('input');
    input.id = 'url' + i;
    input.type = 'text';
    input.spellcheck = false;
    input.placeholder = 'http://SERVER_IP:8888/live/drone' + (i + 1);
    input.value = urls[i];
    input.addEventListener('keydown', (e) => {
      if (e.key === 'Enter') {
        applyAddresses();
      }
    });
    const clear = document.createElement('button');
    clear.type = 'button';
    clear.textContent = 'Clear';
    clear.addEventListener('click', () => { input.value = ''; input.focus(); });
    row.append(label, input, clear);
    form.appendChild(row);
    fields.push(input);
  }
}

/** Puts http://SERVER:8888/live/droneN into every empty box; boxes that already have an address keep it. */
function fillFromServer() {
  const server = serverField.value.trim().replace(/^[a-z]+:\/\//i, '').replace(/[/:].*$/, '');
  if (!server) {
    serverField.focus();
    return;
  }
  serverField.value = server;
  store(SERVER_KEY, server);
  fields.forEach((field, i) => {
    if (!field.value.trim()) {
      field.value = 'http://' + server + ':' + HLS_PORT + '/live/drone' + (i + 1);
    }
  });
}

function setGlobalMode(mode) {
  globalMode = mode;
  store(MODE_KEY, mode);
  modeSelect.value = mode;
  modeToggle.textContent = 'Server streams: ' + (mode === 'hls' ? 'HLS' : 'WebRTC');
  modeToggle.title = 'Switch every server box to ' + (mode === 'hls' ? 'WebRTC (lower delay)' : 'HLS (more reliable)');
}

function showPage(gridPage) {
  // Hidden pages pause their animations, so a drone still in flight would wait there: land it now
  document.querySelectorAll('#setup .drone, #grid .drone').forEach((drone) => drone.remove());
  document.getElementById('setup').classList.toggle('hidden', gridPage);
  document.getElementById('grid-page').classList.toggle('hidden', !gridPage);
  if (!gridPage) {
    setFocus(null);
    tiles.forEach((tile) => tile.stop());
  }
}

function applyAddresses() {
  const texts = fields.map((field) => field.value.trim());
  texts.forEach((text, i) => { fields[i].value = text; });
  store(STORAGE_KEY, JSON.stringify(texts));
  showPage(true);
  tiles.forEach((tile, i) => {
    tile.setAddress(texts[i]);
    tile.start();
  });
}

function setFocus(tile) {
  if (focused) {
    focused.el.classList.remove('focused');
  }
  focused = tile;
  grid.classList.toggle('focus', !!tile);
  if (tile) {
    tile.el.classList.add('focused');
  }
}

function toggleFocus(tile) {
  setFocus(focused === tile ? null : tile);
}

// Start-up splash: the logo alone on dark blue, then it fades (a click skips it)
const splash = document.getElementById('splash');
const hideSplash = () => splash.classList.add('done');
splash.addEventListener('click', hideSplash);
setTimeout(hideSplash, 2600);

document.querySelector('.title-logo').addEventListener('click', () => flyDrone(document.getElementById('header')));

const saved = loadUrls();
buildAddressForm(saved);
for (let i = 0; i < STREAMS; i++) {
  tiles.push(new Tile(i, grid));
}
serverField.value = load(SERVER_KEY, '');
setGlobalMode(globalMode);
modeSelect.addEventListener('change', () => setGlobalMode(modeSelect.value));
modeToggle.addEventListener('click', () => {
  setGlobalMode(globalMode === 'hls' ? 'webrtc' : 'hls');
  tiles.forEach((tile) => {
    tile.modeOverride = null;
    if (tile.source && tile.source.kind === 'server') {
      tile.start();
    }
  });
});
document.getElementById('fill').addEventListener('click', fillFromServer);
serverField.addEventListener('keydown', (e) => {
  if (e.key === 'Enter') {
    fillFromServer();
  }
});
document.getElementById('ok').addEventListener('click', applyAddresses);
document.getElementById('back').addEventListener('click', () => showPage(false));
document.addEventListener('keydown', (e) => {
  if (e.key !== 'Escape' || document.getElementById('grid-page').classList.contains('hidden')) {
    return;
  }
  if (focused) {
    setFocus(null);
  } else {
    showPage(false);
  }
});

if (saved.some((text) => text)) {
  applyAddresses();
}
