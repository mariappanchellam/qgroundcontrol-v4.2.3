'use strict';

// Dhaksha Live desktop: eight boxes, each showing a Livepush player page for one drone.
// Empty boxes, and boxes whose address is not a Livepush player link, show the Dhaksha logo.
// The addresses are kept between runs.

const STREAMS = 8;
const STORAGE_KEY = 'dhakshaLive.urls';
const LIVEPUSH_HOST = 'player.livepush.io';

/**
 * Returns the Livepush player link in a standard form (https:// added when missing),
 * '' for an empty box, or null when the text is not a Livepush player link.
 */
function livepushUrl(input) {
  let text = (input || '').trim();
  if (!text) {
    return '';
  }
  if (!text.includes('://')) {
    text = 'https://' + text;
  }
  let url;
  try {
    url = new URL(text);
  } catch (e) {
    return null;
  }
  if (url.protocol !== 'https:' || url.hostname !== LIVEPUSH_HOST || url.pathname.replace(/\/+/g, '') === '') {
    return null;
  }
  return url.toString();
}

function loadUrls() {
  try {
    const saved = JSON.parse(localStorage.getItem(STORAGE_KEY) || '[]');
    return Array.from({ length: STREAMS }, (_, i) => (typeof saved[i] === 'string' ? saved[i] : ''));
  } catch (e) {
    return new Array(STREAMS).fill('');
  }
}

function saveUrls(urls) {
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify(urls));
  } catch (e) {
    // Not being able to remember the addresses must not stop playback
  }
}

const DRONE_SVG =
  '<svg viewBox="0 0 120 50" xmlns="http://www.w3.org/2000/svg">' +
  '<rect x="20" y="19" width="80" height="5" rx="2" fill="#ffa000"/>' +
  '<rect x="46" y="14" width="28" height="15" rx="5" fill="#ffa000"/>' +
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

/** The Dhaksha logo: a big D and a smaller "haksha" whose letters fade in. Clicking it flies a drone across `area`. */
function makeLogo(area) {
  const logo = document.createElement('span');
  logo.className = 'dlogo';
  logo.title = 'Dhaksha';
  const big = document.createElement('span');
  big.className = 'D';
  big.textContent = 'D';
  const word = document.createElement('span');
  word.className = 'haksha';
  for (const letter of 'haksha') {
    const span = document.createElement('span');
    span.textContent = letter;
    word.appendChild(span);
  }
  logo.append(big, word);
  if (area) {
    logo.addEventListener('click', (e) => {
      e.stopPropagation();
      flyDrone(area);
    });
  }
  return logo;
}

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

class Tile {
  constructor(index, grid) {
    this.index = index;
    this.text = '';
    this.url = '';
    this.frame = null;

    this.el = document.createElement('div');
    this.el.className = 'tile';

    const logo = document.createElement('div');
    logo.className = 'logo';
    const name = makeLogo(logo);
    const sub = document.createElement('div');
    sub.className = 'sub';
    sub.textContent = 'Drone ' + (index + 1);
    this.note = document.createElement('div');
    this.note.className = 'note';
    logo.append(name, sub, this.note);

    this.badge = document.createElement('div');
    this.badge.className = 'badge';
    this.badge.textContent = String(index + 1);

    const overlay = document.createElement('div');
    overlay.className = 'overlay';
    this.urlText = document.createElement('span');
    this.urlText.className = 'url';
    const reload = document.createElement('button');
    reload.type = 'button';
    reload.textContent = '↻ Reload';
    reload.addEventListener('click', () => this.start());
    const enlarge = document.createElement('button');
    enlarge.type = 'button';
    enlarge.textContent = '⛶ Enlarge';
    enlarge.addEventListener('click', () => toggleFocus(this));
    overlay.append(this.urlText, reload, enlarge);

    this.el.append(logo, this.badge, overlay);
    this.el.addEventListener('dblclick', () => toggleFocus(this));
    grid.appendChild(this.el);
  }

  setAddress(text) {
    this.text = text;
    const url = livepushUrl(text);
    this.url = url || '';
    this.urlText.textContent = this.url || (text ? 'Not a Livepush player link' : 'No address');
    this.note.textContent = url === null ? 'Not a Livepush player link' : '';
  }

  start() {
    this.stop();
    if (!this.url) {
      return;
    }
    const frame = document.createElement('iframe');
    frame.allow = 'autoplay; fullscreen; picture-in-picture; encrypted-media';
    frame.allowFullscreen = true;
    frame.addEventListener('load', () => this.el.classList.add('live'));
    frame.src = this.url;
    this.el.insertBefore(frame, this.el.firstChild);
    this.frame = frame;
  }

  stop() {
    if (this.frame) {
      this.frame.remove();
      this.frame = null;
    }
    this.el.classList.remove('live');
  }
}

const grid = document.getElementById('grid');
const form = document.getElementById('addresses');
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
    input.placeholder = 'https://player.livepush.io/live/...';
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
  const texts = fields.map((field) => {
    const url = livepushUrl(field.value);
    return url === null ? field.value.trim() : url;
  });
  texts.forEach((text, i) => { fields[i].value = text; });
  saveUrls(texts);
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

// Start-up splash: the logo animates and a drone flies past, then the splash fades (a click skips it)
const splash = document.getElementById('splash');
splash.insertBefore(makeLogo(null), splash.firstChild);
flyDrone(splash);
const hideSplash = () => splash.classList.add('done');
splash.addEventListener('click', hideSplash);
setTimeout(hideSplash, 2600);

const title = document.getElementById('title');
title.insertBefore(makeLogo(document.getElementById('header')), title.firstChild);

const saved = loadUrls();
buildAddressForm(saved);
for (let i = 0; i < STREAMS; i++) {
  tiles.push(new Tile(i, grid));
  tiles[i].setAddress(saved[i]);
}
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
