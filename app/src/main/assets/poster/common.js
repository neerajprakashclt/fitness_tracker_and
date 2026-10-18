// Formatting helpers shared by all pages.

const PACE_SPORTS = new Set(["running", "walking", "hiking"]);

function fmtDuration(sec) {
  if (sec == null) return "–";
  sec = Math.round(sec);
  const h = Math.floor(sec / 3600), m = Math.floor((sec % 3600) / 60), s = sec % 60;
  const mm = String(m).padStart(h ? 2 : 1, "0"), ss = String(s).padStart(2, "0");
  return h ? `${h}:${mm}:${ss}` : `${mm}:${ss}`;
}

// Units. Data is always stored and served in metric (m, m/s); only display converts.
// The choice is kept in localStorage, and toggling reloads the page so every
// table, chart and map re-renders in the new units.
const M_PER_MILE = 1609.344, M_PER_FOOT = 0.3048;

function loadUnits() {
  try { return localStorage.getItem("units") === "imperial" ? "imperial" : "metric"; }
  catch { return "metric"; }
}
const UNITS = loadUnits();
const IMPERIAL = UNITS === "imperial";

const DIST_UNIT = IMPERIAL ? "mi" : "km";
const ELEV_UNIT = IMPERIAL ? "ft" : "m";
const SPEED_UNIT = IMPERIAL ? "mph" : "km/h";
const PACE_UNIT = IMPERIAL ? "min/mi" : "min/km";

// Metres -> km or miles, metres -> m or feet, m/s -> km/h or mph.
const toDist = m => m / (IMPERIAL ? M_PER_MILE : 1000);
const toElev = m => IMPERIAL ? m / M_PER_FOOT : m;
const toSpeed = mps => mps * (IMPERIAL ? 3600 / M_PER_MILE : 3.6);
// Minutes per km or mile from m/s.
const toPaceMin = mps => (IMPERIAL ? M_PER_MILE : 1000) / mps / 60;

function fmtDistance(m) {
  return m == null ? "–" : toDist(m).toFixed(2) + " " + DIST_UNIT;
}

function fmtElevation(m) {
  return m == null ? "–" : Math.round(toElev(m)).toLocaleString() + " " + ELEV_UNIT;
}

// Pace as m:ss per km (or mile) from metres/second.
function fmtPace(speed) {
  if (!speed || speed < 0.3) return "–";
  const s = Math.round(toPaceMin(speed) * 60);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")} /${DIST_UNIT}`;
}

function fmtSpeed(speed) {
  return speed == null ? "–" : toSpeed(speed).toFixed(1) + " " + SPEED_UNIT;
}

// Pace without its unit ("5:39"), for ranges like "6:03-5:22 /km".
function fmtPaceBare(speed) {
  if (!speed || speed < 0.3) return "–";
  const s = Math.round(toPaceMin(speed) * 60);
  return `${Math.floor(s / 60)}:${String(s % 60).padStart(2, "0")}`;
}

// A pace-zone range from speed bounds (m/s): slower pace first. `low` is the slower bound.
function fmtPaceRange(b) {
  if (!b.low) return `slower than ${fmtPaceBare(b.high)} /${DIST_UNIT}`;
  if (b.high == null) return `faster than ${fmtPaceBare(b.low)} /${DIST_UNIT}`;
  return `${fmtPaceBare(b.low)}–${fmtPaceBare(b.high)} /${DIST_UNIT}`;
}

// Run types from run_types.py, each with a colour that is used everywhere.
const RUN_TYPES = {
  easy: { label: "Easy", color: "#74c0fc" },
  steady: { label: "Steady", color: "#51cf66" },
  tempo: { label: "Tempo", color: "#fcc419" },
  long: { label: "Long run", color: "#9775fa" },
  intervals: { label: "Intervals", color: "#fa5252" },
  unknown: { label: "Unlabelled", color: "#adb5bd" },
};

// A sentence saying why a run got its type, in the user's units.
function describeRunType(c) {
  const d = c.detail || {}, by = c.basis === "hr" ? " (by heart rate)" : "";
  const pct = x => Math.round(x * 100) + "%";
  switch (c.type) {
    case "intervals": return `${d.reps} hard reps of about ${fmtDuration(d.median_seconds)} at ${fmtPace(d.speed)}`;
    case "long": return d.typical_distance
      ? `${fmtDistance(d.distance)}, against a usual ${fmtDistance(d.typical_distance)}` : `${fmtDistance(d.distance)} or ${fmtHours(d.duration)}`;
    case "tempo": return `${pct(d.hard_share)} of the run at threshold pace or harder${by}`;
    case "easy": return `${pct(d.easy_share)} of the run below threshold pace${by}`;
    case "steady": return `a mix: ${pct(d.hard_share)} at threshold or harder${by}`;
    default: return "no intensity data to judge it by";
  }
}

// Google encoded polyline (precision 5) to [[lat, lon], ...].
function decodePolyline(str) {
  const out = [];
  let i = 0, lat = 0, lon = 0;
  while (i < str.length) {
    for (const which of [0, 1]) {
      let shift = 0, result = 0, b;
      do {
        b = str.charCodeAt(i++) - 63;
        result |= (b & 0x1f) << shift;
        shift += 5;
      } while (b >= 0x20);
      const delta = result & 1 ? ~(result >> 1) : result >> 1;
      if (which === 0) lat += delta; else lon += delta;
    }
    out.push([lat / 1e5, lon / 1e5]);
  }
  return out;
}

// Base maps for every Leaflet map in the app, with the standard layer switcher in the corner.
// All of these are free and need no key. Tile providers change their terms (CARTO's free tiles now
// answer every request with "API KEY REQUIRED"), so keep this list short, check any new source by
// looking at a real tile in a browser, and keep "None", which needs no provider at all.
// The choice is remembered across pages.
const MAP_LAYER_KEY = "mapLayer";
function addMapLayers(map, fallback = "Street") {
  const OSM = "https://tile.openstreetmap.org/{z}/{x}/{y}.png";
  const ESRI = "https://server.arcgisonline.com/ArcGIS/rest/services";
  const osm = '&copy; <a href="https://www.openstreetmap.org/copyright">OpenStreetMap</a> contributors';
  const esri = "Tiles &copy; Esri &mdash; Source: Esri, Maxar, Earthstar Geographics, and the GIS User Community";
  const tile = (url, options) => L.tileLayer(url, { maxZoom: 19, ...options });
  const imagery = () => tile(`${ESRI}/World_Imagery/MapServer/tile/{z}/{y}/{x}`, { attribution: esri });
  const layers = {
    "Dark": tile(OSM, { attribution: osm }),                       // OpenStreetMap, darkened by CSS (see style.css)
    "Street": tile(OSM, { attribution: osm }),
    "Terrain": tile("https://{s}.tile.opentopomap.org/{z}/{x}/{y}.png", {
      subdomains: "abc", maxNativeZoom: 17,
      attribution: 'Map data &copy; OpenStreetMap contributors, SRTM | Map style &copy; <a href="https://opentopomap.org">OpenTopoMap</a> (CC-BY-SA)' }),
    "Satellite": imagery(),
    "Hybrid": L.layerGroup([imagery(), tile(`${ESRI}/Reference/World_Boundaries_and_Places/MapServer/tile/{z}/{y}/{x}`, { attribution: esri })]),
    "Cycling and trails": tile("https://{s}.tile-cyclosm.openstreetmap.fr/cyclosm/{z}/{x}/{y}.png", {
      subdomains: "abc", attribution: `${osm}, <a href="https://www.cyclosm.org">CyclOSM</a>` }),
    "None (routes only)": L.layerGroup(),                           // no tile requests at all
  };
  let saved = null;
  try { saved = localStorage.getItem(MAP_LAYER_KEY); } catch { /* unavailable */ }
  const start = saved in layers ? saved : (fallback in layers ? fallback : "Street");

  const mark = name => {
    map.getPane("tilePane").classList.toggle("dark-tiles", name === "Dark");
    map.getContainer().classList.toggle("basemap-dark", name === "Dark");
    map.getContainer().classList.toggle("basemap-none", name === "None (routes only)");
  };
  layers[start].addTo(map);
  mark(start);
  L.control.layers(layers, null, { position: "topright" }).addTo(map);
  map.on("baselayerchange", e => {
    mark(e.name);
    try { localStorage.setItem(MAP_LAYER_KEY, e.name); } catch { /* private mode: not remembered */ }
  });
  return layers;
}

function setUnits(units) {
  try { localStorage.setItem("units", units); } catch { /* private mode: lasts until reload only */ }
  location.reload();
}

// Metric / Imperial switch, added to every page's header.
(function addUnitToggle() {
  const header = document.querySelector("header");
  if (!header) return;
  const box = document.createElement("div");
  box.className = "segmented unit-toggle";
  box.title = "Display units";
  box.innerHTML = `<button type="button" data-units="metric">km</button>
                   <button type="button" data-units="imperial">mi</button>`;
  box.querySelectorAll("button").forEach(b => {
    b.classList.toggle("active", b.dataset.units === UNITS);
    b.addEventListener("click", () => { if (b.dataset.units !== UNITS) setUnits(b.dataset.units); });
  });
  header.appendChild(box);
})();

function fmtDate(iso) {
  return new Date(iso).toLocaleString(undefined, {
    weekday: "short", year: "numeric", month: "short", day: "numeric",
    hour: "2-digit", minute: "2-digit",
  });
}

// Long totals: "12h 05m" (hours stay readable where 12:05:00 is not).
function fmtHours(sec) {
  if (!sec) return "–";
  const total = Math.round(sec / 60);          // round once, so 2h 59.6m becomes 3h 00m, never "2h 60m"
  const h = Math.floor(total / 60), m = total % 60;
  return h ? `${h}h ${String(m).padStart(2, "0")}m` : `${m}m`;
}

// Heart-rate zone colours, Z1 (easy) to Z5 (hardest).
const ZONE_COLORS = ["#74c0fc", "#51cf66", "#fcc419", "#ff922b", "#fa5252"];

function fmtZoneRange(b) {
  if (b.high == null) return `${b.low}+ bpm`;
  return b.low === 0 ? `< ${b.high} bpm` : `${b.low}–${b.high - 1} bpm`;
}

function fmtSport(s) {
  return (s || "unknown").replace(/_/g, " ");
}

function avgSpeed(a) {
  return a.distance && a.duration ? a.distance / a.duration : null;
}

const LOAD_METHOD = {
  power: "Scored from power vs FTP",
  hr: "Scored from heart rate (TRIMP)",
  duration: "No power/HR data: estimated from duration",
};

function fmtLoad(a) {
  if (a.load == null) return "–";
  return Math.round(a.load) + (a.load_method === "duration" ? "*" : "");
}

function escapeHtml(s) {
  return String(s ?? "").replace(/[&<>"']/g, c => (
    { "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" }[c]
  ));
}

function cssVar(name) {
  return getComputedStyle(document.documentElement).getPropertyValue(name).trim();
}
