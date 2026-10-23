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
}
assert.match(privacy, /coordinates are sent to Dream Wi-Fi/i);
assert.match(privacy, /OpenStreetMap.{0,30}Overpass/i);
assert.match(privacy, /hosting provider/i);
assert.match(privacy, /SHA-256 hash/i);
assert.match(terms, /own it or have explicit permission/i);
assert.match(terms, /third-device hotspot forwarding/i);
assert.match(terms, /have not completed physical acceptance testing/i);
assert.match(terms, /ICMP/i);
assert.match(index, /Prototype testing notice/);
assert.match(receive, /Native Android VPN routing/);
assert.match(receive, /not implemented or verified/);
assert.match(support, /GitHub issues are public/i);
assert.match(support, /pairing codes/i);

assert.ok(privacy.includes('href="/terms.html"') && privacy.includes('href="/support.html"'));
assert.ok(terms.includes('href="/privacy.html"') && terms.includes('href="/support.html"'));
assert.ok(support.includes('href="/privacy.html"') && support.includes('href="/terms.html"'));
for (const [name, html] of [["index", index], ["share", share], ["receive", receive]]) {
  assert.ok(html.includes('href="/privacy.html"'), name + " must expose privacy link");
  assert.ok(html.includes('href="/terms.html"'), name + " must expose terms link");
  assert.ok(html.includes('href="/support.html"'), name + " must expose support link");
}
console.log("Dream Wi-Fi privacy, terms, support, and navigation smoke test passed.");
