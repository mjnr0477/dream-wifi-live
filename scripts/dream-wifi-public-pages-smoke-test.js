"use strict";
const assert = require("node:assert/strict");
const fs = require("node:fs");
const path = require("node:path");

const publicDir = path.join(__dirname, "..", "public");
const read = name => fs.readFileSync(path.join(publicDir, name), "utf8");
const privacy = read("privacy.html");
const terms = read("terms.html");
const support = read("support.html");
const index = read("index.html");
const share = read("share.html");
const receive = read("receive.html");

for (const [name, html] of [["privacy", privacy], ["terms", terms], ["support", support]]) {
  assert.match(html, /<!doctype html>/i, name + " must be a standalone HTML document");
  assert.match(html, /<meta name="viewport"/i, name + " must be mobile-friendly");
  assert.match(html, /href="\/privacy\.html"/, name + " must link to privacy");
  assert.match(html, /href="\/terms\.html"/, name + " must link to terms");
  assert.match(html, /href="\/support\.html"/, name + " must link to support");
}
assert.match(privacy, /coordinates are sent to Dream Wi-Fi/i);
assert.match(privacy, /OpenStreetMap public Overpass/i);
assert.match(privacy, /hosting provider/i);
assert.match(privacy, /SHA-256 hash/i);
assert.match(terms, /own or have explicit permission/i);
assert.match(terms, /not implemented or verified/i);
assert.match(terms, /ICMP/i);
assert.match(support, /GitHub issues are public/i);
assert.match(support, /pairing codes/i);
for (const [name, html] of [["index", index], ["share", share], ["receive", receive]]) {
  assert.match(html, /href="\/privacy\.html"/, name + " page must expose privacy link");
  assert.match(html, /href="\/terms\.html"/, name + " page must expose terms link");
  assert.match(html, /href="\/support\.html"/, name + " page must expose support link");
}
console.log("Dream Wi-Fi privacy, terms, support, and navigation smoke test passed.");
