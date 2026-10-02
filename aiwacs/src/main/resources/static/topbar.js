// 모든 화면 공통 상단바: 환영 문구 · 현재 시각 · 아이콘(Agent / 알림(종) / 계정) + 종 패널(레벨 탭 + AI 사건 마인드맵)
// 화면마다 <div class="topbar"> 바로 뒤에서 이 파일을 불러오면 오른쪽 영역과 팝오버가 그려진다.
// - 왼쪽 경로(.breadcrumb)는 화면마다 그대로 둔다
// - 화면 전용 요소(예: 대시보드의 Company 선택)는 .topbar-right 안에 class="tb-extra"로 두면 시계 옆에 유지된다
(function () {
  const topbar = document.querySelector(".topbar");
  if (!topbar) return;

  // ===== 스타일 (각 화면의 .topbar 정의보다 뒤에 붙여서 모든 화면이 같은 모양이 되게) =====
  const style = document.createElement("style");
  style.textContent = `
    .topbar { background: #fff; border-bottom: 1px solid #e3e6ec; padding: 10px 20px; display: flex; justify-content: space-between; align-items: center; }
    .breadcrumb { color: #7a8194; font-size: 12px; }
    .breadcrumb b { color: #3f6ad8; }
    .topbar-right { color: #7a8194; font-size: 12px; display: flex; gap: 14px; align-items: center; }
    .topbar-right b { color: #3f6ad8; }
    /* 상단바 아이콘 (AiWACS식: Agent / 알림(종) / 계정) */
    .tb-icons { display: flex; align-items: center; gap: 14px; margin-left: 10px; }
    .tb-icon {
      position: relative; width: 32px; height: 32px; border: none; background: none;
      color: #6b7280; cursor: pointer; display: flex; align-items: center; justify-content: center;
      border-radius: 8px;
    }
    .tb-icon:hover { background: #f2f4f8; color: #3f6ad8; }
    .tb-icon svg { width: 19px; height: 19px; }
    .tb-badge {
      position: absolute; top: 2px; right: 2px; background: #d64545; color: #fff;
      font-size: 9px; font-weight: 800; min-width: 15px; height: 15px; line-height: 15px;
      border-radius: 8px; padding: 0 3px; text-align: center;
    }
    .tb-pop {
      position: absolute; top: 52px; right: 16px; width: 380px; max-width: calc(100vw - 32px);
      max-height: 74vh; overflow-y: auto; background: #fff; border: 1px solid #e3e6ec;
      border-radius: 10px; box-shadow: 0 10px 34px rgba(23,32,64,0.16); z-index: 40;
      font-size: 13px; color: #333;
    }
    .tb-pop[hidden] { display: none; }
    .tb-pop .pop-h {
      padding: 12px 16px; border-bottom: 1px solid #eef0f4; font-weight: 700;
      color: #3a3f52; font-size: 13px; display: flex; justify-content: space-between; align-items: center;
    }
    .tb-pop .pop-b { padding: 14px 16px; }
    .pop-agent-b, .pop-account-b { font-size: 12.5px; color: #3a4055; line-height: 1.7; }
    .al-row {
      display: flex; align-items: center; gap: 8px; padding: 9px 0;
      border-bottom: 1px dashed #eef0f4; font-size: 12.5px; color: #3a4055;
    }
    .al-row:last-child { border-bottom: none; }
    .al-row b { color: #232838; }
    .al-row .cnt { color: #9098aa; font-size: 11px; margin-left: 4px; }
    .al-lv { display: inline-flex; align-items: center; padding: 1px 8px; border-radius: 12px; font-size: 11px; font-weight: 700; white-space: nowrap; }
    .al-lv.lv-주의 { background: #fbf1da; color: #c9820a; }
    .al-lv.lv-경고 { background: #fbe6d6; color: #c9600a; }
    .al-lv.lv-위험 { background: #fbe9e9; color: #d64545; }
    .al-lv.lv-장애 { background: #f6d7d7; color: #a02020; }
    .al-actions { display: flex; gap: 8px; margin: 12px 0 4px; }
    .al-btn {
      flex: 1; padding: 8px 10px; border-radius: 8px; border: none; background: #3f6ad8;
      color: #fff; font-size: 12px; font-weight: 600; cursor: pointer; font-family: inherit;
    }
    .al-btn:hover { background: #2f52b0; }
    .al-btn.ghost { background: #f2f4f8; color: #3a3f52; border: 1px solid #d5d9e2; }
    .al-btn.ghost:hover { background: #e6eaf1; }
    .al-empty { color: #9098aa; font-size: 12.5px; padding: 6px 0; }
    .al-note { color: #9aa1b2; font-size: 11px; margin-top: 10px; }
    /* 종 패널 레벨 탭 (AiWACS: 전체/주의/경고/위험/장애) + AI 사건 탭 */
    .al-tabs { display: flex; border-bottom: 1px solid #eef0f4; background: #f8f9fc; }
    .al-tabs button {
      flex: 1; border: none; background: none; padding: 9px 2px; font-size: 12px; color: #7a8194;
      cursor: pointer; border-bottom: 2px solid transparent; white-space: nowrap; font-family: inherit;
    }
    .al-tabs button.on { color: #3f6ad8; border-bottom-color: #3f6ad8; background: #fff; font-weight: 700; }
    .al-tabs button .n { display: inline-block; min-width: 16px; margin-left: 3px; padding: 0 4px; border-radius: 8px; background: #e6e9f0; color: #5a6072; font-size: 10px; }
    .al-tabs button.ai { color: #6b46c1; }
    .al-tabs button.ai.on { border-bottom-color: #6b46c1; }
    .al-tabs button.ai .n { background: #ece6fb; color: #6b46c1; }
    /* AI 사건 마인드맵(위→아래): 현재 알림 → 서버 → 사건(자원) → 레벨별 알림 */
    .mm-wrap { margin-top: 10px; }
    .mm { position: relative; }
    .mm svg { position: absolute; left: 0; top: 0; overflow: visible; }
    .mm-node {
      position: absolute; transform: translateY(-50%); box-sizing: border-box;
      border: 1px solid #d5d9e2; border-radius: 16px; background: #fff; padding: 5px 11px;
      font-size: 12px; color: #3a4055; white-space: nowrap; overflow: hidden; text-overflow: ellipsis;
    }
    .mm-root { background: #6b46c1; border-color: #6b46c1; color: #fff; font-weight: 700; }
    .mm-server { background: #eef2fb; border-color: #c9d4ef; color: #2f52b0; font-weight: 700; }
    .mm-event { cursor: pointer; font-weight: 700; color: #232838; border-width: 2px; }
    .mm-event:hover { box-shadow: 0 2px 8px rgba(23,32,64,0.12); }
    .mm-event.on { box-shadow: 0 0 0 3px rgba(107,70,193,0.25); }
    .mm-event.lv-주의 { border-color: #e0a63a; } .mm-event.lv-경고 { border-color: #e07a2a; }
    .mm-event.lv-위험 { border-color: #d64545; } .mm-event.lv-장애 { border-color: #a02020; }
    .mm-leaves { border: none; background: none; padding: 0; display: flex; gap: 5px; }
    .mm-leaf { font-size: 11px; padding: 2px 8px; font-weight: 700; cursor: default; }
    .mm-dim { opacity: 0.3; }
    .mm-card { border: 1px solid #e3e6ec; border-left: 3px solid #6b46c1; border-radius: 8px; background: #f8f9fc; padding: 10px 13px; margin-top: 12px; }
    .mm-card .ev-h { display: flex; align-items: center; gap: 7px; }
    .mm-card .ev-t { font-weight: 700; color: #232838; font-size: 12.5px; }
    .mm-card .ev-k { color: #9098aa; font-weight: 700; font-size: 11px; margin-top: 7px; }
    .mm-card .ev-v { color: #3a4055; font-size: 12px; }
    .mm-card .ev-go { display: flex; gap: 6px; margin-top: 10px; }
    .mm-card .ev-go a { font-size: 11.5px; font-weight: 700; padding: 5px 10px; border-radius: 7px; border: 1px solid #b9c9f2; background: #fff; color: #3f6ad8; text-decoration: none; }
    .mm-card .ev-go a:hover { background: #eef3fc; }
  `;
  document.head.appendChild(style);

  // ===== 오른쪽 영역: 환영 문구 · 시각 · (화면 전용 요소) · 아이콘 =====
  let right = topbar.querySelector(".topbar-right");
  if (!right) {
    right = document.createElement("div");
    right.className = "topbar-right";
    topbar.appendChild(right);
  }
  const extras = [...right.querySelectorAll(".tb-extra")];
  right.innerHTML =
    `<span><b>aiwacs</b>님, 환영합니다.</span>` +
    `<span>현재 시각: <span id="tb-clock">--:--:--</span></span>`;
  extras.forEach((el) => right.appendChild(el));
  const icons = document.createElement("div");
  icons.className = "tb-icons";
  icons.innerHTML = `
    <button class="tb-icon" title="Agent 다운로드" onclick="tbTogglePop('pop-agent')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4"/><polyline points="7 10 12 15 17 10"/><line x1="12" y1="15" x2="12" y2="3"/></svg>
    </button>
    <button class="tb-icon" title="알림" onclick="tbTogglePop('pop-alarm')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M18 8A6 6 0 0 0 6 8c0 7-3 9-3 9h18s-3-2-3-9"/><path d="M13.73 21a2 2 0 0 1-3.46 0"/></svg>
      <span class="tb-badge" id="alarm-badge" hidden>0</span>
    </button>
    <button class="tb-icon" title="계정" onclick="tbTogglePop('pop-account')">
      <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round" stroke-linejoin="round"><path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2"/><circle cx="12" cy="7" r="4"/></svg>
    </button>`;
  right.appendChild(icons);

  // ===== 팝오버 (상단바 바로 뒤) =====
  topbar.insertAdjacentHTML(
    "afterend",
    `
    <div class="tb-pop" id="pop-agent" hidden>
      <div class="pop-h">Agent 다운로드</div>
      <div class="pop-b pop-agent-b">
        모니터링 대상 서버에 설치할 Agent를 내려받습니다.<br />
        설치 후 <b>server.url</b>을 이 서버로 지정하면 자동 등록됩니다.<br />
        <span style="color:#9aa1b2">(데모 화면 — 실제 배포 파일은 환경설정 &gt; Agent 다운로드)</span>
      </div>
    </div>
    <div class="tb-pop" id="pop-alarm" hidden>
      <div class="pop-h">
        <span>알림 (현재 발생 <span id="pop-alarm-count">0</span>건)</span>
        <button class="al-btn ghost" style="flex:none;padding:3px 10px" onclick="tbLoadAlarms()">새로고침</button>
      </div>
      <!-- AiWACS 종 패널과 같은 레벨 탭 + 맨 끝에 AI 사건 탭 (원래 탭은 그대로 두고 보기 방식만 하나 추가) -->
      <div class="al-tabs" id="al-tabs"></div>
      <div class="pop-b">
        <div id="alarm-list"><div class="al-empty">불러오는 중…</div></div>
        <div id="alarm-grouped" hidden></div>
      </div>
    </div>
    <div class="tb-pop" id="pop-account" hidden>
      <div class="pop-h">계정</div>
      <div class="pop-b pop-account-b">
        <b>aiwacs</b> 님<br />
        역할: 관제 운영자<br />
        <span style="color:#9aa1b2">(데모 화면)</span>
      </div>
    </div>`
  );

  // ===== 시계 =====
  const clock = document.getElementById("tb-clock");
  const tick = () => (clock.textContent = new Date().toLocaleTimeString("ko-KR"));
  tick();
  setInterval(tick, 1000);

  // ===== 팝오버 열고 닫기 =====
  function togglePop(id) {
    document.querySelectorAll(".tb-pop").forEach((p) => {
      p.hidden = p.id !== id ? true : !p.hidden;
    });
  }
  document.addEventListener("click", (e) => {
    // 탭을 누르면 탭 줄을 다시 그리므로, 누른 버튼이 이미 화면에서 빠졌을 수 있음 → 바깥 클릭으로 보지 않음
    if (!document.contains(e.target)) return;
    if (!e.target.closest(".tb-pop") && !e.target.closest(".tb-icon")) {
      document.querySelectorAll(".tb-pop").forEach((p) => (p.hidden = true));
    }
  });

  function alEsc(s) {
    return String(s == null ? "" : s).replace(/&/g, "&amp;").replace(/</g, "&lt;").replace(/>/g, "&gt;");
  }
  function alLv(l) {
    return "al-lv lv-" + (l || "주의");
  }

  // ===== 종 패널: 레벨 탭(전체/주의/경고/위험/장애) + AI 사건 탭 =====
  const AL_TABS = ["전체", "주의", "경고", "위험", "장애"];
  let alList = [];
  let alTab = "전체";
  let alEvents = null; // AI 사건 결과 (탭을 다시 열 때 재사용, 새로고침 시 다시 묶음)

  function renderAlarmTabs() {
    const n = (lv) => (lv === "전체" ? alList.length : alList.filter((a) => a.level === lv).length);
    document.getElementById("al-tabs").innerHTML =
      AL_TABS.map((lv) => `<button class="${alTab === lv ? "on" : ""}" onclick="tbShowAlarmTab('${lv}')">${lv}<span class="n">${n(lv)}</span></button>`).join("") +
      `<button class="ai ${alTab === "AI" ? "on" : ""}" onclick="tbShowAlarmTab('AI')" title="같은 서버·같은 자원의 알림을 한 사건으로 묶고 AI가 원인을 해석합니다">✦ AI 사건<span class="n">${alEvents ? alEvents.length : "-"}</span></button>`;
  }

  function showAlarmTab(tab) {
    alTab = tab;
    renderAlarmTabs();
    const listBox = document.getElementById("alarm-list");
    const out = document.getElementById("alarm-grouped");
    listBox.hidden = tab === "AI";
    out.hidden = tab !== "AI";
    if (tab === "AI") {
      if (!alEvents) groupAlarmsUI();
      return;
    }
    const list = tab === "전체" ? alList : alList.filter((a) => a.level === tab);
    listBox.innerHTML = list.length
      ? list.map((a) =>
          `<div class="al-row"><span class="${alLv(a.level)}">${alEsc(a.level)}</span> <b>${alEsc(a.server)}</b> · ${alEsc(a.title || a.metric)}<span class="cnt">${alEsc(a.count)}회</span><span style="margin-left:auto;color:#7a8194">${alEsc(a.value)}</span></div>`
        ).join("")
      : '<div class="al-empty">현재 발생한 알림이 없습니다.</div>';
  }

  function renderAlarmList(list) {
    alList = list;
    const badge = document.getElementById("alarm-badge");
    document.getElementById("pop-alarm-count").textContent = list.length;
    if (list.length) { badge.hidden = false; badge.textContent = list.length; } else { badge.hidden = true; }
    showAlarmTab(alTab);
  }
  async function loadAlarms() {
    try {
      const list = await (await fetch("/api/ai/alarms")).json();
      alEvents = alTab === "AI" ? alEvents : null; // AI 탭을 보고 있지 않으면 다음에 열 때 새로 묶음
      renderAlarmList(list);
    } catch (e) {
      document.getElementById("alarm-list").innerHTML = '<div class="al-empty">알림을 불러오지 못했습니다.</div>';
    }
  }
  async function groupAlarmsUI() {
    const out = document.getElementById("alarm-grouped");
    out.innerHTML = '<div class="al-empty">같은 서버·같은 자원 알림을 묶고, AI가 원인을 해석하는 중…</div>';
    try {
      const d = await (await fetch("/api/ai/alarm-group", { method: "POST", headers: { "Content-Type": "application/json" }, body: JSON.stringify({ demo: false }) })).json();
      if (!d.ok) { out.innerHTML = `<div class="al-empty" style="color:#a03535">${alEsc(d.reply)}</div>`; return; }
      const events = (d.result && d.result.events) || [];
      alEvents = events;
      renderAlarmTabs();
      const raw = d.rawCount || 0;
      if (!events.length) { out.innerHTML = '<div class="al-empty">묶을 발생 알림이 없습니다.</div>'; return; }
      out.innerHTML =
        `<div class="al-note" style="margin-top:0">사건을 누르면 AI 해석을 봅니다 (원본 알림은 레벨 탭에 그대로 있습니다)</div>` +
        (d.aiNote ? `<div class="al-note" style="color:#a0661a">${alEsc(d.aiNote)} — 묶음만 표시합니다.</div>` : "") +
        `<div class="mm-wrap"><div class="mm" id="mm"></div></div><div id="mm-card"></div>` +
        `<div class="al-actions"><button class="al-btn ghost" onclick="tbRegroupAlarms()">다시 묶기</button></div>`;
      renderMindMap(events, raw);
    } catch (e) {
      out.innerHTML = '<div class="al-empty" style="color:#a03535">묶기 중 오류가 발생했습니다.</div>';
    }
  }

  // ===== AI 사건 마인드맵 (위→아래): 현재 알림 → 서버 → 사건(자원) → 레벨별 알림 =====
  // 위치는 코드가 계산: 한 줄씩 아래로 쌓고, 단계마다 오른쪽으로 들여쓴다 (패널 너비 그대로)
  const MM_X = { root: 0, server: 22, event: 46, leaf: 70 };
  const MM_LV = ["장애", "위험", "경고", "주의"]; // 심각한 레벨부터

  function renderMindMap(events, raw) {
    const box = document.getElementById("mm");
    const W = box.parentElement.clientWidth || 348;
    const nodes = []; // {col, y, html, cls, evs, title, onclick}
    const links = []; // {from, to, evs}
    const servers = [];
    events.forEach((e, i) => {
      let s = servers.find((x) => x.name === e.server);
      if (!s) servers.push((s = { name: e.server, evs: [] }));
      s.evs.push(i);
    });

    let y = 16;
    const add = (n, h) => { n.y = y; y += h; return nodes.push(n) - 1; };
    const rn = add({ col: "root", evs: events.map((_, i) => i), cls: "mm-root", html: `현재 알림 ${raw}건 → 사건 ${events.length}건` }, 38);
    servers.forEach((s) => {
      const sn = add({ col: "server", evs: s.evs, cls: "mm-server", html: "🖥 " + alEsc(s.name), title: s.name }, 36);
      links.push({ from: rn, to: sn, evs: s.evs });
      s.evs.forEach((i) => {
        const e = events[i];
        const en = add({ col: "event", evs: [i], cls: "mm-event lv-" + e.level, title: e.title,
          html: `<span class="${alLv(e.level)}" style="margin-right:6px">${alEsc(e.level)}</span>${alEsc(e.title)}`,
          onclick: `tbSelectMindEvent(${i})` }, 32);
        links.push({ from: sn, to: en, evs: [i] });
        const lvs = MM_LV.filter((lv) => (e.byLevel || {})[lv]);
        const chips = (lvs.length ? lvs : [e.level]).map((lv) => {
          const members = (e.members || []).filter((m) => String(m).startsWith(lv + " "));
          return `<span class="mm-leaf al-lv lv-${lv}" title="${alEsc(members.join("\n")).replace(/"/g, "&quot;")}">${alEsc(lv)} ${alEsc((e.byLevel || {})[lv] || members.length)}건</span>`;
        }).join("");
        const ln = add({ col: "leaf", evs: [i], cls: "mm-leaves", html: chips }, 34);
        links.push({ from: en, to: ln, evs: [i] });
      });
      y += 4;
    });

    const H = y;
    const evAttr = (evs) => ` data-evs=" ${evs.join(" ")} "`;
    // 부모 왼쪽 아래에서 내려와 자식 왼쪽으로 꺾이는 선
    const paths = links.map((l) => {
      const a = nodes[l.from], b = nodes[l.to];
      const x = MM_X[a.col] + 12, x2 = MM_X[b.col];
      return `<path${evAttr(l.evs)} d="M${x},${a.y + 12} L${x},${b.y - 8} Q${x},${b.y} ${x + 8},${b.y} L${x2},${b.y}" fill="none" stroke="#c3c9d6" stroke-width="1.5"/>`;
    }).join("");
    box.style.height = H + "px";
    box.innerHTML =
      `<svg width="${W}" height="${H}">${paths}</svg>` +
      nodes.map((n) =>
        `<div class="mm-node ${n.cls}"${evAttr(n.evs)} style="left:${MM_X[n.col]}px;top:${n.y}px;max-width:${W - MM_X[n.col]}px"` +
        (n.title ? ` title="${alEsc(n.title).replace(/"/g, "&quot;")}"` : "") +
        (n.onclick ? ` onclick="${n.onclick}"` : "") + `>${n.html}</div>`
      ).join("");
    selectMindEvent(0);
  }

  // 사건을 고르면 그 가지만 진하게, 아래 카드에 AI 해석 표시
  function selectMindEvent(i) {
    const e = (alEvents || [])[i];
    if (!e) return;
    document.querySelectorAll("#mm [data-evs]").forEach((el) => {
      const mine = el.dataset.evs.includes(" " + i + " ");
      el.classList.toggle("mm-dim", !mine);
      el.classList.toggle("on", mine && el.classList.contains("mm-event"));
    });
    document.getElementById("mm-card").innerHTML = `<div class="mm-card">
      <div class="ev-h"><span class="${alLv(e.level)}">${alEsc(e.level)}</span><span class="ev-t">${alEsc(e.title)}</span></div>
      <div class="ev-k">사건 구간</div><div class="ev-v">${alEsc(e.firstAt)} ~ ${alEsc(e.lastAt)} · 알림 ${alEsc(e.alarmCount)}건 · 발생 횟수 합계 ${alEsc(e.occurrences)}회 (지표: ${alEsc((e.metrics || []).join(", "))})</div>
      <div class="ev-k">원인 추정 (AI)</div><div class="ev-v">${alEsc(e.cause)}</div>
      <div class="ev-k">권장 조치 (AI)</div><div class="ev-v">${alEsc(e.action)}</div>
      <div class="ev-go">
        <a href="/ai?serverId=${encodeURIComponent(String(e.key).split("|")[0])}&since=${e.firstAtMillis}&run=1#diagnose" title="사건 시작 5분 전부터 지금까지를 진단합니다">✦ AI 진단 · 조치</a>
        <a href="/alerts?select=${(e.alarmIds || []).join(",")}">처리 기록 →</a>
      </div>
    </div>`;
  }

  // HTML onclick에서 부르는 함수만 밖으로 (다른 화면의 이름과 겹치지 않게 tb 접두어)
  window.tbTogglePop = togglePop;
  window.tbShowAlarmTab = showAlarmTab;
  window.tbLoadAlarms = loadAlarms;
  window.tbSelectMindEvent = selectMindEvent;
  window.tbRegroupAlarms = () => {
    alEvents = null;
    groupAlarmsUI();
  };

  loadAlarms();
  setInterval(loadAlarms, 10000);
})();
