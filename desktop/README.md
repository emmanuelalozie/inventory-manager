# Inventix Desktop

A desktop client for the Inventix REST API, built with [Tauri 2](https://v2.tauri.app/) and plain HTML, CSS and
JavaScript. There is no framework, bundler or TypeScript: the files in `src\` are loaded exactly as they are.

It covers:

- **Dashboard:** product and order counts, stock value, a low-stock list with a quick restock button, orders by
  status and recent orders.
- **Products:** search, create, edit, delete and adjust stock, with form checks that match the backend rules.
- **Orders:** list and filter by status, create an order from a product picker, view an order's items and total,
  add items, change item quantities, remove items, change the status (ship, deliver, cancel) and delete orders.
- **Settings:** the backend URL (default `http://localhost:8080`) and the low-stock threshold, saved in the
  app's local storage, plus a connection test.

Errors from the backend (the JSON error body described in `..\docs\API.md`) appear as toasts, or next to the
fields they belong to in forms. If the backend can't be reached, a banner says so and shows the URL it tried.

## Layout

```
desktop\
├── package.json            Tauri CLI (npm run dev / build), dev server (npm run serve), npm test
├── scripts\
│   ├── dev-server.js       Static server for src\ on http://127.0.0.1:1420 (no dependencies)
│   └── dev-server.test.js  Tests for it, and a check that the port matches tauri.conf.json and CORS
├── src\                    The UI (served to the webview as-is)
│   ├── index.html
│   ├── styles.css
│   └── js\
│       ├── main.js         Routing, connection banner, settings
│       ├── api.js          fetch wrapper, base URL, all endpoints
│       ├── ui.js           Toasts, dialogs, formatting, form errors
│       ├── settings.js     Low-stock threshold preference
│       ├── dashboard.js
│       ├── products.js
│       └── orders.js
└── src-tauri\              The Rust shell around the webview
    ├── Cargo.toml
    ├── build.rs
    ├── tauri.conf.json
    ├── capabilities\default.json
    ├── icons\              Generated with `npx tauri icon` (see below)
    └── src\main.rs, lib.rs
```

## Prerequisites (Windows)

1. **Microsoft C++ Build Tools.** Install
   [Build Tools for Visual Studio](https://visualstudio.microsoft.com/visual-cpp-build-tools/) and tick the
   **Desktop development with C++** workload.
2. **WebView2.** Already included in Windows 10 (1803 and later) and Windows 11. If it's missing, install the
   Evergreen Bootstrapper from [Microsoft](https://developer.microsoft.com/microsoft-edge/webview2/).
3. **Rust** through rustup, using the MSVC toolchain:

   ```powershell
   winget install --id Rustlang.Rustup
   # open a new terminal, then:
   rustup default stable-msvc
   ```

4. **Node.js LTS** (for the Tauri CLI):

   ```powershell
   winget install OpenJS.NodeJS.LTS
   ```

5. **Java 17** for the backend.

## Run it

1. **Start the backend** from the repository root (`inventory-manager\`) and leave it running:

   ```powershell
   .\gradlew.bat bootRun
   ```

   It listens on `http://localhost:8080` and adds five sample products on startup. The database is in memory, so
   data is lost when it stops.

2. **Install the Tauri CLI** in a second terminal:

   ```powershell
   cd desktop
   npm install
   ```

3. **Create the app icons** (only needed once). Tauri needs them even in development, and they are binary files
   that aren't checked in. Use any square PNG of at least 1024×1024:

   ```powershell
   npx tauri icon path\to\logo.png
   ```

   This fills `src-tauri\icons\`. See `src-tauri\icons\README.md`.

4. **Run the app in development:**

   ```powershell
   npm run dev
   ```

   The first run compiles the Rust side, which takes a few minutes.

   `tauri.conf.json` sets `devUrl` to `http://127.0.0.1:1420` and `beforeDevCommand` to `npm run serve`, which
   starts `scripts\dev-server.js` (a small static server with no dependencies) on that fixed port. The backend
   allows that origin through CORS. If port 1420 is already in use the server stops with an error instead of
   moving to another port, because any other port would be blocked by CORS. The **Settings** page shows the
   window's current origin if you need to check.

5. **Build an installer:**

   ```powershell
   npm run build
   ```

   The installers are written to `src-tauri\target\release\bundle\` (`msi\` and `nsis\`). Building the MSI needs
   the Windows VBSCRIPT optional feature. If it's missing, enable it under **Settings → System → Optional
   features**.

## Test in a normal browser

The UI doesn't need Tauri for any feature, so you can also open it in a browser. ES modules don't load from
`file://`, so serve the `src\` folder over HTTP on a port the backend allows through CORS (1420 or 5500):

- **The included server:** run `npm run serve` in `desktop\` and open `http://127.0.0.1:1420/`.
- **VS Code Live Server:** open `desktop\src\index.html` and click **Go Live** (it uses port 5500 by default).
- **Or Python:**

  ```powershell
  cd desktop\src
  python -m http.server 5500
  ```

  Then open `http://localhost:5500/`.

Other ports are blocked by the backend's CORS settings (`src\main\java\com\example\inventix\config\WebConfig.java`).

## Configuration notes

- **Backend URL.** Change it in **Settings**. It's stored per origin in local storage, so the desktop app and
  the browser keep separate values.
- **Content Security Policy.** `tauri.conf.json` sets a CSP whose `connect-src` allows `http://localhost` and
  `http://127.0.0.1` on any port (including `8080`), plus Tauri's IPC. If you point the app at a backend on another
  host, add that host to `connect-src`, and add the app's origin to the backend's CORS list.
- **No external resources.** Everything (fonts, styles, scripts) is local, so the app works offline apart from
  the backend itself.
- **`window.__TAURI__`** is enabled (`app.withGlobalTauri`), but the UI only uses it to show where it is running.
