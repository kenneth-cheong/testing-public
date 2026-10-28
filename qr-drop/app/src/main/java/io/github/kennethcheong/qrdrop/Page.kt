package io.github.kennethcheong.qrdrop

/**
 * The page the iPhone opens from the QR code. It says it's connected (and pings the
 * phone to keep saying so), uploads up to three files at once with a bar each and
 * one for the batch, and ends on a "saved" card with Retry and Send more.
 */
fun sendPage(deviceName: String): String {
    val name = deviceName.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
    return """<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1, viewport-fit=cover">
<meta name="color-scheme" content="light dark">
<title>Send to $name</title>
<style>
  :root { --bg:#f5f6fb; --card:#fff; --text:#14161f; --muted:#6b7080; --accent:#3d4bd8; --ok:#1f9d63; --okbg:rgba(31,157,99,.12); --warn:#b7791f; --warnbg:rgba(183,121,31,.13); --err:#d64545; --track:#e6e8f2; }
  @media (prefers-color-scheme: dark) { :root { --bg:#0d0f16; --card:#171a24; --text:#eef0f7; --muted:#9097aa; --accent:#7b86ff; --ok:#4fd39a; --okbg:rgba(79,211,154,.13); --warn:#f2b451; --warnbg:rgba(242,180,81,.13); --err:#ff7a7a; --track:#262a38; } }
  * { box-sizing:border-box; }
  [hidden] { display:none !important; }
  body { margin:0; font:17px/1.4 -apple-system, system-ui, sans-serif; background:var(--bg); color:var(--text);
         padding:calc(env(safe-area-inset-top) + 24px) 18px calc(env(safe-area-inset-bottom) + 28px); }
  main { max-width:520px; margin:0 auto; }
  .eyebrow { font-size:13px; letter-spacing:.08em; text-transform:uppercase; color:var(--muted); margin:0 0 4px; }
  h1 { font-size:28px; margin:0 0 12px; letter-spacing:-.01em; }
  .conn { display:inline-flex; align-items:center; gap:8px; font-size:14px; font-weight:600; padding:6px 12px 6px 10px; border-radius:99px;
          background:var(--okbg); color:var(--ok); margin-bottom:22px; }
  .conn i { width:8px; height:8px; border-radius:50%; background:currentColor; animation:pulse 2s infinite; }
  .conn.lost { background:var(--warnbg); color:var(--warn); }
  .conn.lost i { animation:none; }
  @keyframes pulse { 0%, 100% { opacity:1; } 50% { opacity:.35; } }
  label.pick { display:flex; align-items:center; justify-content:center; gap:10px; width:100%; padding:18px; border-radius:16px;
               background:var(--accent); color:#fff; font-weight:600; font-size:18px; cursor:pointer; }
  label.pick svg { width:22px; height:22px; }
  input[type=file] { display:none; }
  .hint { color:var(--muted); font-size:14px; text-align:center; margin:10px 0 22px; }
  .card { background:var(--card); border-radius:18px; padding:16px 16px 18px; box-shadow:0 1px 2px rgba(0,0,0,.06); margin-bottom:14px; }
  .overall .row { display:flex; justify-content:space-between; align-items:baseline; gap:12px; }
  .overall b { font-size:17px; }
  .overall span { color:var(--muted); font-size:14px; }
  .overall .bar { height:8px; border-radius:4px; margin-top:12px; }
  .done { text-align:center; padding:26px 18px 20px; }
  .done svg { width:64px; height:64px; display:block; margin:0 auto 12px; }
  .done svg circle { fill:var(--okbg); }
  .done svg path { fill:none; stroke:var(--ok); stroke-width:3.2; stroke-linecap:round; stroke-linejoin:round; stroke-dasharray:30; stroke-dashoffset:30; animation:draw .45s .1s ease-out forwards; }
  .done.bad svg circle { fill:var(--warnbg); } .done.bad svg path { stroke:var(--warn); }
  @keyframes draw { to { stroke-dashoffset:0; } }
  .done h2 { font-size:21px; margin:0 0 4px; letter-spacing:-.01em; }
  .done p { margin:0; color:var(--muted); font-size:14px; }
  .actions { display:flex; gap:10px; justify-content:center; margin-top:16px; flex-wrap:wrap; }
  .actions button, .actions label { font:inherit; font-size:15px; font-weight:600; border:0; border-radius:12px; padding:10px 16px; cursor:pointer; }
  .actions .primary { background:var(--accent); color:#fff; }
  .actions .ghost { background:var(--track); color:var(--text); }
  ul { list-style:none; margin:0; padding:0; display:grid; gap:10px; }
  li { background:var(--card); border-radius:14px; padding:12px 14px; box-shadow:0 1px 2px rgba(0,0,0,.06); }
  .row { display:flex; justify-content:space-between; gap:12px; align-items:baseline; }
  .name { font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .state { font-size:14px; color:var(--muted); flex:none; }
  .state.ok { color:var(--ok); } .state.err { color:var(--err); }
  .bar { height:5px; border-radius:3px; background:var(--track); margin-top:9px; overflow:hidden; }
  .bar > i { display:block; height:100%; width:0; background:var(--accent); transition:width .15s; }
  li.done .bar > i { background:var(--ok); } li.failed .bar > i { background:var(--err); }
</style>
</head>
<body>
<main>
  <p class="eyebrow">QR Drop</p>
  <h1>Send to $name</h1>
  <div class="conn" id="conn"><i></i><span id="connText">Connected to $name</span></div>

  <section class="card done" id="done" hidden>
    <svg viewBox="0 0 64 64" aria-hidden="true"><circle cx="32" cy="32" r="30"/><path id="doneMark" d="M20 33l8 8 16-17"/></svg>
    <h2 id="doneTitle"></h2>
    <p id="doneText"></p>
    <div class="actions">
      <button class="ghost" id="retry" hidden>Retry failed</button>
      <label class="primary" for="files">Send more</label>
    </div>
  </section>

  <div id="picker">
    <label class="pick" for="files">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V6M6 11l6-6 6 6"/></svg>
      Choose photos or files
    </label>
    <p class="hint">Files are saved to Downloads › QR Drop on $name.</p>
  </div>
  <input id="files" type="file" multiple>

  <section class="card overall" id="overall" hidden>
    <div class="row"><b id="overallTitle"></b><span id="overallBytes"></span></div>
    <div class="bar"><i id="overallBar"></i></div>
  </section>

  <ul id="list"></ul>
</main>
<script>
const NAME = document.querySelector('h1').textContent.replace(/^Send to /, '');
const el = id => document.getElementById(id);
const list = el('list');
// A few at a time keeps the link busy between files (and through small photos) without starving any one file.
const PARALLEL = 3;
let batch = [], queue = [], active = 0;

function fmt(bytes) {
  if (bytes < 1024) return bytes + ' B';
  const units = ['KB', 'MB', 'GB']; let v = bytes / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return v.toFixed(v < 10 ? 1 : 0) + ' ' + units[i];
}
const plural = (n, one) => n + ' ' + one + (n === 1 ? '' : 's');

el('files').addEventListener('change', e => {
  if (!active && !queue.length) batch = []; // a new round once the last one has finished
  const rows = document.createDocumentFragment(); // newest batch on top, in the order chosen
  for (const file of e.target.files) {
    const li = document.createElement('li');
    li.innerHTML = '<div class="row"><span class="name"></span><span class="state">Waiting</span></div><div class="bar"><i></i></div>';
    li.querySelector('.name').textContent = file.name;
    rows.append(li);
    const job = { file, li, loaded: 0, status: 'waiting' };
    batch.push(job); queue.push(job);
  }
  list.prepend(rows);
  e.target.value = '';
  next();
});

el('retry').addEventListener('click', () => {
  for (const job of batch) if (job.status === 'failed') {
    job.status = 'waiting'; job.loaded = 0;
    job.li.className = ''; job.li.querySelector('.bar > i').style.width = '0';
    const s = job.li.querySelector('.state'); s.className = 'state'; s.textContent = 'Waiting';
    queue.push(job);
  }
  next();
});

function next() {
  while (active < PARALLEL && queue.length) start(queue.shift());
  render();
}

function render() {
  const sending = active > 0 || queue.length > 0;
  const total = batch.reduce((n, j) => n + j.file.size, 0);
  const loaded = batch.reduce((n, j) => n + (j.status === 'sent' ? j.file.size : j.loaded), 0);
  const sent = batch.filter(j => j.status === 'sent').length;
  const failed = batch.filter(j => j.status === 'failed').length;

  el('overall').hidden = !sending;
  el('picker').hidden = !sending && batch.length > 0;
  const wasDone = !el('done').hidden;
  el('done').hidden = sending || batch.length === 0;
  if (sending) {
    el('overallTitle').textContent = 'Sending · ' + sent + ' of ' + batch.length + ' saved';
    el('overallBytes').textContent = fmt(loaded) + ' of ' + fmt(total);
    el('overallBar').style.width = (total ? 100 * loaded / total : 0).toFixed(1) + '%';
  } else if (batch.length && !wasDone) {
    el('done').classList.toggle('bad', failed > 0);
    el('doneTitle').textContent = failed ? plural(failed, 'file') + " didn't send" : (sent === 1 ? 'Saved' : 'All ' + sent + ' files saved');
    el('doneText').textContent = failed
      ? plural(sent, 'file') + ' saved on ' + NAME + '. Check QR Drop is still open there, then retry.'
      : (sent === 1 ? batch[0].file.name + ' is' : "They're") + ' in Downloads › QR Drop on ' + NAME + '.';
    const mark = el('doneMark');
    mark.setAttribute('d', failed ? 'M32 19v17M32 44v.5' : 'M20 33l8 8 16-17');
    el('retry').hidden = !failed;
    // Replay the tick's draw-in each time the card appears.
    mark.style.animation = 'none'; mark.getBoundingClientRect(); mark.style.animation = '';
  }
}

function start(job) {
  active++;
  job.status = 'sending';
  const { file, li } = job;
  const state = li.querySelector('.state'), bar = li.querySelector('.bar > i');
  const xhr = new XMLHttpRequest();
  xhr.open('PUT', 'upload?name=' + encodeURIComponent(file.name));
  xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
  xhr.upload.onprogress = ev => {
    if (!ev.lengthComputable) return;
    job.loaded = ev.loaded;
    bar.style.width = (100 * ev.loaded / ev.total).toFixed(1) + '%';
    state.textContent = fmt(ev.loaded) + ' / ' + fmt(ev.total);
    render();
  };
  const finish = ok => {
    job.status = ok ? 'sent' : 'failed';
    li.classList.add(ok ? 'done' : 'failed');
    state.className = 'state ' + (ok ? 'ok' : 'err');
    state.textContent = ok ? 'Saved ✓' : 'Failed';
    if (ok) bar.style.width = '100%';
    active--; next();
  };
  xhr.onload = () => finish(xhr.status >= 200 && xhr.status < 300);
  xhr.onerror = () => finish(false);
  state.textContent = 'Sending…';
  xhr.send(file);
}

// Tell the phone this page is open, and show whether the phone can still be reached.
let misses = 0, stale = false;
async function ping() {
  const stop = new AbortController();
  const timer = setTimeout(() => stop.abort(), 3000);
  try {
    const r = await fetch('ping', { cache: 'no-store', signal: stop.signal });
    stale = r.status === 403; // the phone answers, but QR Drop restarted with a new link
    misses = r.ok ? 0 : misses + 1;
  } catch (e) { misses++; }
  clearTimeout(timer);
  const lost = misses >= 2;
  el('conn').classList.toggle('lost', lost);
  el('connText').textContent = !lost ? 'Connected to ' + NAME
    : stale ? 'QR Drop restarted on ' + NAME + '. Scan its code again.'
    : "Can't reach " + NAME + '. Is QR Drop still open?';
}
ping();
setInterval(ping, 4000);
</script>
</body>
</html>
"""
}
