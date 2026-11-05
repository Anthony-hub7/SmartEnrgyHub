const SERVICES = [
  { id: 'collector', label: 'Collector', icon: '↗' },
  { id: 'storage', label: 'Storage', icon: '▤' },
  { id: 'analytics', label: 'Analytics', icon: '⌁' },
  { id: 'sensor', label: 'Sensor registry', icon: '⌖' }
];
const $ = (selector) => document.querySelector(selector);
const state = { sensors: [], sensorId: '', measurements: [] };

async function api(path) {
  const response = await fetch(path, { headers: { Accept: 'application/json' } });
  if (!response.ok) throw new Error(`${response.status} ${response.statusText}`);
  return response.json();
}
function proxy(service, path) { return `/dashboard-api/${service}${path}`; }
function safe(value, digits = 2) { return Number.isFinite(Number(value)) ? Number(value).toFixed(digits) : '—'; }
function localTime(value) { if (!value) return '—'; const date = new Date(value); return Number.isNaN(date.valueOf()) ? '—' : date.toLocaleString(); }
function escapeHtml(value) { return String(value ?? '').replace(/[&<>"']/g, (ch) => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', '"': '&quot;', "'": '&#39;' }[ch])); }
function table(headers, rows, empty = 'Aucune donnée disponible.') {
  if (!rows.length) return `<div class="notice">${escapeHtml(empty)}</div>`;
  return `<table><thead><tr>${headers.map((h) => `<th>${h}</th>`).join('')}</tr></thead><tbody>${rows.join('')}</tbody></table>`;
}
function setView(id) {
  document.querySelectorAll('.tab').forEach((tab) => tab.setAttribute('aria-selected', String(tab.dataset.view === id)));
  document.querySelectorAll('.view').forEach((view) => view.classList.toggle('active', view.id === id));
  if (id === 'measurements') loadMeasurements();
  if (id === 'analytics') loadAnalytics();
}
function reportError(target, message) { const node = $(target); if (node) node.innerHTML = `<div class="notice error">${escapeHtml(message)}</div>`; }

async function loadHealth() {
  $('#health-grid').innerHTML = SERVICES.map((s) => `<article class="card service-card"><div class="card-top"><span class="label">${s.label}</span><span class="icon">${s.icon}</span></div><div class="status unknown" id="health-${s.id}">…</div><div class="detail" id="health-detail-${s.id}">Vérification en cours</div></article>`).join('');
  await Promise.all(SERVICES.map(async (service) => {
    const statusNode = $(`#health-${service.id}`), detail = $(`#health-detail-${service.id}`);
    try { const data = await api(proxy(service.id, '/actuator/health')); const up = data.status === 'UP'; statusNode.textContent = data.status || 'UNKNOWN'; statusNode.className = `status ${up ? 'up' : 'down'}`; detail.textContent = 'Réponse reçue'; }
    catch (error) { statusNode.textContent = 'DOWN'; statusNode.className = 'status down'; detail.textContent = error.message === 'Failed to fetch' ? 'Service indisponible' : error.message; }
  }));
}
async function loadSensors() {
  try {
    const page = await api(proxy('sensor', '/api/sensors?page=0&size=100'));
    state.sensors = Array.isArray(page.items) ? page.items : [];
    if (!state.sensorId || !state.sensors.some((s) => s.sensorId === state.sensorId)) state.sensorId = state.sensors[0]?.sensorId || '';
    const options = state.sensors.map((s) => `<option value="${escapeHtml(s.sensorId)}">${escapeHtml(s.sensorId)}${s.name ? ` · ${escapeHtml(s.name)}` : ''}</option>`).join('');
    ['#measurement-sensor', '#analytics-sensor'].forEach((selector) => { const select = $(selector); const old = select.value || state.sensorId; select.innerHTML = options || '<option value="">Aucun capteur</option>'; select.value = state.sensors.some((s) => s.sensorId === old) ? old : state.sensorId; });
    renderSensors(); renderOverviewMetrics();
    return state.sensors;
  } catch (error) { reportError('#sensor-table', `Impossible de charger les capteurs : ${error.message}`); reportError('#overview-metrics', `Impossible de charger les capteurs : ${error.message}`); return []; }
}
function renderSensors() {
  const rows = state.sensors.map((s) => `<tr><td><strong>${escapeHtml(s.sensorId)}</strong></td><td>${escapeHtml(s.name || '—')}</td><td>${escapeHtml(s.building || '—')}</td><td>${escapeHtml(s.floor ?? '—')}</td><td>${s.maxPowerKw == null ? '—' : `${safe(s.maxPowerKw)} kW`}</td><td><span class="pill ${s.active ? '' : 'off'}">${s.active ? 'Actif' : 'Inactif'}</span></td><td>${escapeHtml(Array.isArray(s.tags) ? s.tags.join(', ') : '—')}</td></tr>`);
  $('#sensor-table').innerHTML = table(['Identifiant', 'Nom', 'Bâtiment', 'Étage', 'Seuil', 'État', 'Tags'], rows, 'Aucun capteur enregistré.');
}
function renderOverviewMetrics() {
  const total = state.sensors.length, active = state.sensors.filter((s) => s.active).length;
  $('#overview-metrics').innerHTML = `<article class="card metric"><div class="label">CAPTEURS ENREGISTRÉS</div><div class="value">${total}</div><div class="caption">Dans le registre</div></article><article class="card metric"><div class="label">CAPTEURS ACTIFS</div><div class="value">${active}</div><div class="caption">${total ? `${Math.round(active / total * 100)}% du parc` : 'Aucun capteur déclaré'}</div></article><article class="card metric"><div class="label">CAPTEUR SUIVI</div><div class="value" style="font-size:20px">${escapeHtml(state.sensorId || '—')}</div><div class="caption">Sélection pour l’historique</div></article>`;
}
async function loadMeasurements() {
  if (!state.sensorId) { $('#measurement-table').innerHTML = '<div class="notice">Enregistrez un capteur pour afficher ses mesures.</div>'; return; }
  const id = encodeURIComponent($('#measurement-sensor').value || state.sensorId); state.sensorId = decodeURIComponent(id);
  try {
    const history = await api(proxy('storage', `/api/measurements/${id}?limit=50`));
    state.measurements = Array.isArray(history.items) ? history.items : [];
    renderMeasurements();
  } catch (error) { reportError('#measurement-table', `Impossible de charger les mesures : ${error.message}`); $('#latest-reading').innerHTML = `<span class="error">${escapeHtml(error.message)}</span>`; }
}
function renderMeasurements() {
  const items = state.measurements;
  const rows = items.map((m) => `<tr><td>${escapeHtml(localTime(m.timestamp))}</td><td>${safe(m.powerKw)} kW</td><td>${m.voltage == null ? '—' : `${safe(m.voltage, 1)} V`}</td><td>${m.temperature == null ? '—' : `${safe(m.temperature, 1)} °C`}</td><td>${escapeHtml(m.state || '—')}</td></tr>`);
  $('#measurement-table').innerHTML = table(['Horodatage', 'Puissance', 'Tension', 'Température', 'État'], rows, `Aucune mesure pour ${state.sensorId}.`);
  const latest = items[0]; $('#latest-reading').innerHTML = latest ? `<div class="reading"><span>Puissance</span><b>${safe(latest.powerKw)} kW</b></div><div class="reading"><span>Tension</span><b>${latest.voltage == null ? '—' : `${safe(latest.voltage, 1)} V`}</b></div><div class="reading"><span>Température</span><b>${latest.temperature == null ? '—' : `${safe(latest.temperature, 1)} °C`}</b></div><div class="reading"><span>État</span><b>${escapeHtml(latest.state || '—')}</b></div><div class="reading"><span>Horodatage</span><b>${escapeHtml(localTime(latest.timestamp))}</b></div>` : '<span class="detail">Aucune mesure reçue.</span>';
  $('#chart-value').innerHTML = latest ? `${safe(latest.powerKw)} <span class="unit">kW</span>` : '—';
  $('#chart-caption').textContent = `${items.length} mesure(s) · ${state.sensorId}`;
  drawChart(items);
  $('#overview-table').innerHTML = table(['Horodatage', 'Capteur', 'Puissance', 'Tension', 'État'], items.slice(0, 6).map((m) => `<tr><td>${escapeHtml(localTime(m.timestamp))}</td><td>${escapeHtml(state.sensorId)}</td><td>${safe(m.powerKw)} kW</td><td>${m.voltage == null ? '—' : `${safe(m.voltage, 1)} V`}</td><td>${escapeHtml(m.state || '—')}</td></tr>`), 'Aucune mesure reçue.');
}
function drawChart(items) {
  const canvas = $('#chart'), rect = canvas.getBoundingClientRect(), scale = window.devicePixelRatio || 1;
  canvas.width = Math.max(1, rect.width * scale); canvas.height = Math.max(1, rect.height * scale);
  const ctx = canvas.getContext('2d'); ctx.scale(scale, scale); const w = rect.width, h = rect.height, values = items.map((m) => Number(m.powerKw)).filter(Number.isFinite).reverse();
  ctx.clearRect(0, 0, w, h); if (!values.length) { ctx.fillStyle = '#9eaea1'; ctx.font = '12px system-ui'; ctx.fillText('Aucune donnée à représenter', 8, h / 2); return; }
  const pad = { l: 8, r: 8, t: 14, b: 20 }, max = Math.max(...values, 1) * 1.15, min = Math.min(0, ...values), x = (i) => pad.l + i * (w - pad.l - pad.r) / Math.max(values.length - 1, 1), y = (v) => h - pad.b - (v - min) / (max - min || 1) * (h - pad.t - pad.b);
  ctx.strokeStyle = '#2b3a30'; ctx.lineWidth = 1; for (let i = 0; i < 4; i++) { const gy = pad.t + i * (h - pad.t - pad.b) / 3; ctx.beginPath(); ctx.moveTo(pad.l, gy); ctx.lineTo(w - pad.r, gy); ctx.stroke(); }
  const gradient = ctx.createLinearGradient(0, pad.t, 0, h - pad.b); gradient.addColorStop(0, '#a7ed6740'); gradient.addColorStop(1, '#a7ed6700'); ctx.beginPath(); values.forEach((v, i) => i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))); ctx.lineTo(x(values.length - 1), h - pad.b); ctx.lineTo(x(0), h - pad.b); ctx.closePath(); ctx.fillStyle = gradient; ctx.fill();
  ctx.beginPath(); values.forEach((v, i) => i ? ctx.lineTo(x(i), y(v)) : ctx.moveTo(x(i), y(v))); ctx.strokeStyle = '#a7ed67'; ctx.lineWidth = 2; ctx.lineJoin = 'round'; ctx.stroke();
}
function kv(label, value) { return `<div class="kv"><span>${escapeHtml(label)}</span><b>${escapeHtml(value)}</b></div>`; }
function renderSummaryCard(selector, title, values, error) { $(selector).innerHTML = `<h3>${title}</h3>${error ? `<div class="notice">${escapeHtml(error)}</div>` : values.map(([k,v]) => kv(k,v)).join('') || '<div class="notice">Aucune donnée disponible.</div>'}`; }
async function loadAnalytics() {
  const id = $('#analytics-sensor').value || state.sensorId; if (!id) { ['#summary-card','#daily-card','#anomaly-card','#exceed-card'].forEach((s) => renderSummaryCard(s, s === '#summary-card' ? 'Résumé' : '—', [], 'Aucun capteur disponible.')); return; }
  const encoded = encodeURIComponent(id);
  const [summary, daily, anomalies, exceedances] = await Promise.allSettled([
    api(proxy('analytics', `/api/analytics/${encoded}/summary`)), api(proxy('analytics', `/api/analytics/${encoded}/daily-summary`)),
    api(proxy('analytics', `/api/analytics/${encoded}/anomalies?page=0&size=5`)), api(proxy('analytics', '/api/analytics/exceedances?threshold=10.0'))
  ]);
  if (summary.status === 'fulfilled') { const s = summary.value; renderSummaryCard('#summary-card', 'Résumé de la période', [['Puissance moyenne', `${safe(s.avgPowerKw)} kW`], ['Min / max', `${safe(s.minPowerKw)} / ${safe(s.maxPowerKw)} kW`], ['Énergie', `${safe(s.energyKwh)} kWh`], ['Nombre de mesures', s.count ?? '—']]); } else renderSummaryCard('#summary-card', 'Résumé de la période', [], 'Aucune agrégation disponible.');
  if (daily.status === 'fulfilled') { const d = daily.value; renderSummaryCard('#daily-card', 'Résumé quotidien', [['Date', d.date || '—'], ['Puissance moyenne', `${safe(d.avgPowerKw)} kW`], ['Énergie', `${safe(d.energyKwh)} kWh`], ['Mesures', d.count ?? '—']]); } else renderSummaryCard('#daily-card', 'Résumé quotidien', [], 'Aucune donnée pour aujourd’hui.');
  if (anomalies.status === 'fulfilled') { const list = anomalies.value.items || []; renderSummaryCard('#anomaly-card', 'Anomalies récentes', list.map((a) => [`${a.type || 'Anomalie'} · ${localTime(a.timestamp)}`, `${a.severity || '—'}${a.powerKw == null ? '' : ` · ${safe(a.powerKw)} kW`}`])); } else renderSummaryCard('#anomaly-card', 'Anomalies récentes', [], 'Aucune anomalie disponible.');
  if (exceedances.status === 'fulfilled') { const match = exceedances.value.find((e) => e.sensorId === id); renderSummaryCard('#exceed-card', 'Dépassements de seuil', match ? [['État', 'Seuil dépassé'], ['Dernière puissance', `${safe(match.lastPowerKw ?? match.maxPowerKw)} kW`], ['Seuil', `${safe(match.thresholdKw ?? 10)} kW`]] : [['État', 'Aucun dépassement'], ['Seuil configuré', '10.00 kW']]); } else renderSummaryCard('#exceed-card', 'Dépassements de seuil', [], 'Indicateur indisponible.');
}
async function refreshAll() {
  $('#updated').textContent = 'Actualisation…';
  await Promise.all([loadHealth(), loadSensors()]);
  if (state.sensorId) await loadMeasurements();
  if ($('#analytics').classList.contains('active')) await loadAnalytics();
  $('#updated').textContent = `Mis à jour à ${new Date().toLocaleTimeString()}`;
}
document.querySelectorAll('.tab').forEach((tab) => tab.addEventListener('click', () => setView(tab.dataset.view)));
document.querySelectorAll('[data-open]').forEach((button) => button.addEventListener('click', () => setView(button.dataset.open)));
document.querySelectorAll('[data-action="refresh"]').forEach((button) => button.addEventListener('click', refreshAll));
$('#refresh').addEventListener('click', refreshAll);
$('#measurement-sensor').addEventListener('change', loadMeasurements);
$('#analytics-sensor').addEventListener('change', loadAnalytics);
window.addEventListener('resize', () => drawChart(state.measurements));
refreshAll(); setInterval(refreshAll, 10000);
