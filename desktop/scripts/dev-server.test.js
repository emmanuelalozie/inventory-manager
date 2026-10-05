// Tests for the development server and for keeping its origin in sync with Tauri and the backend CORS list.
// Run with: npm test (from desktop\), or: node --test scripts/dev-server.test.js

"use strict";

const test = require("node:test");
const assert = require("node:assert");
const fs = require("fs");
const http = require("http");
const path = require("path");

const { createServer, resolvePath, HOST, PORT, ROOT } = require("./dev-server.js");

const DESKTOP = path.resolve(__dirname, "..");
const WEB_CONFIG = path.resolve(
  DESKTOP, "..", "src", "main", "java", "com", "example", "inventix", "config", "WebConfig.java"
);

function get(port, urlPath) {
  return new Promise((resolve, reject) => {
    http
      .get({ host: HOST, port, path: urlPath }, (res) => {
        let body = "";
        res.setEncoding("utf8");
        res.on("data", (chunk) => (body += chunk));
        res.on("end", () => resolve({ status: res.statusCode, type: res.headers["content-type"], body }));
      })
      .on("error", reject);
  });
}

test("serves the UI files with the right content types", async (t) => {
  const server = createServer();
  await new Promise((resolve) => server.listen(0, HOST, resolve));
  t.after(() => server.close());
  const { port } = server.address();

  const index = await get(port, "/");
  assert.strictEqual(index.status, 200);
  assert.match(index.type, /^text\/html/);
  assert.match(index.body, /<html/i);

  // ES modules only load with a JavaScript MIME type.
  const script = await get(port, "/js/main.js");
  assert.strictEqual(script.status, 200);
  assert.match(script.type, /^text\/javascript/);

  const missing = await get(port, "/does-not-exist.js");
  assert.strictEqual(missing.status, 404);
});

test("never resolves paths outside src", () => {
  assert.strictEqual(resolvePath("/"), path.join(ROOT, "index.html"));
  assert.strictEqual(resolvePath("/js/api.js"), path.join(ROOT, "js", "api.js"));
  // Each attempt must be rejected (null) or stay inside src (the URL parser may normalize "..").
  for (const attempt of [
    "/../package.json",
    "/%2e%2e/package.json",
    "/..%5c..%5cpackage.json",
    "/%2e%2e%2f%2e%2e%2fbuild.gradle",
    "/js/..%2f..%2f..%2fbuild.gradle",
  ]) {
    const resolved = resolvePath(attempt);
    assert.ok(
      resolved === null || resolved.startsWith(ROOT + path.sep),
      `${attempt} resolved outside src: ${resolved}`
    );
  }
});

test("Tauri devUrl points at the dev server", () => {
  const conf = JSON.parse(fs.readFileSync(path.join(DESKTOP, "src-tauri", "tauri.conf.json"), "utf8"));
  assert.strictEqual(conf.build.devUrl, `http://${HOST}:${PORT}`);
  assert.strictEqual(conf.build.beforeDevCommand, "npm run serve");
  assert.strictEqual(conf.build.frontendDist, "../src");
});

test("the backend CORS list allows the dev server and Tauri origins", () => {
  const source = fs.readFileSync(WEB_CONFIG, "utf8");
  for (const origin of [
    `http://${HOST}:${PORT}`,
    `http://localhost:${PORT}`,
    "http://tauri.localhost",
    "https://tauri.localhost",
    "tauri://localhost",
  ]) {
    assert.ok(source.includes(`"${origin}"`), `WebConfig.java should allow ${origin}`);
  }
});
