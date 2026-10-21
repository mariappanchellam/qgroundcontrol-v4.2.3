'use strict';

// Dhaksha Live: 10 boxes, one per drone, playing from the Dhaksha video server (MediaMTX) as HLS or WebRTC.
//   Boxes 1-5: Skydroid, day stream droneN in the box and the night stream droneN-night in a small
//              picture-in-picture at its corner; clicking the small picture swaps day and night.
//   Boxes 6-10: SIYI ZR10, one stream droneN.
// The camera window sets each box's server IP and port. Until a box's video is actually playing it shows
// the Dhaksha logo. The settings are kept between runs.

const BOXES = 10;
const SKYDROID_BOXES = 5;
const CAMERAS_KEY = 'dhakshaLive.cameras';
const MODE_KEY = 'dhakshaLive.mode';
const DEFAULT_SERVER = '15.252.170.151';
const HLS_PORT = '8888';
const WEBRTC_PORT = '8889';
const RETRY_MS = 3000;
const MAX_DELAY_S = 3;      // seconds behind the newest video before a box jumps back to live
const CONNECT_TIMEOUT_MS = 10000;
// The web version: the page's own server, and whether it was opened on the plain web port (80), where the
// server also passes the video through /hls/ and /webrtc/ so networks that allow only web ports still play it
const PAGE_HOST = /^https?:$/.test(location.protocol) ? location.hostname : '';
const VIDEO_THROUGH_PAGE = PAGE_HOST !== '' && (location.port === '' || location.port === '80');

function isSkydroid(box) {
  return box < SKYDROID_BOXES;
}

function streamName(box, night) {
  return 'drone' + (box + 1) + (night ? '-night' : '');
}

/** A stream on the Dhaksha server: `port` is the one typed in the camera window (8888 HLS, or 80 through the web port). */
function serverSource(ip, port, name) {
  return { host: ip, port: port, path: '/live/' + name, name: name };
}

/** The HLS playlist or WebRTC (WHEP) address of a server stream for `mode`. */
function resolve(source, mode) {
  if (source.port === '80') {
    const origin = 'http://' + source.host;
    return mode === 'webrtc'
      ? { kind: 'webrtc', url: origin + '/webrtc' + source.path + '/whep' }
      : { kind: 'hls', url: origin + '/hls' + source.path + '/index.m3u8' };
  }
  const origin = 'http://' + source.host;
  const hlsPort = source.port && source.port !== WEBRTC_PORT ? source.port : HLS_PORT;
  return mode === 'webrtc'
    ? { kind: 'webrtc', url: origin + ':' + WEBRTC_PORT + source.path + '/whep' }
    : { kind: 'hls', url: origin + ':' + hlsPort + source.path + '/index.m3u8' };
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

function defaultCamera() {
  return { ip: PAGE_HOST || DEFAULT_SERVER, port: VIDEO_THROUGH_PAGE ? '80' : HLS_PORT, show: true };
}

/** The camera window's settings: server IP, port and whether the box is shown, for each of the 10 boxes. */
function loadCameras() {
  let saved = [];
  try {
    saved = JSON.parse(load(CAMERAS_KEY, '[]'));
  } catch (e) {
    saved = [];
  }
  return Array.from({ length: BOXES }, (_, i) => {
    const camera = Array.isArray(saved) && saved[i] ? saved[i] : {};
    const fallback = defaultCamera();
    return {
      ip: typeof camera.ip === 'string' ? camera.ip : fallback.ip,
      port: typeof camera.port === 'string' ? camera.port : fallback.port,
      show: typeof camera.show === 'boolean' ? camera.show : fallback.show,
    };
  });
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
  /** A box in the grid; with `pip` a small picture inside another box instead (no controls of its own). */
  constructor(index, parent, pip) {
    this.source = null;
    this.modeOverride = null;
    this.player = null;
    this.retryTimer = 0;
    this.session = 0;
    this.pip = null;
    this.isPip = !!pip;

    this.el = document.createElement('div');
    this.el.className = this.isPip ? 'tile pip' : 'tile';

    const logo = document.createElement('div');
    logo.className = 'logo';
    this.note = document.createElement('div');
    this.note.className = 'note';
    this.label = document.createElement('div');
    this.label.className = 'badge';

    if (this.isPip) {
      logo.append(this.note);
      this.el.append(logo, this.label);
      parent.appendChild(this.el);
      return;
    }

    const img = document.createElement('img');
    img.src = 'logo.svg';
    img.alt = 'Dhaksha Drones';
    img.addEventListener('click', (e) => {
      e.stopPropagation();
      flyDrone(logo);
    });
    const sub = document.createElement('div');
    sub.className = 'sub';
    sub.textContent = 'Drone ' + (index + 1) + (isSkydroid(index) ? ' · Skydroid' : ' · ZR10');
    logo.append(img, sub, this.note);

    const overlay = document.createElement('div');
    overlay.className = 'overlay';
    this.urlText = document.createElement('span');
    this.urlText.className = 'url';
    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = '↻';
    reload.title = 'Reload';
    reload.addEventListener('click', () => this.startAll());
    this.modeButton = document.createElement('button');
    this.modeButton.type = 'button';
    this.modeButton.title = 'Switch this box between HLS and WebRTC';
    this.modeButton.addEventListener('click', () => {
      this.modeOverride = this.mode() === 'hls' ? 'webrtc' : 'hls';
      this.startAll();
    });
    const enlarge = document.createElement('button');
    enlarge.type = 'button';
    enlarge.textContent = '⛶';
    enlarge.title = 'Enlarge';
    enlarge.addEventListener('click', () => toggleFocus(this));
    overlay.append(this.urlText, reload, this.modeButton, enlarge);

    this.el.append(logo, this.label, overlay);
    this.el.addEventListener('dblclick', () => toggleFocus(this));
    parent.appendChild(this.el);

    if (isSkydroid(index)) {
      // The night (or day) stream in the corner; a click swaps it with the big picture
      this.pip = new Tile(index, this.el, true);
      this.pip.el.title = 'Click to swap day and night';
      this.pip.el.addEventListener('click', (e) => {
        e.stopPropagation();
        this.swap();
      });
      this.pip.el.addEventListener('dblclick', (e) => e.stopPropagation());
    }
  }

  mode() {
    return this.isPip && this.owner ? this.owner.mode() : (this.modeOverride || globalMode);
  }

  /** Day stream `source`, and for a Skydroid box its night stream `nightSource`; day starts in the big picture. */
  setSources(box, source, nightSource) {
    this.box = box;
    this.source = source;
    this.modeOverride = null;
    this.nightOnMain = false;
    if (this.pip) {
      this.pip.owner = this;
      this.pip.source = nightSource;
    }
    this.updateLabels();
  }

  updateLabels() {
    if (!this.pip) {
      this.label.textContent = (this.box + 1) + ' · ZR10';
      return;
    }
    this.label.textContent = (this.box + 1) + (this.nightOnMain ? ' · NIGHT' : ' · DAY');
    this.pip.label.textContent = this.nightOnMain ? 'DAY' : 'NIGHT';
  }

  /** Day and night change places between the big and the small picture. */
  swap() {
    if (!this.pip) {
      return;
    }
    [this.source, this.pip.source] = [this.pip.source, this.source];
    this.nightOnMain = !this.nightOnMain;
    this.updateLabels();
    this.startAll();
  }

  startAll() {
    this.start();
    if (this.pip) {
      this.pip.start();
    }
  }

  stopAll() {
    this.stop();
    if (this.pip) {
      this.pip.stop();
    }
  }

  setNote(text, isError) {
    this.note.textContent = text;
    this.note.classList.toggle('error', !!isError);
  }

  setLive(live) {
    this.el.classList.toggle('live', live);
    this.el.classList.toggle('waiting', !live && !!this.source);
    if (live) {
      this.setNote('');
    }
  }

  start() {
    this.stop();
    if (!this.source) {
      this.setNote('No address');
      return;
    }
    const target = resolve(this.source, this.mode());
    const session = this.session;
    if (!this.isPip) {
      this.modeButton.textContent = this.mode() === 'hls' ? 'HLS' : 'WebRTC';
      this.urlText.textContent = target.url;
    }
    if (target.kind === 'hls') {
      this.setNote(this.isPip ? 'Waiting…' : 'Waiting for feed (HLS)…');
      this.playHls(target.url, session);
    } else {
      this.setNote(this.isPip ? 'Waiting…' : 'Waiting for feed (WebRTC)…');
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
    this.setNote(this.isPip ? reason : reason + ' · retrying…');
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
      liveMaxLatencyDurationCount: 4,
      maxLiveSyncPlaybackRate: 1.5,
      // Keep only a few seconds: by default played video is never dropped, so memory and delay kept growing
      backBufferLength: 2,
      maxBufferLength: 4,
      maxMaxBufferLength: 6,
    });
    // Jump back to live whenever the picture falls behind (after a network hiccup or a busy laptop)
    const catchUp = setInterval(() => {
      const buffered = video.buffered;
      if (!buffered.length || video.paused) {
        return;
      }
      const liveEdge = buffered.end(buffered.length - 1);
      if (liveEdge - video.currentTime > MAX_DELAY_S) {
        video.currentTime = liveEdge - 0.3;
      }
    }, 1000);
    this.player = { close: () => { clearInterval(catchUp); hls.destroy(); video.remove(); } };
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
const cameraRows = document.getElementById('cameras');
const allIp = document.getElementById('all-ip');
const allPort = document.getElementById('all-port');
const modeSelect = document.getElementById('mode');
const modeToggle = document.getElementById('mode-toggle');
const tiles = [];
const rows = [];
const shownCount = document.getElementById('shown-count');
let focused = null;

function updateShownCount() {
  const n = rows.filter((row) => row.check.checked).length;
  shownCount.textContent = n === 1 ? '1 drone ticked' : n + ' drones ticked';
}

/** One row per box: show, box number, camera type, server IP, port and the stream names it plays. */
function buildCameraWindow(cameras) {
  for (let i = 0; i < BOXES; i++) {
    const row = document.createElement('div');
    row.className = 'camera-row' + (isSkydroid(i) ? ' skydroid' : ' zr10');
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.checked = cameras[i].show;
    check.title = 'Show this drone (unticked drones use no CPU or network)';
    check.addEventListener('change', updateShownCount);
    const box = document.createElement('span');
    box.className = 'box';
    box.textContent = String(i + 1);
    const type = document.createElement('span');
    type.className = 'type';
    type.textContent = isSkydroid(i) ? 'Skydroid · day + night' : 'ZR10';
    const ip = document.createElement('input');
    ip.type = 'text';
    ip.spellcheck = false;
    ip.className = 'ip';
    ip.placeholder = DEFAULT_SERVER;
    ip.value = cameras[i].ip;
    const port = document.createElement('input');
    port.type = 'text';
    port.inputMode = 'numeric';
    port.className = 'port';
    port.placeholder = HLS_PORT;
    port.value = cameras[i].port;
    const streams = document.createElement('span');
    streams.className = 'streams';
    streams.textContent = isSkydroid(i) ? streamName(i, false) + ' + ' + streamName(i, true) : streamName(i, false);
    for (const field of [ip, port]) {
      field.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          applyCameras();
        }
      });
    }
    row.append(check, box, type, ip, port, streams);
    cameraRows.appendChild(row);
    rows.push({ check, ip, port });
  }
}

function cleanIp(text) {
  return text.trim().replace(/^[a-z]+:\/\//i, '').replace(/[/:].*$/, '');
}

function cleanPort(text) {
  const port = text.trim().replace(/[^0-9]/g, '');
  return port || HLS_PORT;
}

/** Puts the IP and port at the top into every box. */
function applyToAll() {
  const ip = cleanIp(allIp.value);
  if (!ip) {
    allIp.focus();
    return;
  }
  const port = cleanPort(allPort.value);
  allIp.value = ip;
  allPort.value = port;
  rows.forEach((row) => {
    row.ip.value = ip;
    row.port.value = port;
  });
}

function setGlobalMode(mode) {
  globalMode = mode;
  store(MODE_KEY, mode);
  modeSelect.value = mode;
  modeToggle.textContent = 'Streams: ' + (mode === 'hls' ? 'HLS' : 'WebRTC');
  modeToggle.title = 'Switch every box to ' + (mode === 'hls' ? 'WebRTC (lower delay)' : 'HLS (more reliable)');
}

function showPage(gridPage) {
  // Hidden pages pause their animations, so a drone still in flight would wait there: land it now
  document.querySelectorAll('#setup .drone, #grid .drone').forEach((drone) => drone.remove());
  document.getElementById('setup').classList.toggle('hidden', gridPage);
  document.getElementById('grid-page').classList.toggle('hidden', !gridPage);
  if (!gridPage) {
    setFocus(null);
    tiles.forEach((tile) => tile.stopAll());
  }
}

/** Saves the camera window and shows only the ticked drones; unticked boxes run no player at all. */
function applyCameras() {
  const cameras = rows.map((row) => ({ ip: cleanIp(row.ip.value), port: cleanPort(row.port.value), show: row.check.checked }));
  cameras.forEach((camera, i) => {
    rows[i].ip.value = camera.ip;
    rows[i].port.value = camera.port;
  });
  store(CAMERAS_KEY, JSON.stringify(cameras));
  const okNote = document.getElementById('ok-note');
  const missing = cameras.findIndex((camera) => camera.show && !camera.ip);
  if (missing >= 0) {
    okNote.textContent = 'Drone ' + (missing + 1) + ' has no IP.';
    rows[missing].ip.focus();
    return;
  }
  const count = cameras.filter((camera) => camera.show).length;
  okNote.textContent = count ? '' : 'Tick at least one drone to show.';
  if (!count) {
    return;
  }
  // As square as possible: 1 drone fills the window, 4 make 2 x 2, 10 make 4 x 3
  const columns = Math.ceil(Math.sqrt(count));
  grid.style.setProperty('--columns', columns);
  grid.style.setProperty('--rows', Math.ceil(count / columns));
  showPage(true);
  tiles.forEach((tile, i) => {
    const camera = cameras[i];
    tile.el.classList.toggle('hidden', !camera.show);
    if (!camera.show) {
      tile.stopAll();
      return;
    }
    const day = serverSource(camera.ip, camera.port, streamName(i, false));
    const night = isSkydroid(i) ? serverSource(camera.ip, camera.port, streamName(i, true)) : null;
    tile.setSources(i, day, night);
    tile.startAll();
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

const cameras = loadCameras();
buildCameraWindow(cameras);
updateShownCount();
allIp.value = cameras[0].ip;
allPort.value = cameras[0].port;
for (let i = 0; i < BOXES; i++) {
  tiles.push(new Tile(i, grid, false));
}
document.getElementById('tick-all').addEventListener('click', () => {
  rows.forEach((row) => { row.check.checked = true; });
  updateShownCount();
});
document.getElementById('tick-none').addEventListener('click', () => {
  rows.forEach((row) => { row.check.checked = false; });
  updateShownCount();
});
document.getElementById('apply-all').addEventListener('click', applyToAll);
setGlobalMode(globalMode);
modeSelect.addEventListener('change', () => setGlobalMode(modeSelect.value));
modeToggle.addEventListener('click', () => {
  setGlobalMode(globalMode === 'hls' ? 'webrtc' : 'hls');
  tiles.forEach((tile) => {
    tile.modeOverride = null;
    if (!tile.el.classList.contains('hidden') && tile.source) {
      tile.startAll();
    }
  });
});
document.getElementById('ok').addEventListener('click', applyCameras);
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

// Straight to the drones when this computer has used the camera window before
if (load(CAMERAS_KEY, null) !== null) {
  applyCameras();
}
