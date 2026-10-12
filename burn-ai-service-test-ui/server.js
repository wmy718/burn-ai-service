const http = require("http");
const fs = require("fs");
const path = require("path");

const PORT = Number(process.env.TEST_UI_PORT || 5179);
const BACKEND = (process.env.BURN_AI_BACKEND || "http://127.0.0.1:8091").replace(/\/$/, "");
const ROOT = __dirname;

const TYPES = {
  ".html": "text/html; charset=utf-8",
  ".css": "text/css; charset=utf-8",
  ".js": "text/javascript; charset=utf-8",
  ".json": "application/json; charset=utf-8",
  ".svg": "image/svg+xml; charset=utf-8",
};

function serveStatic(req, res) {
  const url = new URL(req.url, "http://localhost");
  const filePath = path.join(ROOT, url.pathname === "/" ? "index.html" : decodeURIComponent(url.pathname));
  if (!filePath.startsWith(ROOT)) {
    res.writeHead(403).end("Forbidden");
    return;
  }
  fs.readFile(filePath, (error, data) => {
    if (error) {
      res.writeHead(404).end("Not found");
      return;
    }
    res.writeHead(200, {
      "content-type": TYPES[path.extname(filePath)] || "application/octet-stream",
      "cache-control": "no-store",
    });
    res.end(data);
  });
}

function proxy(req, res) {
  const target = new URL(req.url, BACKEND);
  const headers = { ...req.headers, host: target.host };
  delete headers.connection;

  const request = http.request(
    {
      protocol: target.protocol,
      hostname: target.hostname,
      port: target.port || 80,
      method: req.method,
      path: target.pathname + target.search,
      headers,
    },
    backendRes => {
      const responseHeaders = { ...backendRes.headers };
      responseHeaders["access-control-allow-origin"] = "*";
      responseHeaders["cache-control"] = "no-store";
      res.writeHead(backendRes.statusCode || 502, responseHeaders);
      backendRes.pipe(res);
    }
  );

  request.on("error", error => {
    res.writeHead(502, { "content-type": "application/json; charset=utf-8" });
    res.end(JSON.stringify({ error: "BACKEND_UNAVAILABLE", message: error.message, backend: BACKEND }));
  });
  req.pipe(request);
}

http
  .createServer((req, res) => {
    if (req.method === "OPTIONS") {
      res.writeHead(204, {
        "access-control-allow-origin": "*",
        "access-control-allow-methods": "GET,POST,OPTIONS",
        "access-control-allow-headers": "content-type",
      });
      res.end();
      return;
    }
    if (req.url.startsWith("/api/")) {
      proxy(req, res);
      return;
    }
    serveStatic(req, res);
  })
  .listen(PORT, () => {
    console.log(`Burn AI test UI: http://127.0.0.1:${PORT}`);
    console.log(`Proxy backend: ${BACKEND}`);
  });
