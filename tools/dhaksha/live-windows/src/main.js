// Dhaksha Live desktop: one window showing the stream grid; F11 toggles full screen.
const { app, BrowserWindow, Menu } = require('electron');
const path = require('path');

function createWindow() {
  const win = new BrowserWindow({
    width: 1400,
    height: 860,
    backgroundColor: '#121212',
    title: 'Dhaksha Live',
    autoHideMenuBar: true,
    webPreferences: {
      contextIsolation: true,
      nodeIntegration: false,
      sandbox: true,
    },
  });
  win.loadFile(path.join(__dirname, 'index.html'));
  win.webContents.on('before-input-event', (event, input) => {
    if (input.type === 'keyDown' && input.key === 'F11') {
      win.setFullScreen(!win.isFullScreen());
      event.preventDefault();
    }
  });
  // Links must never open new windows or replace the app page; the players stay in their boxes
  win.webContents.setWindowOpenHandler(() => ({ action: 'deny' }));
  win.webContents.on('will-navigate', (event) => event.preventDefault());
}

Menu.setApplicationMenu(null);
// The embedded Livepush players start by themselves, and keep playing when the window is in the background
app.commandLine.appendSwitch('autoplay-policy', 'no-user-gesture-required');
app.commandLine.appendSwitch('disable-renderer-backgrounding');
app.commandLine.appendSwitch('disable-background-timer-throttling');
app.whenReady().then(createWindow);
app.on('window-all-closed', () => app.quit());
