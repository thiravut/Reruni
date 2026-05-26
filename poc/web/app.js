const $ = (id) => document.getElementById(id);

// Same-origin: the POC server reverse-proxies /api/legacy/* to the upstream
// API server. No CORS dance, no host detection — just hit the same port the
// dashboard was served from.
const API = "/api/legacy";

function log(line) {
  const el = $("playLog");
  const stamp = new Date().toLocaleTimeString();
  el.textContent += `[${stamp}] ${line}\n`;
  el.scrollTop = el.scrollHeight;
}

async function refresh() {
  await Promise.all([loadVideos(), loadDevices()]);
}

async function loadVideos() {
  const res = await fetch(`${API}/videos`);
  const videos = await res.json();
  const list = $("videoList");
  const select = $("playVideo");
  if (videos.length === 0) {
    list.innerHTML = '<li class="muted">ยังไม่มีวิดีโอ — upload ด้านบนก่อน</li>';
  } else {
    list.innerHTML = videos.map(v => `
      <li>
        <span>${escapeHtml(v.name)} <span class="muted">${formatSize(v.size_bytes)}</span></span>
        <button class="small-btn" data-del="${v.id}">ลบ</button>
      </li>
    `).join("");
    list.querySelectorAll("[data-del]").forEach(btn => {
      btn.addEventListener("click", async () => {
        if (!confirm("ลบวิดีโอนี้?")) return;
        await fetch(`${API}/videos/` + btn.dataset.del, { method: "DELETE" });
        refresh();
      });
    });
  }
  const currentSelected = select.value;
  select.innerHTML = '<option value="">— select video —</option>' +
    videos.map(v => `<option value="${v.id}">${escapeHtml(v.name)}</option>`).join("");
  if (currentSelected) select.value = currentSelected;
}

async function loadDevices() {
  const res = await fetch(`${API}/devices`);
  const devices = await res.json();
  const list = $("deviceList");
  const select = $("playDevice");
  if (devices.length === 0) {
    list.innerHTML = '<li class="muted">ยังไม่มี device pair — สร้าง Pair Token แล้ว pair จากแอป mobile</li>';
  } else {
    list.innerHTML = devices.map(d => `
      <li>
        <span>
          <code>${d.id}</code> ${escapeHtml(d.name || "")}
          <span class="badge ${d.online ? "online" : "offline"}">${d.online ? "ONLINE" : "offline"}</span>
        </span>
        <span class="muted">${d.last_seen ? new Date(d.last_seen).toLocaleString() : "—"}</span>
      </li>
    `).join("");
  }
  const currentSelected = select.value;
  select.innerHTML = '<option value="">— select device —</option>' +
    devices.filter(d => d.online).map(d => `<option value="${d.id}">${d.id} ${escapeHtml(d.name || "")}</option>`).join("");
  if (currentSelected) select.value = currentSelected;
}

function escapeHtml(s) {
  if (s == null) return "";
  return String(s).replace(/[&<>"']/g, c => ({"&":"&amp;","<":"&lt;",">":"&gt;","\"":"&quot;","'":"&#39;"}[c]));
}
function formatSize(b) {
  if (b < 1024) return b + " B";
  if (b < 1024*1024) return (b/1024).toFixed(1) + " KB";
  if (b < 1024*1024*1024) return (b/(1024*1024)).toFixed(1) + " MB";
  return (b/(1024*1024*1024)).toFixed(2) + " GB";
}

$("uploadForm").addEventListener("submit", async (e) => {
  e.preventDefault();
  const fd = new FormData(e.target);
  const xhr = new XMLHttpRequest();
  const prog = $("uploadProgress");
  prog.hidden = false; prog.value = 0;
  xhr.upload.addEventListener("progress", (ev) => {
    if (ev.lengthComputable) prog.value = (ev.loaded / ev.total) * 100;
  });
  xhr.addEventListener("load", () => {
    prog.hidden = true;
    if (xhr.status >= 200 && xhr.status < 300) {
      e.target.reset();
      refresh();
    } else {
      alert("Upload failed: " + xhr.responseText);
    }
  });
  xhr.open("POST", `${API}/videos`);
  xhr.send(fd);
});

$("pairBtn").addEventListener("click", async () => {
  const res = await fetch(`${API}/pair`, { method: "POST" });
  const data = await res.json();
  $("pairToken").textContent = data.url + "   token: " + data.token.slice(0, 8) + "…";
  $("pairQr").src = data.qr_data_url;
  $("pairQrWrap").hidden = false;

  if (data.url.includes("localhost") || data.url.includes("127.0.0.1")) {
    alert("⚠️ ตอนนี้คุณเข้า dashboard ผ่าน localhost → QR จะใช้ไม่ได้บนมือถือ\nให้เปิด dashboard ผ่าน LAN IP ของ Mac (เช่น http://192.168.x.x:8080) แทน");
  }
});

function readProductKeywords() {
  const raw = ($("productKeywords")?.value || "").split("\n");
  return raw.map(s => s.trim()).filter(s => s.length > 0);
}
function readLiveTitle() {
  return ($("liveTitle")?.value || "").trim();
}

$("playBtn").addEventListener("click", async () => {
  const deviceId = $("playDevice").value;
  const videoId = $("playVideo").value;
  if (!deviceId || !videoId) { alert("เลือก device + video ก่อน"); return; }
  const autoStart = $("autoStartCheck").checked;
  const useOverlay = $("useOverlayCheck").checked;
  const keywords = readProductKeywords();
  const title = readLiveTitle();
  const res = await fetch(`${API}/devices/${deviceId}/play`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      video_id: parseInt(videoId, 10),
      auto_start_live: autoStart,
      use_overlay: useOverlay,
      product_keywords: keywords,
      live_title: title,
    })
  });
  const data = await res.json();
  if (res.ok) {
    const tags = [];
    if (autoStart) tags.push("auto-start");
    if (useOverlay) tags.push("overlay");
    if (keywords.length) tags.push(`${keywords.length} kw`);
    if (title) tags.push("title");
    const suffix = tags.length ? ` (${tags.join(", ")})` : "";
    log(`→ ${deviceId}: play video #${videoId}${suffix}`);
  } else log(`✗ ${data.error || "error"}`);
});

$("startLiveBtn").addEventListener("click", async () => {
  const deviceId = $("playDevice").value;
  if (!deviceId) { alert("เลือก device ก่อน"); return; }
  const useOverlay = $("useOverlayCheck").checked;
  const keywords = readProductKeywords();
  const title = readLiveTitle();
  const res = await fetch(`${API}/devices/${deviceId}/start-live`, {
    method: "POST",
    headers: { "Content-Type": "application/json" },
    body: JSON.stringify({
      product_keywords: keywords,
      live_title: title,
      use_overlay: useOverlay,
    }),
  });
  const data = await res.json();
  if (res.ok) log(`→ ${deviceId}: start_live (autopilot${useOverlay ? "+overlay" : ""}, ${keywords.length} kw${title ? ", title" : ""})`);
  else log(`✗ ${data.error || "error"}`);
});

refresh();
setInterval(refresh, 5000);
