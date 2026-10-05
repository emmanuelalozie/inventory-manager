// Static file server for development: serves ../src on http://127.0.0.1:1420.
// No dependencies, only Node built-ins. Used by `npm run serve` and by Tauri's beforeDevCommand.
//
// The port is fixed on purpose: the backend's CORS list (config/WebConfig.java) allows
// http://127.0.0.1:1420 and http://localhost:1420, so the UI must always be served from there.
// If the port is taken the server stops with an error instead of silently moving to another port.

"use strict";

const http = require("http");
const fs = require("fs");
const path = require("path");

const HOST = "127.0.0.1";
const PORT = 1420;
const ROOT = path.resolve(__dirname, "..", "src");

const CONTENT_TYPES = {
  ".html": "text/html; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".mjs": "text/javascript; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml",
  ".png": "image/png",
  ".jpg": "image/jpeg",
  ".jpeg": "image/jpeg",
  ".gif": "image/gif",
  ".ico": "image/x-icon",
  ".woff": "font/woff",
  ".woff2": "font/woff2",
  ".txt": "text/plain; charset=utf-8",
};

/** Maps a request URL to a file inside ROOT, or null if it points outside it. */
function resolvePath(requestUrl) {
  let pathname;
  try {
    pathname = decodeURIComponent(new URL(requestUrl, `http://${HOST}`).pathname);
  } catch {
    return null;
  }
  if (pathname.endsWith("/")) pathname += "index.html";
  const filePath = path.resolve(ROOT, "." + pathname);
  if (filePath !== ROOT && !filePath.startsWith(ROOT + path.sep)) return null;
  return filePath;
}

function send(res, status, body, contentType = "text/plain; charset=utf-8") {
  res.writeHead(status, { "Content-Type": contentType, "Cache-Control": "no-store" });
  res.end(body);
}

function createServer() {
  return http.createServer((req, res) => {
    if (req.method !== "GET" && req.method !== "HEAD") {
      send(res, 405, "Method Not Allowed");
      return;
    }
    const filePath = resolvePath(req.url);
    if (!filePath) {
      send(res, 403, "Forbidden");
      return;
    }
    fs.stat(filePath, (statError, stats) => {
      if (statError || !stats.isFile()) {
        send(res, 404, "Not Found");
        return;
      }
      const type = CONTENT_TYPES[path.extname(filePath).toLowerCase()] || "application/octet-stream";
      res.writeHead(200, { "Content-Type": type, "Content-Length": stats.size, "Cache-Control": "no-store" });
      if (req.method === "HEAD") {
        res.end();
        return;
      }
      fs.createReadStream(filePath).pipe(res);
    });
  });
}

function start() {
  const server = createServer();
  server.on("error", (error) => {
    if (error.code === "EADDRINUSE") {
      console.error(
        `Port ${PORT} on ${HOST} is already in use. Stop the other process: the UI must run on port ${PORT} ` +
          "because that is the origin the backend allows through CORS."
      );
    } else {
      console.error(error);
    }
    process.exit(1);
  });
  server.listen(PORT, HOST, () => {
    console.log(`Inventix UI: serving ${ROOT} at http://${HOST}:${PORT}/`);
  });
  return server;
}

if (require.main === module) {
  start();
}

module.exports = { createServer, resolvePath, HOST, PORT, ROOT };
