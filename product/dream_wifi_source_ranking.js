"use strict";

// Rank only evidence-backed participating gateways. This does not measure bandwidth;
// internet_ready is a source-side HTTPS reachability probe, not a speed guarantee.
function sourcePriority(source) {
  if (!source || source.connected !== true) return 3;
  if (source.internet_ready === true) return 0;
  if (source.internet_ready === false) return 2;
  return 1;
}
function compareSourceCandidates(a, b) {
  return sourcePriority(a) - sourcePriority(b)
    || Number(a.distance_km || 0) - Number(b.distance_km || 0);
}
module.exports = { compareSourceCandidates, sourcePriority };
