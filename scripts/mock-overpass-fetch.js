"use strict";

const originalFetch = globalThis.fetch.bind(globalThis);
globalThis.fetch = async (input, init) => {
  const url = typeof input === "string" ? input : input && input.url;
  if (typeof url === "string" && url.startsWith("https://overpass-api.de/api/interpreter")) {
    return new Response(JSON.stringify({
      elements: [{
        type: "node",
        id: 901,
        lat: -0.56,
        lon: 34.56,
        tags: { name: "Integration Test Library", wifi: "yes", amenity: "library" }
      }]
    }), { status: 200, headers: { "content-type": "application/json" } });
  }
  return originalFetch(input, init);
};
