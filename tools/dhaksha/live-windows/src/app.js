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
    const name = document.createElement('div');
    name.className = 'name';
    name.textContent = 'DHAKSHA';
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
