import { escapeHtml } from "./http";
import { renderRoutineEditor } from "./routine-editor";
import { revisionInline } from "./revision";

export type IndexEntry = {
  date: string;
  durationMs: number;
  setCount: number;
  exerciseCount: number;
  isMakeup: boolean;
  estimated?: boolean;
  partial?: boolean;
};

export type DashboardOptions = {
  displayName: string;
  slug: string;
  accessToken?: string;
  agentUrl: string;
  /** full site, phone page with Log and Stats, or one panel chosen by the phone. */
  embed?: "full" | "app" | "locked";
  lockTab?: "log" | "stats";
};

export function renderDashboard(index: IndexEntry[], options: DashboardOptions): string {
  const embed = options.embed ?? "full";
  const bodyClass = embed === "locked" ? "app-embed locked" : embed === "app" ? "app-embed" : "";
  const routines = renderRoutineEditor({
    apiBase: `/u/${options.slug}`,
    calendarHref: `/u/${options.slug}`,
    slug: options.slug,
    signedIn: true,
    accessToken: options.accessToken,
    embedded: true,
  });
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <title>Gym Buddy</title>
  <meta name="gb-profile" content="${escapeHtml(options.displayName)}" />
  <meta name="referrer" content="no-referrer" />
  <style>
    :root { color-scheme: dark; }
    * { box-sizing: border-box; }
    body { margin: 0; font-family: ui-sans-serif, system-ui, sans-serif; background: #121212; color: #eee; }
    a { color: #00ffff; }
    .top { position: sticky; top: 0; z-index: 6; display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 10px 18px; background: #1a1024; border-bottom: 3px solid #5B2C6F; }
    .brand { display: flex; align-items: center; gap: 10px; text-decoration: none; color: inherit; }
    .plate { width: 36px; height: 36px; display: block; }
    .word { font-size: 26px; font-weight: 800; letter-spacing: -0.03em; line-height: 1; }
    .word .gym { color: #f9f72e; }
    .word .buddy { color: #00ff00; }
    .who { display: flex; flex-direction: column; align-items: flex-end; line-height: 1.15; text-align: right; }
    .who b { color: #00ffff; font-weight: 600; font-size: 16px; }
    .who .rev-inline { font: 11px/1.2 ui-monospace, SFMono-Regular, Menlo, monospace; color: #6e6e76; letter-spacing: .03em; }
    .shell { display: flex; align-items: flex-start; min-height: calc(100vh - 59px); }
    .side { position: sticky; top: 59px; width: 240px; flex: none; align-self: stretch; display: flex; flex-direction: column; gap: 6px; padding: 16px 12px; background: #1a1a1a; border-right: 1px solid #333; }
    .side > button { text-align: left; background: transparent; color: #ddd; border: 0; border-left: 3px solid transparent; border-radius: 8px; padding: 10px 12px; cursor: pointer; font: inherit; font-size: 16px; }
    .side > button.active { background: #2a2210; color: #f9f72e; border-left-color: #f9f72e; }
    main { flex: 1; min-width: 0; margin: 0; max-width: none; padding: 20px 24px 48px; }
    h1 { color: #f9f72e; font-size: 28px; margin: 0 0 8px; }
    .sub { color: #9e9e9e; margin-bottom: 24px; }
    @media (max-width: 800px) {
      .shell { flex-direction: column; }
      .side { position: sticky; top: 56px; width: auto; flex-direction: row; flex-wrap: wrap; border-right: 0; border-bottom: 1px solid #333; padding: 8px; z-index: 5; }
      .side > button { flex: 1; text-align: center; border-left: 0; border-bottom: 3px solid transparent; }
      .side > button.active { background: transparent; color: #ececec; font-weight: 600; border-bottom: 2px solid #e9cf4f; border-left-color: transparent; border-radius: 0; }
      main { padding: 10px 12px 40px; }
      .top { padding: 10px 14px; }
      .side { padding: 0; gap: 0; background: #121212; border-bottom: 1px solid #2c2c30; }
      .side > button { color: #9a9aa2; border-radius: 0; padding: 12px 0; border-bottom: 2px solid transparent; }
    }
    body.app-embed .top, body.app-embed .agent, body.app-embed [data-tab="routines"] { display: none; }
    body.app-embed .shell { flex-direction: column; min-height: 0; }
    body.app-embed .side { position: static; top: auto; width: 100%; flex-direction: row; flex-wrap: wrap; align-self: stretch; border-right: 0; border-bottom: 1px solid #333; padding: 8px; }
    body.app-embed .side > button { flex: 1; text-align: center; border-left: 0; border-bottom: 3px solid transparent; }
    body.app-embed .side > button.active { border-bottom-color: #f9f72e; border-left-color: transparent; }
    body.app-embed main { width: 100%; padding: 8px 0 20px; }
    body.app-embed .nav, body.app-embed .detail, body.app-embed .stats-columns { padding-left: 10px; padding-right: 10px; }
    body.app-embed .cal { padding: 6px 10px 0; }
    .side > button.share-tab { display: none; }
    body.app-embed .side > button.share-tab { display: block; }
    .share-screen { max-width: 420px; }
    .share-screen p { line-height: 1.5; color: #ddd; }
    .share-screen button { margin-top: 8px; display: inline-flex; align-items: center; gap: 8px; background: #5B2C6F; color: #fff; border: 0; border-radius: 10px; padding: 12px 16px; font: inherit; font-size: 16px; cursor: pointer; }
    body.locked .side { display: none; }
    .stats-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 16px; align-items: start; }
    .stats-columns h2 { margin: 0 0 12px; font-size: 18px; color: #f9f72e; }
    .totals, .averages { display: grid; gap: 12px; grid-template-columns: 1fr; margin: 0; }
    .card { background: #1e1e1e; border: 1px solid #444; border-radius: 16px; padding: 16px 18px; }
    .label { color: #bdbdbd; font-size: 13px; text-transform: uppercase; letter-spacing: .04em; }
    .value { font-size: 28px; color: #00ff88; margin-top: 6px; font-variant-numeric: tabular-nums; }
    .meta { color: #9e9e9e; margin-top: 6px; font-size: 14px; }
    .nav { display: flex; align-items: center; justify-content: space-between; margin: 8px 0 12px; }
    .nav button { background: #2a2a2a; color: #f9f72e; border: 1px solid #555; border-radius: 10px; padding: 8px 14px; font-size: 18px; cursor: pointer; }
    .nav h2 { margin: 0; font-size: 20px; }
    .cal { display: grid; grid-template-columns: repeat(7, 1fr); gap: 6px; }
    .dow { text-align: center; color: #888; font-size: 12px; padding-bottom: 4px; }
    .day { aspect-ratio: 1; border-radius: 10px; background: #1a1a1a; border: 1px solid #333; display: flex; flex-direction: column; align-items: center; justify-content: center; font-size: 14px; color: #777; }
    .day.empty { background: transparent; border-color: transparent; }
    .day.future { opacity: .35; }
    .day.off { color: #666; }
    .day.hit { background: #14331c; border-color: #00aa44; color: #e8ffe8; cursor: pointer; }
    .day.hit.est { background: #1b2a1c; border-color: #2e7d4f; color: #cfe8d4; }
    .day.hit.part { border-style: dashed; }
    .day.hit .dur { font-size: 10px; color: #00ff88; margin-top: 2px; }
    .day.hit.est .dur { color: #7dcc9a; }
    .day.today { outline: 2px solid #f9f72e; }
    .detail { margin-top: 16px; min-height: 1.5em; color: #ddd; }
    [data-panel] { display: none; }
    [data-panel].active { display: block; }
    details.agent { margin-top: 20px; border-top: 1px solid #2c2c30; border-bottom: 1px solid #2c2c30; color: #9a9aa2; }
    details.agent summary { list-style: none; cursor: pointer; padding: 12px 2px; font-size: 14px; display: flex; align-items: center; gap: 8px; }
    details.agent summary::-webkit-details-marker { display: none; }
    details.agent summary::before { content: "›"; display: inline-block; width: 10px; font-size: 15px; transition: transform .15s; }
    details.agent[open] summary::before { transform: rotate(90deg); }
    .agent-body { padding: 2px 2px 12px 20px; }
    .agent-field { display: flex; gap: 6px; }
    .agent input { flex: 1; min-width: 0; background: #141416; color: #9a9aa2; border: 1px solid #2c2c30; border-radius: 8px; padding: 9px; font: 12px ui-monospace, monospace; }
    .agent button { background: none; color: #e9cf4f; border: 1px solid #e9cf4f; border-radius: 8px; padding: 0 14px; cursor: pointer; font: inherit; font-weight: 600; }
    .agent .hint { margin: 6px 0 0; color: #6e6e76; font-size: 12px; }
  </style>
</head>
<body class="${bodyClass}"${options.lockTab ? ` data-lock="${options.lockTab}"` : ""}>
  <header class="top">
    <a class="brand" href="/">
      <svg class="plate" viewBox="0 0 32 32" aria-hidden="true"><circle cx="16" cy="16" r="14" fill="#5B2C6F" stroke="#f9f72e" stroke-width="2"/><circle cx="16" cy="16" r="5" fill="#121212"/></svg>
      <span class="word"><span class="gym">Gym</span> <span class="buddy">Buddy</span></span>
    </a>
    <span class="who"><b>${escapeHtml(options.displayName)}</b>${revisionInline()}</span>
  </header>
  <div class="shell">
  <aside class="side">
    <button type="button" data-tab="log" class="active">Log</button>
    <button type="button" data-tab="stats">Stats</button>
    <button type="button" class="share-tab" data-tab="share">Share</button>
    <button type="button" data-tab="routines">Routines</button>
  </aside>
  <main>
    <section data-panel="stats">
    <div class="stats-columns">
    <div>
    <h2>Totals</h2>
    <div class="totals">
      <div class="card">
        <div class="label">Total gym time</div>
        <div class="value" id="totalTime">—</div>
        <div class="meta" id="totalSince"></div>
      </div>
      <div class="card">
        <div class="label">Year to date</div>
        <div class="value" id="ytdTime">—</div>
        <div class="meta" id="ytdMeta"></div>
      </div>
    </div>
    </div>
    <div>
    <h2>Averages</h2>
    <div class="averages">
      <div class="card">
        <div class="label">Last 10 workouts</div>
        <div class="value" id="avg10">—</div>
        <div class="meta" id="avg10Meta"></div>
      </div>
      <div class="card">
        <div class="label">Last 30 workouts</div>
        <div class="value" id="avg30">—</div>
        <div class="meta" id="avg30Meta"></div>
      </div>
      <div class="card">
        <div class="label">Last 100 workouts</div>
        <div class="value" id="avg100">—</div>
        <div class="meta" id="avg100Meta"></div>
      </div>
    </div>
    </div>
    </div>
    </section>
    <section data-panel="log" class="active">
    <div class="nav">
      <button type="button" id="prev">&lsaquo;</button>
      <h2 id="monthLabel"></h2>
      <button type="button" id="next">&rsaquo;</button>
    </div>
    <div class="cal" id="cal"></div>
    <div class="detail" id="detail">Tap a green day for that session.</div>
    </section>
    <section data-panel="share">
      <div class="share-screen">
        <p>This link lets anyone who receives it view your gym log, stats, and routine. Share it with an AI agent, or send it to yourself to open your profile on a computer.</p>
        <button type="button" id="startShare">
          <svg viewBox="0 0 24 24" width="22" height="22" aria-hidden="true"><path fill="currentColor" d="M18 16.08c-.76 0-1.44.3-1.96.77L8.91 12.7c.05-.23.09-.46.09-.7s-.04-.47-.09-.7l7.05-4.11c.54.5 1.25.81 2.04.81 1.66 0 3-1.34 3-3s-1.34-3-3-3-3 1.34-3 3c0 .24.04.47.09.7L8.04 9.81C7.5 9.31 6.79 9 6 9c-1.66 0-3 1.34-3 3s1.34 3 3 3c.79 0 1.5-.31 2.04-.81l7.12 4.16c-.05.21-.08.43-.08.65 0 1.61 1.31 2.92 2.92 2.92s2.92-1.31 2.92-2.92-1.31-2.92-2.92-2.92z"/></svg>
          Share
        </button>
      </div>
    </section>
    <section data-panel="routines">
    ${routines}
    </section>
    <details class="agent">
      <summary>Agent access</summary>
      <div class="agent-body">
        <div class="agent-field">
          <input id="agentLink" readonly aria-label="Agent link" value="${escapeHtml(options.agentUrl)}" />
          <button type="button" id="copyAgent">Copy</button>
        </div>
        <p class="hint">This link lets an agent see and edit this profile.</p>
      </div>
    </details>
  </main>
  </div>
  <script>
    const workouts = ${JSON.stringify(index)};
    const byDate = Object.fromEntries(workouts.map(w => [w.date, w]));
    const weekdays = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
    let view = new Date();
    view.setDate(1);

    function fmtDur(ms) {
      const s = Math.max(0, Math.floor(ms / 1000));
      const h = Math.floor(s / 3600);
      const m = Math.floor((s % 3600) / 60);
      if (h <= 0) return m + "m";
      if (m === 0) return h + "h";
      return h + "h " + String(m).padStart(2, "0") + "m";
    }
    function ymd(d) {
      return d.getFullYear() + "-" + String(d.getMonth()+1).padStart(2,"0") + "-" + String(d.getDate()).padStart(2,"0");
    }

    function totals() {
      const year = new Date().getFullYear();
      let allMs = 0, ytdMs = 0, ytdWorkouts = 0, ytdSets = 0;
      for (const w of workouts) {
        allMs += w.durationMs || 0;
        if (w.date.startsWith(String(year))) {
          ytdMs += w.durationMs || 0;
          ytdWorkouts += 1;
          ytdSets += w.setCount || 0;
        }
      }
      const first = workouts[0]?.date;
      document.getElementById("totalTime").textContent = fmtHours(allMs);
      document.getElementById("totalSince").textContent = first
        ? workouts.length + " workout" + (workouts.length === 1 ? "" : "s") + " since " + first
        : "No workouts pushed yet";
      document.getElementById("ytdTime").textContent = fmtHours(ytdMs);
      document.getElementById("ytdMeta").textContent = ytdWorkouts + " workout" + (ytdWorkouts === 1 ? "" : "s") + " · " + ytdSets + " sets in " + year;
    }

    function fmtHours(ms) {
      return Math.floor(Math.max(0, ms) / 3600000) + "h";
    }
    function fmtMinutes(ms) {
      return Math.max(0, Math.round(ms / 60000)) + " min";
    }
    function sessionAvg(list) {
      if (!list.length) return { value: "0 min", meta: "No sessions" };
      let sum = 0, estimated = 0;
      for (const w of list) {
        sum += w.durationMs || 0;
        if (w.estimated) estimated += 1;
      }
      let meta = "avg session · " + list.length + " workout" + (list.length === 1 ? "" : "s");
      if (estimated) meta += " · " + estimated + " estimated";
      return { value: fmtMinutes(sum / list.length), meta: meta };
    }
    function setAvg(id, result) {
      document.getElementById(id).textContent = result.value;
      document.getElementById(id + "Meta").textContent = result.meta;
    }
    function averages() {
      const today = ymd(new Date());
      const past = workouts.filter((w) => w.date <= today);
      setAvg("avg10", sessionAvg(past.slice(-10)));
      setAvg("avg30", sessionAvg(past.slice(-30)));
      setAvg("avg100", sessionAvg(past.slice(-100)));
    }

    function render() {
      const year = view.getFullYear();
      const month = view.getMonth();
      document.getElementById("monthLabel").textContent = view.toLocaleString(undefined, { month: "long", year: "numeric" });
      const cal = document.getElementById("cal");
      cal.innerHTML = weekdays.map(d => '<div class="dow">' + d + "</div>").join("");
      const firstDow = new Date(year, month, 1).getDay();
      const daysInMonth = new Date(year, month + 1, 0).getDate();
      const today = ymd(new Date());
      for (let i = 0; i < firstDow; i++) cal.insertAdjacentHTML("beforeend", '<div class="day empty"></div>');
      for (let day = 1; day <= daysInMonth; day++) {
        const date = year + "-" + String(month + 1).padStart(2, "0") + "-" + String(day).padStart(2, "0");
        const w = byDate[date];
        const future = date > today;
        const cls = ["day", w ? "hit" : "off", w && w.estimated ? "est" : "", w && w.partial ? "part" : "", future ? "future" : "", date === today ? "today" : ""].join(" ");
        const dur = w ? '<div class="dur">' + fmtDur(w.durationMs) + "</div>" : "";
        cal.insertAdjacentHTML("beforeend", '<div class="' + cls + '" data-date="' + date + '"><div>' + day + "</div>" + dur + "</div>");
      }
      cal.querySelectorAll(".day.hit").forEach((el) => {
        el.addEventListener("click", () => {
          const w = byDate[el.dataset.date];
          if (!w) return;
          document.getElementById("detail").textContent =
            w.date + " · " + fmtDur(w.durationMs) + " · " + w.exerciseCount + " exercises · " + w.setCount + " sets" + (w.isMakeup ? " · makeup" : "") + (w.partial ? " · partial" : "") + (w.estimated ? " · estimated" : "");
        });
      });
    }

    document.getElementById("prev").onclick = () => { view.setMonth(view.getMonth() - 1); render(); };
    document.getElementById("next").onclick = () => { view.setMonth(view.getMonth() + 1); render(); };
    totals();
    averages();
    render();

    const tabKey = ${JSON.stringify(`gb-user-tab:${options.slug}`)};
    function showTab(name, store) {
      if (name !== "log" && name !== "stats" && name !== "routines" && name !== "share") name = "log";
      document.querySelectorAll("[data-tab]").forEach(function (btn) {
        btn.classList.toggle("active", btn.getAttribute("data-tab") === name);
      });
      document.querySelectorAll("[data-panel]").forEach(function (panel) {
        panel.classList.toggle("active", panel.getAttribute("data-panel") === name);
      });
      window.dispatchEvent(new Event("resize"));
      if (store && !document.body.classList.contains("locked")) localStorage.setItem(tabKey, name);
      if (document.body.classList.contains("locked")) return;
      var next = new URL(location.href);
      next.searchParams.delete("view");
      next.searchParams.set("tab", name);
      if (document.body.classList.contains("app-embed")) next.searchParams.set("app", "1");
      history.replaceState(null, "", next);
    }
    var startShare = document.getElementById("startShare");
    if (startShare) startShare.onclick = function () {
      var input = document.getElementById("agentLink");
      var value = input ? input.value : "";
      if (window.GymBuddy && window.GymBuddy.share) window.GymBuddy.share(value);
    };
    var copyAgent = document.getElementById("copyAgent");
    if (copyAgent) copyAgent.onclick = function () {
      var input = document.getElementById("agentLink");
      var value = input ? input.value : "";
      if (navigator.clipboard && navigator.clipboard.writeText) {
        navigator.clipboard.writeText(value).then(function () { copyAgent.textContent = "Copied"; });
      } else if (input) {
        input.focus();
        input.select();
      }
    };
    document.querySelectorAll("[data-tab]").forEach(function (btn) {
      btn.addEventListener("click", function () { showTab(btn.getAttribute("data-tab"), true); });
    });
    var params = new URL(location.href).searchParams;
    var fromView = params.get("view");
    var initial = document.body.getAttribute("data-lock") || params.get("tab") || (fromView === "totals" || fromView === "averages" ? "stats" : fromView === "log" ? "log" : localStorage.getItem(tabKey) || "log");
    if (document.body.classList.contains("app-embed") && initial === "routines") initial = "log";
    showTab(initial, !document.body.classList.contains("locked"));
  </script>
</body>
</html>`;
}
