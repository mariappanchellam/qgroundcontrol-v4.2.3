'use strict';

// Dhaksha Live: up to 25 boxes, one per drone, playing from the Dhaksha video server (MediaMTX) as HLS or WebRTC.
// The camera window sets each drone's camera and what its box shows:
//   Skydroid: Day (droneN), Night (droneN-night), or Day + Night: day in the box and night in a small
//             picture-in-picture at its corner; clicking the small picture swaps day and night.
//   SIYI ZR10: Day only (droneN).
// One click on a box enlarges it to the whole window; another click shrinks it back to the grid.
// The camera window also sets each box's server IP and port and lists its stream addresses. Until a box's
// video is actually playing it shows the Dhaksha logo. When video arrives slower than it plays, a message
// says the internet is too slow. The settings are kept between runs.

const BOXES = 25;
const FIRST_SHOWN = 10;        // a new computer shows drones 1-10: 1-5 Skydroid day + night, 6-10 ZR10
const FIRST_SKYDROID = 5;
const CAMERA_TYPES = { skydroid: 'Skydroid', zr10: 'ZR10' };
const VIEWS = { day: 'Day', night: 'Night', both: 'Day + Night' };
const CAMERAS_KEY = 'dhakshaLive.cameras';
const MODE_KEY = 'dhakshaLive.mode';
const DEFAULT_SERVER = '15.252.170.151';
const HLS_PORT = '8888';
const WEBRTC_PORT = '8889';
const RETRY_MS = 3000;
const MAX_DELAY_S = 3;      // seconds behind the newest video before a box jumps back to live
const CONNECT_TIMEOUT_MS = 10000;
// Slow internet: a video piece that takes longer to download than to play, or WebRTC losing this share of packets
const SLOW_PIECES = 2;          // pieces in a row before the message
const SLOW_LOSS = 0.05;
const SLOW_SHOWN_MS = 8000;     // the message stays this long after the last sign of a slow internet
// The web version: the page's own server, and whether it was opened on the plain web port (80), where the
// server also passes the video through /hls/ and /webrtc/ so networks that allow only web ports still play it
const PAGE_HOST = /^https?:$/.test(location.protocol) ? location.hostname : '';
const VIDEO_THROUGH_PAGE = PAGE_HOST !== '' && (location.port === '' || location.port === '80');

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

function defaultCamera(box) {
  const skydroid = box < FIRST_SKYDROID || box >= FIRST_SHOWN;
  return {
    ip: PAGE_HOST || DEFAULT_SERVER,
    port: VIDEO_THROUGH_PAGE ? '80' : HLS_PORT,
    show: box < FIRST_SHOWN,
    type: skydroid ? 'skydroid' : 'zr10',
    view: skydroid ? 'both' : 'day',
  };
}

/** What a box shows: ZR10 has only the day camera. */
function cleanView(type, view) {
  return type === 'zr10' || !VIEWS[view] ? 'day' : view;
}

/** The camera window's settings for each of the 25 boxes: server IP, port, shown, camera type and view. */
function loadCameras() {
  let saved = [];
  try {
    saved = JSON.parse(load(CAMERAS_KEY, '[]'));
  } catch (e) {
    saved = [];
  }
  return Array.from({ length: BOXES }, (_, i) => {
    const camera = Array.isArray(saved) && saved[i] ? saved[i] : {};
    const fallback = defaultCamera(i);
    // Settings saved before 4.2 have no camera type: boxes 1-5 were Skydroid day + night, 6-10 ZR10
    const type = CAMERA_TYPES[camera.type] ? camera.type : fallback.type;
    return {
      ip: typeof camera.ip === 'string' ? camera.ip : fallback.ip,
      port: typeof camera.port === 'string' ? camera.port : fallback.port,
      show: typeof camera.show === 'boolean' ? camera.show : fallback.show,
      type: type,
      view: cleanView(type, typeof camera.view === 'string' ? camera.view : fallback.view),
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
    this.hasPip = false;        // Day + Night: the small picture is in use
    this.slowPieces = 0;
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
    this.sub = document.createElement('div');
    this.sub.className = 'sub';
    this.sub.textContent = 'Drone ' + (index + 1);
    logo.append(img, this.sub, this.note);

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
    // The overlay buttons act on their own, without also enlarging or shrinking the box
    overlay.addEventListener('click', (e) => e.stopPropagation());

    this.el.append(logo, this.label, overlay);
    this.el.addEventListener('click', () => toggleFocus(this));
    parent.appendChild(this.el);

    // The night (or day) stream in the corner, used for Day + Night; a click swaps it with the big picture
    this.pip = new Tile(index, this.el, true);
    this.pip.owner = this;
    this.pip.el.classList.add('hidden');
    this.pip.el.title = 'Click to swap day and night';
    this.pip.el.addEventListener('click', (e) => {
      e.stopPropagation();
      this.swap();
    });
  }

  mode() {
    return this.isPip && this.owner ? this.owner.mode() : (this.modeOverride || globalMode);
  }

  /**
   * The drone's streams: `day` (droneN) and `night` (droneN-night), its camera `type` and `view`:
   * Day or Night alone fill the box; Day + Night puts night in the small picture (day starts in the big one).
   */
  setSources(box, day, night, type, view) {
    this.box = box;
    this.type = type;
    this.modeOverride = null;
    this.nightOnMain = view === 'night';
    this.source = this.nightOnMain ? night : day;
    this.hasPip = view === 'both';
    this.pip.source = this.hasPip ? night : null;
    this.pip.el.classList.toggle('hidden', !this.hasPip);
    this.sub.textContent = 'Drone ' + (box + 1) + ' · ' + CAMERA_TYPES[type];
    this.updateLabels();
  }

  updateLabels() {
    if (this.type === 'zr10') {
      this.label.textContent = (this.box + 1) + ' · ZR10';
      return;
    }
    this.label.textContent = (this.box + 1) + (this.nightOnMain ? ' · NIGHT' : ' · DAY');
    this.pip.label.textContent = this.nightOnMain ? 'DAY' : 'NIGHT';
  }

  /** Day and night change places between the big and the small picture. */
  swap() {
    if (!this.hasPip) {
      return;
    }
    [this.source, this.pip.source] = [this.pip.source, this.source];
    this.nightOnMain = !this.nightOnMain;
    this.updateLabels();
    this.startAll();
  }

  startAll() {
    this.start();
    if (this.hasPip) {
      this.pip.start();
    }
  }

  stopAll() {
    this.stop();
    if (this.pip) {
      this.pip.stop();
    }
  }

  /** Video for this box arrives slower than it plays: tell the operator the internet is too slow. */
  reportSlow() {
    const owner = this.isPip ? this.owner : this;
    if (owner && owner.box !== undefined) {
      notifySlow(owner.box);
    }
  }

  /** A video piece took `loadMs` to download and plays for `durationS`; two slower than real time in a row are slow. */
  countPiece(loadMs, durationS) {
    if (!durationS) {
      return;
    }
    this.slowPieces = loadMs > durationS * 1000 ? this.slowPieces + 1 : 0;
    if (this.slowPieces >= SLOW_PIECES) {
      this.reportSlow();
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
    this.slowPieces = 0;
    hls.on(Hls.Events.FRAG_LOADED, (_, data) => {
      const stats = data.frag.stats || data.stats;
      if (session === this.session && stats && stats.loading) {
        this.countPiece(stats.loading.end - stats.loading.start, data.frag.duration);
      }
    });
    hls.on(Hls.Events.ERROR, (_, data) => {
      if (session !== this.session) {
        return;
      }
      // Downloads that time out, or fail without any answer while the video was playing, mean the internet is not
      // keeping up (no answer before it ever played is more likely a wrong address)
      const timedOut = /TimeOut$/.test(data.details || '')
                       || (data.type === Hls.ErrorTypes.NETWORK_ERROR && data.response && data.response.code === 0
                           && this.el.classList.contains('live'));
      if (timedOut) {
        this.reportSlow();
      }
      if (data.fatal) {
        this.retryLater(session, data.response && data.response.code === 404 ? 'No feed yet'
                                 : timedOut ? 'Internet too slow' : 'Feed lost');
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
    // Every 2 s: many packets lost on the way means the internet is too slow for this video
    let last = null;
    const watch = setInterval(async () => {
      if (session !== this.session || pc.connectionState !== 'connected') {
        return;
      }
      try {
        (await pc.getStats()).forEach((report) => {
          if (report.type !== 'inbound-rtp' || report.kind !== 'video') {
            return;
          }
          if (last) {
            const lost = (report.packetsLost || 0) - last.packetsLost;
            const received = (report.packetsReceived || 0) - last.packetsReceived;
            if (lost > 0 && lost / (lost + received) > SLOW_LOSS) {
              this.reportSlow();
            }
          }
          last = { packetsLost: report.packetsLost || 0, packetsReceived: report.packetsReceived || 0 };
        });
      } catch (e) {
        // Statistics are only for the message; playback goes on without them
      }
    }, 2000);
    this.player = { close: () => { clearTimeout(timer); clearInterval(watch); pc.close(); video.srcObject = null; video.remove(); } };
    pc.addTransceiver('video', { direction: 'recvonly' });
    pc.addTransceiver('audio', { direction: 'recvonly' });
    pc.ontrack = (e) => {
      if (!video.srcObject) {
        video.srcObject = e.streams[0] || new MediaStream([e.track]);
      }
    };
    pc.onconnectionstatechange = () => {
      if (pc.connectionState === 'failed' || pc.connectionState === 'disconnected') {
        if (this.el.classList.contains('live')) {
          this.reportSlow();
        }
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

/** The stream addresses a camera window row plays now, as the boxes will use them (HLS or WebRTC). */
function rowAddresses(row, i) {
  const ip = cleanIp(row.ip.value) || DEFAULT_SERVER;
  const port = cleanPort(row.port.value);
  const view = cleanView(row.type.value, rowView(row));
  const names = view === 'both' ? [streamName(i, false), streamName(i, true)] : [streamName(i, view === 'night')];
  return names.map((name) => resolve(serverSource(ip, port, name), globalMode).url);
}

function rowView(row) {
  const checked = row.views.find((input) => input.checked);
  return checked ? checked.value : 'day';
}

/** Shows the choices the row's camera has (ZR10: Day only) and its stream addresses. */
function updateRow(row, i) {
  const zr10 = row.type.value === 'zr10';
  row.el.classList.toggle('zr10', zr10);
  row.el.classList.toggle('skydroid', !zr10);
  row.views.forEach((input) => {
    input.parentElement.classList.toggle('hidden', zr10 && input.value !== 'day');
  });
  if (zr10) {
    row.views[0].checked = true;
  }
  row.urls.textContent = '';
  rowAddresses(row, i).forEach((url) => {
    const line = document.createElement('div');
    line.textContent = url;
    row.urls.appendChild(line);
  });
}

/** One row per drone: show, box number, camera (Skydroid / ZR10), view, server IP, port and its stream addresses. */
function buildCameraWindow(cameras) {
  for (let i = 0; i < BOXES; i++) {
    const el = document.createElement('div');
    el.className = 'camera-row';
    const check = document.createElement('input');
    check.type = 'checkbox';
    check.checked = cameras[i].show;
    check.title = 'Show this drone (unticked drones use no CPU or network)';
    check.addEventListener('change', updateShownCount);
    const box = document.createElement('span');
    box.className = 'box';
    box.textContent = String(i + 1);
    const type = document.createElement('select');
    type.className = 'type';
    type.title = 'Camera on this drone';
    for (const [value, label] of Object.entries(CAMERA_TYPES)) {
      type.add(new Option(label, value));
    }
    type.value = cameras[i].type;
    // Skydroid can show Day, Night or both; ZR10 only Day
    const viewGroup = document.createElement('span');
    viewGroup.className = 'views';
    const views = Object.entries(VIEWS).map(([value, label]) => {
      const choice = document.createElement('label');
      const input = document.createElement('input');
      input.type = 'radio';
      input.name = 'view' + i;
      input.value = value;
      input.checked = cameras[i].view === value;
      choice.append(input, label);
      viewGroup.appendChild(choice);
      return input;
    });
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
    const urls = document.createElement('span');
    urls.className = 'streams';
    for (const field of [ip, port]) {
      field.addEventListener('keydown', (e) => {
        if (e.key === 'Enter') {
          applyCameras();
        }
      });
    }
    el.append(check, box, type, viewGroup, ip, port, urls);
    cameraRows.appendChild(el);
    const row = { el, check, type, views, ip, port, urls };
    rows.push(row);
    for (const field of [type, ip, port, ...views]) {
      field.addEventListener(field === ip || field === port ? 'input' : 'change', () => updateRow(row, i));
    }
    updateRow(row, i);
  }
}

function updateAllRows() {
  rows.forEach((row, i) => updateRow(row, i));
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
  updateAllRows();
}

function setGlobalMode(mode) {
  globalMode = mode;
  store(MODE_KEY, mode);
  modeSelect.value = mode;
  modeToggle.textContent = 'Streams: ' + (mode === 'hls' ? 'HLS' : 'WebRTC');
  modeToggle.title = 'Switch every box to ' + (mode === 'hls' ? 'WebRTC (lower delay)' : 'HLS (more reliable)');
  updateAllRows();
}

function showPage(gridPage) {
  // Hidden pages pause their animations, so a drone still in flight would wait there: land it now
  document.querySelectorAll('#setup .drone, #grid .drone').forEach((drone) => drone.remove());
  document.getElementById('setup').classList.toggle('hidden', gridPage);
  document.getElementById('grid-page').classList.toggle('hidden', !gridPage);
  if (!gridPage) {
    setFocus(null);
    hideSlow();
    tiles.forEach((tile) => tile.stopAll());
  }
}

/** Saves the camera window and shows only the ticked drones; unticked boxes run no player at all. */
function applyCameras() {
  const cameras = rows.map((row) => ({
    ip: cleanIp(row.ip.value),
    port: cleanPort(row.port.value),
    show: row.check.checked,
    type: row.type.value,
    view: cleanView(row.type.value, rowView(row)),
  }));
  cameras.forEach((camera, i) => {
    rows[i].ip.value = camera.ip;
    rows[i].port.value = camera.port;
    updateRow(rows[i], i);
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
  // As square as possible: 1 drone fills the window, 4 make 2 x 2, 10 make 4 x 3, 25 make 5 x 5
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
    const night = serverSource(camera.ip, camera.port, streamName(i, true));
    tile.setSources(i, day, night, camera.type, camera.view);
    tile.startAll();
  });
}

// The slow internet message: one for all boxes, naming the drones whose video is late
const slowToast = document.getElementById('slow-toast');
const slowDrones = new Map();   // box -> time of its last slow report
let slowTimer = 0;

function notifySlow(box) {
  if (document.getElementById('grid-page').classList.contains('hidden')) {
    return;
  }
  const now = Date.now();
  slowDrones.set(box, now);
  for (const [b, at] of slowDrones) {
    if (now - at > SLOW_SHOWN_MS) {
      slowDrones.delete(b);
    }
  }
  const drones = [...slowDrones.keys()].sort((a, b) => a - b).map((b) => b + 1);
  slowToast.querySelector('.detail').textContent =
    (drones.length === 1 ? 'Drone ' : 'Drones ') + drones.join(', ') + ': video may pause or lag behind.';
  slowToast.classList.add('shown');
  clearTimeout(slowTimer);
  slowTimer = setTimeout(hideSlow, SLOW_SHOWN_MS);
}

function hideSlow() {
  clearTimeout(slowTimer);
  slowDrones.clear();
  slowToast.classList.remove('shown');
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
