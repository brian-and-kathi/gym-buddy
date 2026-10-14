import { escapeHtml } from "./http";
import { revisionMark } from "./revision";

export function renderRoutineEditor(options: {
  apiBase: string;
  calendarHref: string;
  slug: string;
  signedIn?: boolean;
  accessToken?: string;
  embedded?: boolean;
}): string {
  const embedded = options.embedded === true;
  const body = `<div class="routine-root">
  <style>
    .routine-root { --acc: #e9cf4f; --card: #1b1b1d; --line: #2c2c30; --text: #ececec; --mute: #9a9aa2; --dim: #6e6e76; --field: #141416; color: var(--text); }
    .routine-root * { box-sizing: border-box; }
    .routine-root a { color: #00ffff; }
    .routine-root .layout { max-width: 720px; margin: 0 auto; padding: 0; }
    .routine-root input, .routine-root select, .routine-root textarea, .routine-root button { font: inherit; }
    .routine-root .bar { display: flex; align-items: center; gap: 8px; margin-bottom: 10px; }
    .routine-root .days { display: flex; gap: 4px; flex: 1; min-width: 0; }
    .routine-root .days button { flex: 1; min-width: 0; background: none; border: 1px solid var(--line); color: var(--mute); border-radius: 8px; padding: 6px 0; font-size: 13px; line-height: 1.1; cursor: pointer; min-height: 44px; }
    .routine-root .days button small { display: block; font-size: 10px; color: var(--dim); min-height: 11px; }
    .routine-root .days button.active { border-color: var(--acc); color: var(--text); }
    .routine-root .days button.active small { color: var(--acc); }
    .routine-root .save { background: var(--acc); color: #1a1606; border: 0; border-radius: 8px; font-weight: 700; padding: 0 14px; min-height: 44px; cursor: pointer; }
    .routine-root .status { min-height: 0; color: var(--mute); margin: 0 0 8px; font-size: 14px; }
    .routine-root .status:empty { display: none; }
    .routine-root .status.err { color: #ff8a9a; }
    .routine-root .status.ok { color: #8fc5a0; }
    .routine-root .rest { color: var(--mute); font-style: italic; margin: 8px 0 12px; }
    .routine-root .ex { display: flex; background: var(--card); border: 1px solid var(--line); border-radius: 12px; margin-bottom: 8px; overflow: hidden; }
    .routine-root .ex.dragging { opacity: .55; border-color: var(--acc); }
    .routine-root .ex.drop-before { box-shadow: 0 -3px 0 var(--acc); }
    .routine-root .ex.drop-after { box-shadow: 0 3px 0 var(--acc); }
    .routine-root .rail { width: 40px; flex: none; display: flex; flex-direction: column; align-items: center; gap: 2px; padding: 4px 0; border-right: 1px solid var(--line); }
    .routine-root .rail button { background: none; border: 0; color: var(--mute); width: 40px; height: 40px; font-size: 15px; padding: 0; cursor: pointer; }
    .routine-root .rail button:disabled { opacity: .3; cursor: default; }
    .routine-root .rail .grip { font-size: 14px; letter-spacing: -2px; color: var(--dim); cursor: grab; touch-action: none; }
    .routine-root .rail .del { margin-top: auto; color: #8a5a5f; font-size: 13px; }
    .routine-root .ex-body { flex: 1; min-width: 0; padding: 8px 10px; }
    .routine-root .h { display: flex; align-items: center; gap: 8px; }
    .routine-root .h .n { flex: 1; min-width: 0; background: none; border: 1px solid transparent; border-radius: 6px; color: var(--text); font-weight: 600; font-size: 16px; padding: 4px 4px; margin-left: -5px; text-overflow: ellipsis; }
    .routine-root .h .n:focus { outline: none; border-color: var(--line); background: var(--field); }
    .routine-root .pill { appearance: none; -webkit-appearance: none; font-size: 11px; font-weight: 600; text-transform: lowercase; border-radius: 999px; padding: 3px 9px; border: 1px solid #3a3a40; color: var(--mute); background: transparent; cursor: pointer; }
    .routine-root .pill option { background: #1b1b1d; color: var(--text); }
    .routine-root .pill.hard { color: #e7a07a; border-color: #5a3a2c; }
    .routine-root .pill.easy { color: #8fc5a0; border-color: #2f4a38; }
    .routine-root .pillwrap { position: relative; flex: none; }
    .routine-root .pillwrap .pill { padding-right: 20px; }
    .routine-root .pillwrap::after { content: "▾"; position: absolute; right: 8px; top: 50%; transform: translateY(-50%); font-size: 10px; color: var(--mute); pointer-events: none; }
    .routine-root .nums { display: grid; grid-template-columns: repeat(3, 1fr); gap: 6px; margin-top: 6px; }
    .routine-root .num { position: relative; background: var(--field); border: 1px solid var(--line); border-radius: 9px; height: 46px; text-align: center; }
    .routine-root .num label { position: absolute; top: 3px; left: 0; right: 0; font-size: 10px; letter-spacing: .06em; text-transform: uppercase; opacity: .85; pointer-events: none; }
    .routine-root .num .v { gap: 0; display: flex; align-items: center; justify-content: space-between; height: 100%; padding-top: 9px; }
    .routine-root .num .v input { width: 100%; min-width: 0; flex: 1; background: none; border: 0; padding: 0; text-align: center; font-size: 19px; font-weight: 600; font-variant-numeric: tabular-nums; -moz-appearance: textfield; }
    .routine-root .num .v input::-webkit-outer-spin-button, .routine-root .num .v input::-webkit-inner-spin-button { -webkit-appearance: none; margin: 0; }
    .routine-root .num .v input:focus { outline: none; }
    .routine-root .num:focus-within { border-color: #4a4a50; }
    .routine-root .num .v button { flex: none; background: none; border: 0; color: var(--mute); width: 28px; height: 44px; margin-top: -9px; padding-top: 9px; font-size: 18px; cursor: pointer; }
    .routine-root .num.weight label, .routine-root .num.weight input { color: #00FFFF; }
    .routine-root .num.reps label, .routine-root .num.reps input { color: #F1C40F; }
    .routine-root .num.sets label, .routine-root .num.sets input { color: #00FF00; }
    .routine-root .notes { display: block; width: 100%; margin-top: 8px; background: var(--field); border: 1px solid #34343a; border-radius: 9px; padding: 9px 11px; color: var(--text); font-size: 1rem; line-height: 1.5; min-height: calc(4 * 1.5rem + 20px); field-sizing: content; resize: none; overflow: hidden; }
    .routine-root .notes:focus { outline: none; border-color: var(--acc); }
    .routine-root .add { width: 100%; background: none; border: 1px dashed #3a3a40; color: var(--mute); border-radius: 12px; padding: 11px; margin-top: 2px; cursor: pointer; }
    .routine-root .foot { margin-top: 14px; border-top: 1px solid var(--line); }
    .routine-root .row { display: flex; width: 100%; align-items: center; gap: 8px; padding: 12px 2px; color: var(--mute); font-size: 14px; background: none; border: 0; border-bottom: 1px solid var(--line); text-align: left; cursor: pointer; }
    .routine-root .row .chev { display: inline-block; width: 10px; font-size: 15px; }
    .routine-root .card { background: var(--card); border: 1px solid var(--line); border-radius: 16px; padding: 14px; }
    .routine-root .gate input, .routine-root .modal textarea { background: var(--field); color: var(--text); border: 1px solid #34343a; border-radius: 8px; padding: 8px 10px; }
    .routine-root .modal-actions button, .routine-root .gate button { background: none; color: var(--acc); border: 1px solid var(--acc); border-radius: 8px; padding: 8px 14px; cursor: pointer; font-weight: 600; }
    .routine-root .modal-actions button.primary, .routine-root .gate button.primary { background: var(--acc); color: #1a1606; }
    .routine-root .gate { position: fixed; inset: 0; background: #121212ee; display: none; align-items: center; justify-content: center; padding: 24px; z-index: 20; }
    .routine-root .gate.show { display: flex; }
    .routine-root .gate .box { width: min(420px, 100%); }
    .routine-root .hidden { display: none !important; }
    .routine-root .modal { position: fixed; inset: 0; background: #000a; display: none; align-items: center; justify-content: center; padding: 16px; z-index: 20; }
    .routine-root .modal.show { display: flex; }
    .routine-root .modal .box { width: min(720px, 100%); max-height: 90vh; overflow: auto; }
    .routine-root .modal textarea { width: 100%; min-height: 420px; font-family: ui-monospace, monospace; font-size: 13px; }
  </style>
  <div class="layout">
    <div class="bar">
      <div class="days" id="dayTabs"></div>
      <button type="button" class="save" id="saveBtn">Save</button>
    </div>
    <div class="status" id="status"></div>
    <p class="rest hidden" id="restHint">Rest day — add an exercise to train.</p>
    <div id="exercises"></div>
    <button type="button" class="add" id="addExBtn">+ Add exercise</button>
    <div class="foot">
      <button type="button" class="row" id="pasteBtn"><span class="chev">›</span>Edit as JSON</button>
    </div>
  </div>
  <div class="gate" id="gate">
    <div class="box card">
      <h2>Profile token</h2>
      <p>Paste the token from your connect page. It is the same value saved in the app.</p>
      <input id="tokenInput" type="text" placeholder="Token" style="width:100%;margin:12px 0;" />
      <button type="button" class="primary" id="tokenBtn">Continue</button>
      <p class="status err" id="tokenErr"></p>
    </div>
  </div>
  <div class="modal" id="pasteModal">
    <div class="box card">
      <h2>Edit as JSON</h2>
      <p>This is the current week. Edit it, then apply to the form.</p>
      <textarea id="pasteText"></textarea>
      <div class="modal-actions" style="display:flex;gap:8px;margin-top:12px;">
        <button type="button" class="primary" id="pasteApply">Apply</button>
        <button type="button" id="pasteClose">Cancel</button>
      </div>
    </div>
  </div>
  <script>
    const DAYS = ["Sun","Mon","Tue","Wed","Thu","Fri","Sat"];
    const API_BASE = ${JSON.stringify(options.apiBase)};
    const TOKEN_KEY = ${JSON.stringify(`gb_token_${options.slug}`)};
    const signedIn = ${options.signedIn === true ? "true" : "false"};
    let token = ${JSON.stringify(options.accessToken ?? "")} || sessionStorage.getItem(TOKEN_KEY) || "";
    let authRequired = false;
    let versions = [];
    let currentId = null;
    let days = emptyDays();
    let activeDay = "Mon";

    function emptyDays() {
      return DAYS.map(function (d) { return { dayOfWeek: d, exercises: [] }; });
    }
    function dayOf(name) {
      return days.find(function (d) { return d.dayOfWeek === name; });
    }
    function setStatus(msg, kind) {
      var el = document.getElementById("status");
      el.textContent = msg || "";
      el.className = "status" + (kind ? " " + kind : "");
    }
    function headers() {
      var h = { "Content-Type": "application/json" };
      if (token) h.Authorization = "Bearer " + token;
      return h;
    }
    function api(path, opts) {
      opts = opts || {};
      return fetch(path, {
        method: opts.method || "GET",
        headers: headers(),
        body: opts.body ? JSON.stringify(opts.body) : undefined
      }).then(function (res) {
        return res.text().then(function (text) {
          var data = null;
          try { data = text ? JSON.parse(text) : null; } catch (e) { data = { error: text }; }
          if (res.status === 401) {
            sessionStorage.removeItem(TOKEN_KEY);
            token = "";
            showGate(true);
            throw new Error("unauthorized");
          }
          if (!res.ok) throw new Error((data && data.error) || ("HTTP " + res.status));
          return data;
        });
      });
    }

    function showGate(on) {
      document.getElementById("gate").classList.toggle("show", on);
    }

    function renderVersions() {}

    function renderTabs() {
      var root = document.getElementById("dayTabs");
      root.innerHTML = "";
      DAYS.forEach(function (d) {
        var btn = document.createElement("button");
        btn.type = "button";
        var n = dayOf(d).exercises.length;
        btn.appendChild(document.createTextNode(d));
        var small = document.createElement("small");
        small.textContent = n ? String(n) : "";
        btn.appendChild(small);
        btn.setAttribute("aria-label", d + (n ? ", " + n + " exercise" + (n === 1 ? "" : "s") : ", rest day"));
        if (d === activeDay) btn.className = "active";
        btn.addEventListener("click", function () { activeDay = d; renderDay(); renderTabs(); });
        root.appendChild(btn);
      });
    }

    var NATIVE_FIELD_SIZING = typeof CSS !== "undefined" && CSS.supports && CSS.supports("field-sizing", "content");
    function autoGrow(el) {
      if (NATIVE_FIELD_SIZING) return;
      el.style.height = "auto";
      el.style.height = (el.scrollHeight + 2) + "px";
    }
    function growAll() {
      document.querySelectorAll(".routine-root .notes").forEach(autoGrow);
    }
    window.addEventListener("resize", growAll);

    function move(list, from, to) {
      if (to < 0 || to >= list.length || from === to) return;
      var item = list.splice(from, 1)[0];
      list.splice(to, 0, item);
      renderDay();
    }

    function stepper(ex, key, label, step) {
      var wrap = document.createElement("div");
      wrap.className = "num " + key;
      var lab = document.createElement("label");
      lab.textContent = label;
      var id = "f" + Math.random().toString(36).slice(2, 9);
      lab.htmlFor = id;
      var v = document.createElement("div");
      v.className = "v";
      var minus = document.createElement("button");
      minus.type = "button"; minus.textContent = "−"; minus.setAttribute("aria-label", "Less " + label.toLowerCase());
      var plus = document.createElement("button");
      plus.type = "button"; plus.textContent = "+"; plus.setAttribute("aria-label", "More " + label.toLowerCase());
      var inp = document.createElement("input");
      inp.type = "text"; inp.inputMode = "numeric"; inp.pattern = "[0-9]*"; inp.autocomplete = "off"; inp.id = id;
      inp.value = String(ex[key]);
      inp.addEventListener("input", function () {
        var clean = inp.value.replace(/[^0-9]/g, "").slice(0, 4);
        if (clean !== inp.value) inp.value = clean;
        ex[key] = Number(clean || 0);
      });
      inp.addEventListener("blur", function () { inp.value = String(ex[key]); });
      function bump(dir) {
        var cur = Number(ex[key]) || 0;
        var next = dir > 0 ? Math.floor(cur / step) * step + step : Math.ceil(cur / step) * step - step;
        ex[key] = Math.max(0, next);
        inp.value = String(ex[key]);
      }
      minus.addEventListener("click", function () { bump(-1); });
      plus.addEventListener("click", function () { bump(1); });
      v.appendChild(minus); v.appendChild(inp); v.appendChild(plus);
      wrap.appendChild(lab); wrap.appendChild(v);
      return wrap;
    }

    function railBtn(label, text, cls, fn) {
      var b = document.createElement("button");
      b.type = "button";
      b.textContent = text;
      b.setAttribute("aria-label", label);
      b.title = label;
      if (cls) b.className = cls;
      if (fn) b.addEventListener("click", fn);
      return b;
    }

    function enableDrag(grip, card, list, idx) {
      grip.addEventListener("pointerdown", function (e) {
        e.preventDefault();
        grip.setPointerCapture(e.pointerId);
        card.classList.add("dragging");
        var cards = Array.prototype.slice.call(document.querySelectorAll("#exercises .ex"));
        var target = idx;
        function over(y) {
          cards.forEach(function (c) { c.classList.remove("drop-before", "drop-after"); });
          target = idx;
          for (var i = 0; i < cards.length; i++) {
            var r = cards[i].getBoundingClientRect();
            if (y >= r.top && y <= r.bottom) {
              var before = y < r.top + r.height / 2;
              if (i !== idx) cards[i].classList.add(before ? "drop-before" : "drop-after");
              target = before ? i : i + 1;
              if (target > idx) target -= 1;
              break;
            }
          }
        }
        function onMove(ev) { over(ev.clientY); }
        function onUp() {
          grip.removeEventListener("pointermove", onMove);
          grip.removeEventListener("pointerup", onUp);
          grip.removeEventListener("pointercancel", onUp);
          card.classList.remove("dragging");
          cards.forEach(function (c) { c.classList.remove("drop-before", "drop-after"); });
          if (target !== idx) move(list, idx, target);
        }
        grip.addEventListener("pointermove", onMove);
        grip.addEventListener("pointerup", onUp);
        grip.addEventListener("pointercancel", onUp);
      });
    }

    function renderDay() {
      var list = dayOf(activeDay).exercises;
      document.getElementById("restHint").classList.toggle("hidden", list.length > 0);
      var root = document.getElementById("exercises");
      root.innerHTML = "";
      list.forEach(function (ex, idx) {
        var card = document.createElement("div");
        card.className = "ex";

        var rail = document.createElement("div");
        rail.className = "rail";
        var grip = railBtn("Drag to reorder", "⋮⋮", "grip");
        enableDrag(grip, card, list, idx);
        var up = railBtn("Move up", "↑", "", function () { move(list, idx, idx - 1); });
        var down = railBtn("Move down", "↓", "", function () { move(list, idx, idx + 1); });
        up.disabled = idx === 0;
        down.disabled = idx >= list.length - 1;
        var del = railBtn("Remove exercise", "✕", "del", function () {
          if (String(ex.title).trim() && !confirm("Remove " + ex.title + "?")) return;
          list.splice(idx, 1); renderDay(); renderTabs();
        });
        rail.appendChild(grip); rail.appendChild(up); rail.appendChild(down); rail.appendChild(del);
        card.appendChild(rail);

        var body = document.createElement("div");
        body.className = "ex-body";
        var head = document.createElement("div");
        head.className = "h";
        var title = document.createElement("input");
        title.type = "text";
        title.className = "n";
        title.value = ex.title;
        title.placeholder = "Exercise name";
        title.setAttribute("aria-label", "Exercise name");
        title.addEventListener("input", function () { ex.title = title.value; });
        var pillWrap = document.createElement("span");
        pillWrap.className = "pillwrap";
        var rating = document.createElement("select");
        rating.setAttribute("aria-label", "Difficulty");
        ["easy","good","hard"].forEach(function (r) {
          var o = document.createElement("option");
          o.value = r; o.textContent = r;
          if (ex.easyGoodOrHard === r) o.selected = true;
          rating.appendChild(o);
        });
        rating.className = "pill " + (ex.easyGoodOrHard || "good");
        rating.addEventListener("change", function () { ex.easyGoodOrHard = rating.value; rating.className = "pill " + rating.value; });
        pillWrap.appendChild(rating);
        head.appendChild(title);
        head.appendChild(pillWrap);
        body.appendChild(head);

        var nums = document.createElement("div");
        nums.className = "nums";
        nums.appendChild(stepper(ex, "weight", "Weight", 5));
        nums.appendChild(stepper(ex, "reps", "Reps", 1));
        nums.appendChild(stepper(ex, "sets", "Sets", 1));
        body.appendChild(nums);

        var notes = document.createElement("textarea");
        notes.className = "notes";
        notes.placeholder = "Notes / cues";
        notes.setAttribute("aria-label", "Notes");
        notes.value = ex.notes || "";
        notes.addEventListener("input", function () { ex.notes = notes.value; autoGrow(notes); });
        body.appendChild(notes);
        card.appendChild(body);
        root.appendChild(card);
      });
      growAll();
    }

    function blankExercise() {
      return { title: "", weight: 0, reps: 10, sets: 3, notes: "", easyGoodOrHard: "good" };
    }

    function applyRecord(rec) {
      days = rec.days || emptyDays();
      renderTabs();
      renderDay();
    }

    function showEmpty() {
      days = emptyDays();
      activeDay = "Mon";
      renderTabs();
      renderDay();
      setStatus("No routine stored yet.");
    }

    function save() {
      var missing = days.some(function (d) {
        return d.exercises.some(function (ex) { return !String(ex.title).trim(); });
      });
      if (missing) { setStatus("Every exercise needs a title.", "err"); return; }
      setStatus("Saving…");
      api(API_BASE + "/routines", { method: "PUT", body: { days: days } }).then(function (rec) {
        applyRecord(rec);
        setStatus("Saved.", "ok");
      }).catch(function (err) { setStatus(err.message, "err"); });
    }

    document.getElementById("saveBtn").onclick = save;
    document.getElementById("addExBtn").onclick = function () {
      dayOf(activeDay).exercises.push(blankExercise());
      renderDay();
      renderTabs();
    };
    document.getElementById("pasteBtn").onclick = function () {
      document.getElementById("pasteText").value = JSON.stringify(days, null, 2);
      document.getElementById("pasteModal").classList.add("show");
    };
    document.getElementById("pasteClose").onclick = function () {
      document.getElementById("pasteModal").classList.remove("show");
    };
    document.getElementById("pasteApply").onclick = function () {
      try {
        var parsed = JSON.parse(document.getElementById("pasteText").value);
        var incoming = Array.isArray(parsed) ? parsed : (parsed && parsed.days);
        if (!Array.isArray(incoming) || incoming.length !== 7) throw new Error("Need an array of 7 days");
        days = DAYS.map(function (name) {
          var found = incoming.find(function (d) {
            var n = String(d.dayOfWeek || "").slice(0, 3);
            return n.toLowerCase() === name.toLowerCase();
          });
          var exercises = ((found && found.exercises) || []).map(function (ex) {
            return {
              title: ex.title || "",
              weight: Number(ex.weight || 0),
              reps: Number(ex.reps || 10),
              sets: Number(ex.sets || 3),
              notes: ex.notes || "",
              easyGoodOrHard: ex.easyGoodOrHard || ex.rating || "good"
            };
          });
          return { dayOfWeek: name, exercises: exercises };
        });
        document.getElementById("pasteModal").classList.remove("show");
        renderTabs();
        renderDay();
        setStatus("JSON applied. Save to keep it on your profile.", "ok");
      } catch (err) {
        setStatus(err.message || "Invalid JSON", "err");
      }
    };
    document.getElementById("tokenBtn").onclick = function () {
      token = document.getElementById("tokenInput").value.trim();
      sessionStorage.setItem(TOKEN_KEY, token);
      document.getElementById("tokenErr").textContent = "";
      boot().catch(function (err) {
        document.getElementById("tokenErr").textContent = err.message;
      });
    };

    function boot() {
      return api(API_BASE + "/routines").then(function (data) {
        showGate(false);
        if (data && data.routine) applyRecord(data.routine);
        else showEmpty();
      });
    }

    if (signedIn) {
      boot().catch(function (err) { setStatus(err.message, "err"); });
    } else {
    fetch("/health").then(function (r) { return r.json(); }).then(function (h) {
      authRequired = !!h.authRequired;
      if (authRequired && !token) { showGate(true); return; }
      boot().catch(function (err) {
        if (authRequired) {
          showGate(true);
          document.getElementById("tokenErr").textContent = err.message;
        } else {
          setStatus(err.message, "err");
        }
      });
    }).catch(function () { boot(); });
    }
  </script>
</div>`;
  if (embedded) return body;
  return `<!DOCTYPE html>
<html lang="en">
<head>
  <meta charset="utf-8" />
  <meta name="viewport" content="width=device-width, initial-scale=1" />
  <meta name="referrer" content="no-referrer" />
  <title>Gym Buddy routines</title>
</head>
<body style="margin:0;background:#121212;font-family:ui-sans-serif,system-ui,Roboto,sans-serif;padding:10px 12px">
${revisionMark()}
${body}
</body>
</html>`;
}
