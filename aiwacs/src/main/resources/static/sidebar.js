// 모든 화면이 함께 쓰는 사이드바 메뉴.
// 화면마다 <aside class="sidebar"></aside> 바로 뒤에서 이 파일을 불러오면 메뉴가 그려진다.
// 하위 메뉴가 있는 항목은 화살표를 눌러 펼치고 접는다. 지금 보고 있는 화면이 속한 메뉴는 자동으로 펼쳐진다.
// href가 없는 항목은 AiWACS 원래 메뉴 구성을 보여주기 위한 자리(이 프로젝트에서는 미구현)다.
(function () {
  const MENU = [
    {
      label: "대시보드",
      children: [{ label: "요약", href: "/" }, { label: "실시간" }],
    },
    {
      label: "AI 운영 도우미",
      children: [
        { label: "상태 진단", href: "/ai#diagnose" },
        { label: "임계치 변경", href: "/ai#threshold" },
      ],
    },
    { label: "장비 목록", href: "/servers" },
    { label: "ICMP" },
    { label: "프로세스" },
    { label: "추적" },
    { label: "서비스" },
    { label: "로그 조회" },
    {
      label: "알림",
      children: [
        { label: "알림 내역", href: "/alerts" },
        { label: "처리 내역", href: "/alerts/handled" },
      ],
    },
    { label: "장비 비교/분석" },
    { label: "보고서" },
    { label: "장비 모니터링" },
    {
      label: "정책 관리",
      children: [
        { label: "알림 정책", href: "/policy" },
        { label: "보고서 정책" },
        { label: "경로 추적 정책" },
      ],
    },
    { label: "그룹 설정" },
    { label: "환경설정" },
  ];

  const STYLE = `
    /* 로고 크기를 모든 화면에서 통일 (페이지마다 .logo 스타일이 없어 작아지던 문제 방지) */
    .sidebar .logo {
      padding: 16px 18px; font-size: 18px; font-weight: 800;
      letter-spacing: -0.02em; border-bottom: 1px solid #eef0f4; cursor: pointer;
      line-height: 1.2;
    }
    .sidebar .logo .logo-ai { color: #f08c00; }   /* 로고: Ai는 주황 */
    .sidebar .logo .logo-wacs { color: #111; }    /* WACS는 검정 */
    .sidebar .menu { padding: 8px 0; }
    .sidebar .menu-item, .sidebar .menu-cat {
      display: flex; align-items: center; justify-content: space-between;
      padding: 9px 18px; color: #5a6072; cursor: default; font-weight: 400;
      text-decoration: none; font-size: 13px;
    }
    .sidebar a.menu-item, .sidebar .menu-cat { cursor: pointer; }
    .sidebar .menu-item:hover, .sidebar .menu-cat:hover { background: #f5f6fa; }
    .sidebar .menu-item.active { background: #3f6ad8; color: #fff; font-weight: 600; }
    .sidebar .menu-cat.has-active { color: #3a3f52; font-weight: 600; }
    .sidebar .menu-cat .arrow { font-size: 10px; color: #9aa1b2; transition: transform 0.2s; }
    .sidebar .menu-cat.open .arrow { transform: rotate(180deg); }
    .sidebar .menu-sub { display: none; }
    .sidebar .menu-sub.open { display: block; }
    .sidebar .menu-item.sub { padding-left: 34px; font-size: 12px; color: #7a8194; }
    .sidebar .menu-item.sub.active { background: #eaf0fb; color: #3f6ad8; }
  `;

  // 펼침/접힘 상태를 기억 (브라우저 저장소를 못 쓰는 환경이면 그냥 기본값)
  const KEY = "aiwacs.sidebar.open";
  function loadOpen() {
    try {
      return JSON.parse(localStorage.getItem(KEY)) || {};
    } catch (e) {
      return {};
    }
  }
  function saveOpen(state) {
    try {
      localStorage.setItem(KEY, JSON.stringify(state));
    } catch (e) {}
  }

  // 지금 화면과 같은 메뉴인지 (AI 화면은 #탭까지 비교, 탭이 없으면 상태 진단)
  function isCurrent(href) {
    if (!href) return false;
    const [path, hash] = href.split("#");
    if (path !== location.pathname) return false;
    if (!hash) return true;
    return (location.hash.slice(1) || "diagnose") === hash;
  }

  function render() {
    const aside = document.querySelector("aside.sidebar");
    if (!aside) return;
    const open = loadOpen();
    let html = `<div class="logo" onclick="location.href='/'" style="cursor:pointer"><span class="logo-ai">Ai</span><span class="logo-wacs">WACS</span></div><div class="menu">`;
    MENU.forEach((m, i) => {
      if (!m.children) {
        const cls = `menu-item${isCurrent(m.href) ? " active" : ""}`;
        html += m.href
          ? `<a class="${cls}" href="${m.href}">${m.label}</a>`
          : `<div class="${cls}">${m.label}</div>`;
        return;
      }
      const hasActive = m.children.some((c) => isCurrent(c.href));
      // 저장된 상태가 있으면 그대로, 없으면 지금 화면이 속한 메뉴만 펼침
      const isOpen = open[m.label] ?? hasActive;
      html += `<div class="menu-cat${isOpen ? " open" : ""}${hasActive ? " has-active" : ""}" data-group="${i}">
          ${m.label}<span class="arrow">▾</span></div>
        <div class="menu-sub${isOpen ? " open" : ""}" data-group="${i}">`;
      m.children.forEach((c) => {
        const cls = `menu-item sub${isCurrent(c.href) ? " active" : ""}`;
        html += c.href
          ? `<a class="${cls}" href="${c.href}">${c.label}</a>`
          : `<div class="${cls}">${c.label}</div>`;
      });
      html += `</div>`;
    });
    aside.innerHTML = html + `</div>`;

    aside.querySelectorAll(".menu-cat").forEach((cat) => {
      cat.addEventListener("click", () => {
        const sub = aside.querySelector(`.menu-sub[data-group="${cat.dataset.group}"]`);
        const nowOpen = !sub.classList.contains("open");
        sub.classList.toggle("open", nowOpen);
        cat.classList.toggle("open", nowOpen);
        const state = loadOpen();
        state[MENU[cat.dataset.group].label] = nowOpen;
        saveOpen(state);
      });
    });
  }

  const style = document.createElement("style");
  style.textContent = STYLE;
  document.head.appendChild(style);
  render();
  // AI 화면에서 상태 진단 ↔ 임계치 변경을 오갈 때 선택 표시를 다시 그림
  window.addEventListener("hashchange", render);
  window.refreshSidebar = render;
})();
