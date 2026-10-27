package io.github.kennethcheong.qrdrop

/** The page the iPhone opens from the QR code. Uploads go one at a time with a progress bar each. */
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
  :root { --bg:#f5f6fb; --card:#fff; --text:#14161f; --muted:#6b7080; --accent:#3d4bd8; --ok:#1f9d63; --err:#d64545; --track:#e6e8f2; }
  @media (prefers-color-scheme: dark) { :root { --bg:#0d0f16; --card:#171a24; --text:#eef0f7; --muted:#9097aa; --accent:#7b86ff; --ok:#4fd39a; --err:#ff7a7a; --track:#262a38; } }
  * { box-sizing:border-box; }
  body { margin:0; font:17px/1.4 -apple-system, system-ui, sans-serif; background:var(--bg); color:var(--text);
         padding:calc(env(safe-area-inset-top) + 28px) 18px calc(env(safe-area-inset-bottom) + 28px); }
  main { max-width:520px; margin:0 auto; }
  .eyebrow { font-size:13px; letter-spacing:.08em; text-transform:uppercase; color:var(--muted); margin:0 0 4px; }
  h1 { font-size:28px; margin:0 0 22px; letter-spacing:-.01em; }
  label.pick { display:flex; align-items:center; justify-content:center; gap:10px; width:100%; padding:18px; border-radius:16px;
               background:var(--accent); color:#fff; font-weight:600; font-size:18px; cursor:pointer; }
  label.pick svg { width:22px; height:22px; }
  input[type=file] { display:none; }
  .hint { color:var(--muted); font-size:14px; text-align:center; margin:10px 0 24px; }
  ul { list-style:none; margin:0; padding:0; display:grid; gap:10px; }
  li { background:var(--card); border-radius:14px; padding:12px 14px; box-shadow:0 1px 2px rgba(0,0,0,.06); }
  .row { display:flex; justify-content:space-between; gap:12px; align-items:baseline; }
  .name { font-weight:500; overflow:hidden; text-overflow:ellipsis; white-space:nowrap; }
  .state { font-size:14px; color:var(--muted); flex:none; }
  .state.ok { color:var(--ok); } .state.err { color:var(--err); }
  .bar { height:5px; border-radius:3px; background:var(--track); margin-top:9px; overflow:hidden; }
  .bar > i { display:block; height:100%; width:0; background:var(--accent); transition:width .15s; }
  li.done .bar > i { background:var(--ok); } li.failed .bar > i { background:var(--err); }
  .summary { text-align:center; color:var(--muted); font-size:14px; margin-top:18px; min-height:20px; }
</style>
</head>
<body>
<main>
  <p class="eyebrow">QR Drop</p>
  <h1>Send to $name</h1>
  <label class="pick">
    <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2.2" stroke-linecap="round" stroke-linejoin="round"><path d="M12 19V6M6 11l6-6 6 6"/></svg>
    Choose photos or files
    <input id="files" type="file" multiple>
  </label>
  <p class="hint">Files are saved to Downloads › QR Drop on the phone.</p>
  <ul id="list"></ul>
  <p class="summary" id="summary"></p>
</main>
<script>
const list = document.getElementById('list');
const summary = document.getElementById('summary');
const queue = [];
let busy = false, sent = 0, failed = 0;

function fmt(bytes) {
  if (bytes < 1024) return bytes + ' B';
  const units = ['KB', 'MB', 'GB']; let v = bytes / 1024, i = 0;
  while (v >= 1024 && i < units.length - 1) { v /= 1024; i++; }
  return v.toFixed(v < 10 ? 1 : 0) + ' ' + units[i];
}

document.getElementById('files').addEventListener('change', e => {
  for (const file of e.target.files) {
    const li = document.createElement('li');
    li.innerHTML = '<div class="row"><span class="name"></span><span class="state">Waiting</span></div><div class="bar"><i></i></div>';
    li.querySelector('.name').textContent = file.name;
    list.appendChild(li);
    queue.push({ file, li });
  }
  e.target.value = '';
  next();
});

function next() {
  if (busy) return;
  const job = queue.shift();
  if (!job) {
    summary.textContent = sent ? (sent + ' sent' + (failed ? ', ' + failed + ' failed' : '') + '. Choose more any time.') : '';
    return;
  }
  busy = true;
  const { file, li } = job;
  const state = li.querySelector('.state'), bar = li.querySelector('.bar > i');
  const xhr = new XMLHttpRequest();
  xhr.open('PUT', 'upload?name=' + encodeURIComponent(file.name));
  xhr.setRequestHeader('Content-Type', file.type || 'application/octet-stream');
  xhr.upload.onprogress = ev => {
    if (!ev.lengthComputable) return;
    bar.style.width = (100 * ev.loaded / ev.total).toFixed(1) + '%';
    state.textContent = fmt(ev.loaded) + ' / ' + fmt(ev.total);
  };
  const finish = ok => {
    li.classList.add(ok ? 'done' : 'failed');
    state.className = 'state ' + (ok ? 'ok' : 'err');
    state.textContent = ok ? 'Sent ✓' : 'Failed';
    if (ok) { bar.style.width = '100%'; sent++; } else failed++;
    busy = false; next();
  };
  xhr.onload = () => finish(xhr.status >= 200 && xhr.status < 300);
  xhr.onerror = () => finish(false);
  state.textContent = 'Sending…';
  xhr.send(file);
}
</script>
</body>
</html>
"""
}
