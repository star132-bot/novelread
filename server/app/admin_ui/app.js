/* MKread data console. Plain DOM, no build step; every value from the API is inserted as text. */
"use strict";

(() => {
  // ------------------------------------------------------------------ theme (before first paint)
  const THEME_KEY = "mkread-admin-theme";
  const storedTheme = () => { try { return localStorage.getItem(THEME_KEY) || "system"; } catch { return "system"; } };
  const applyTheme = (theme) => {
    if (theme === "light" || theme === "dark") document.documentElement.setAttribute("data-theme", theme);
    else document.documentElement.removeAttribute("data-theme");
  };
  const saveTheme = (theme) => { try { localStorage.setItem(THEME_KEY, theme); } catch { /* private mode */ } applyTheme(theme); };
  applyTheme(storedTheme());

  // ------------------------------------------------------------------ DOM helpers
  const SVG_NS = "http://www.w3.org/2000/svg";

  function append(parent, children) {
    for (const child of children.flat(Infinity)) {
      if (child === null || child === undefined || child === false || child === "") continue;
      parent.append(child instanceof Node ? child : document.createTextNode(String(child)));
    }
    return parent;
  }

  /** Replaces an element's children, skipping null/false like append (native replaceChildren prints "null"). */
  const fill = (el, ...children) => { el.replaceChildren(); return append(el, children); };

  function h(tag, attrs, ...children) {
    const el = document.createElement(tag);
    for (const [key, value] of Object.entries(attrs || {})) {
      if (value === null || value === undefined || value === false) continue;
      if (key === "class") el.className = value;
      else if (key.startsWith("on") && typeof value === "function") el.addEventListener(key.slice(2).toLowerCase(), value);
      else if (key === "value") el.value = value;
      else if (key === "checked" || key === "disabled" || key === "selected" || key === "indeterminate") el[key] = Boolean(value);
      else el.setAttribute(key, value === true ? "" : String(value));
    }
    return append(el, children);
  }

  function s(tag, attrs, ...children) {
    const el = document.createElementNS(SVG_NS, tag);
    for (const [key, value] of Object.entries(attrs || {})) el.setAttribute(key, String(value));
    return append(el, children);
  }

  const ICONS = {
    overview: "M3 13h8V3H3zm10 8h8V11h-8zM3 21h8v-6H3zm10-18v6h8V3z",
    users: "M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2M9 11a4 4 0 1 0 0-8 4 4 0 0 0 0 8zm13 10v-2a4 4 0 0 0-3-3.87M16 3.13a4 4 0 0 1 0 7.75",
    audit: "M9 11l3 3L22 4M21 12v7a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h11",
    books: "M4 19.5A2.5 2.5 0 0 1 6.5 17H20V3H6.5A2.5 2.5 0 0 0 4 5.5zM4 19.5A2.5 2.5 0 0 0 6.5 22H20v-5",
    voices: "M12 1a3 3 0 0 0-3 3v8a3 3 0 0 0 6 0V4a3 3 0 0 0-3-3zm7 10v1a7 7 0 0 1-14 0v-1M12 19v4M8 23h8",
    releases: "M21 16V8a2 2 0 0 0-1-1.73l-7-4a2 2 0 0 0-2 0l-7 4A2 2 0 0 0 3 8v8a2 2 0 0 0 1 1.73l7 4a2 2 0 0 0 2 0l7-4A2 2 0 0 0 21 16zM3.3 7L12 12l8.7-5M12 22V12",
    notes: "M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8zM14 2v6h6M16 13H8M16 17H8M10 9H8",
    files: "M22 19a2 2 0 0 1-2 2H4a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h5l2 3h9a2 2 0 0 1 2 2z",
    system: "M12 15a3 3 0 1 0 0-6 3 3 0 0 0 0 6zm7.4-3a7.4 7.4 0 0 0-.1-1.2l2-1.6-2-3.4-2.4 1a7 7 0 0 0-2-1.2L14.5 3h-4l-.4 2.6a7 7 0 0 0-2 1.2l-2.4-1-2 3.4 2 1.6a7.4 7.4 0 0 0 0 2.4l-2 1.6 2 3.4 2.4-1a7 7 0 0 0 2 1.2l.4 2.6h4l.4-2.6a7 7 0 0 0 2-1.2l2.4 1 2-3.4-2-1.6c.1-.4.1-.8.1-1.2z",
    search: "M11 19a8 8 0 1 0 0-16 8 8 0 0 0 0 16zm10 2l-4.35-4.35",
    menu: "M3 6h18M3 12h18M3 18h18",
    close: "M18 6L6 18M6 6l12 12",
    sun: "M12 17a5 5 0 1 0 0-10 5 5 0 0 0 0 10zM12 1v2M12 21v2M4.2 4.2l1.4 1.4M18.4 18.4l1.4 1.4M1 12h2M21 12h2M4.2 19.8l1.4-1.4M18.4 5.6l1.4-1.4",
    moon: "M21 12.8A9 9 0 1 1 11.2 3a7 7 0 0 0 9.8 9.8z",
    auto: "M12 22a10 10 0 1 0 0-20 10 10 0 0 0 0 20zM12 2v20",
    logout: "M9 21H5a2 2 0 0 1-2-2V5a2 2 0 0 1 2-2h4M16 17l5-5-5-5M21 12H9",
    download: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M7 10l5 5 5-5M12 15V3",
    upload: "M21 15v4a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2v-4M17 8l-5-5-5 5M12 3v12",
    refresh: "M23 4v6h-6M1 20v-6h6M3.5 9a9 9 0 0 1 14.9-3.4L23 10M1 14l4.6 4.4A9 9 0 0 0 20.5 15",
    inbox: "M22 12h-6l-2 3h-4l-2-3H2M5.5 5.1L2 12v6a2 2 0 0 0 2 2h16a2 2 0 0 0 2-2v-6l-3.5-6.9A2 2 0 0 0 16.8 4H7.2a2 2 0 0 0-1.7 1.1z",
    alert: "M10.3 3.9L1.8 18a2 2 0 0 0 1.7 3h17a2 2 0 0 0 1.7-3L13.7 3.9a2 2 0 0 0-3.4 0zM12 9v4M12 17h.01",
    plug: "M12 22v-5M9 8V2M15 8V2M18 8v5a6 6 0 0 1-12 0V8z",
    external: "M18 13v6a2 2 0 0 1-2 2H5a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h6M15 3h6v6M10 14L21 3",
  };
  const icon = (name) => s("svg", { viewBox: "0 0 24 24", "aria-hidden": "true" }, s("path", { d: ICONS[name] }));

  // ------------------------------------------------------------------ formatting
  const numberFormat = new Intl.NumberFormat("zh-CN");
  const fmtNum = (n) => (n === null || n === undefined ? "—" : numberFormat.format(n));
  function fmtBytes(bytes) {
    if (bytes === null || bytes === undefined) return "—";
    const units = ["B", "KB", "MB", "GB", "TB"];
    let value = Number(bytes);
    let unit = 0;
    while (value >= 1024 && unit < units.length - 1) { value /= 1024; unit += 1; }
    return `${value >= 100 || unit === 0 ? Math.round(value) : value.toFixed(1)} ${units[unit]}`;
  }
  const fmtChars = (n) => (n >= 10000 ? `${(n / 10000).toFixed(n >= 1e6 ? 0 : 1)} 万字` : `${fmtNum(n)} 字`);
  const pad = (n) => String(n).padStart(2, "0");
  function fmtTime(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`;
  }
  function fmtDate(iso) {
    if (!iso) return "—";
    const d = new Date(iso);
    return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}`;
  }
  function fmtRel(iso) {
    if (!iso) return "从未";
    const seconds = (Date.now() - new Date(iso).getTime()) / 1000;
    if (seconds < 60) return "刚刚";
    if (seconds < 3600) return `${Math.floor(seconds / 60)} 分钟前`;
    if (seconds < 86400) return `${Math.floor(seconds / 3600)} 小时前`;
    if (seconds < 86400 * 30) return `${Math.floor(seconds / 86400)} 天前`;
    return fmtDate(iso);
  }
  const timeCell = (iso) => h("span", { class: "nowrap", title: fmtTime(iso) }, fmtRel(iso));
  const initials = (name) => (name || "?").trim().slice(0, 1).toUpperCase();

  const ROLE_NAMES = { none: "普通用户", viewer: "只读管理员", operator: "运营", superadmin: "超级管理员" };
  const ROLE_SOURCES = { allowlist: "白名单", mkauth: "MKauth 授予", console: "平台分配" };
  const ACTIONS = {
    "user.disable": ["停用用户", "red"], "user.enable": ["启用用户", "green"], "user.role": ["分配角色", "amber"],
    "user.quota": ["调整配额", "blue"], "user.revoke_sessions": ["吊销会话", "red"],
    "book.publish": ["发布书籍", "green"], "book.unpublish": ["下架书籍", "red"], "book.restore": ["恢复上架", "green"],
    "release.withdraw": ["撤回版本", "red"], "release.restore": ["恢复版本", "green"],
    "release.min_supported": ["设置最低版本", "amber"],
  };
  const RESOURCES = { user: "用户", book: "书籍", release: "版本" };
  const actionPill = (action) => {
    const [label, tone] = ACTIONS[action] || [action, ""];
    return h("span", { class: `pill ${tone}` }, label);
  };
  const rolePill = (role) => h("span", { class: `role ${role}` }, ROLE_NAMES[role] || role);
  const userStatus = (status) => (status === "active"
    ? h("span", { class: "pill green" }, "正常") : h("span", { class: "pill red" }, "已停用"));

  // ------------------------------------------------------------------ API
  class ApiError extends Error {
    constructor(status, code, message, details) {
      super(message);
      this.status = status;
      this.code = code;
      this.details = details;
    }
  }

  function query(params) {
    const search = new URLSearchParams();
    for (const [key, value] of Object.entries(params || {})) {
      if (value !== null && value !== undefined && value !== "") search.set(key, value);
    }
    const text = search.toString();
    return text ? `?${text}` : "";
  }

  async function api(path, { method = "GET", body, form, params } = {}) {
    const headers = { Accept: "application/json" };
    let payload;
    if (method !== "GET") headers["X-MKread-Admin"] = "1";
    if (form) payload = form;
    else if (body !== undefined) { headers["Content-Type"] = "application/json"; payload = JSON.stringify(body); }
    let response;
    try {
      response = await fetch(`/api/v1/admin${path}${query(params)}`, { method, headers, body: payload, credentials: "same-origin" });
    } catch {
      throw new ApiError(0, "network_error", "无法连接服务器，请检查网络后重试");
    }
    if (response.status === 204) return null;
    let data = null;
    try { data = await response.json(); } catch { /* non-JSON error page */ }
    if (!response.ok) {
      const error = new ApiError(response.status, data?.code || `http_${response.status}`,
        data?.message || data?.detail || `请求失败（HTTP ${response.status}）`, data?.details);
      if (response.status === 401 && state.session) showLogin("expired");
      throw error;
    }
    return data;
  }
  const csvHref = (path, params) => `/api/v1/admin${path}${query({ ...params, page: null, page_size: null, format: "csv" })}`;

  // ------------------------------------------------------------------ app state
  const state = { session: null };
  const can = (permission) => Boolean(state.session?.permissions.includes(permission));
  const root = () => document.getElementById("app");
  const go = (hash) => { if (location.hash !== hash) location.hash = hash; };

  // ------------------------------------------------------------------ toast
  function toast(title, detail, kind = "ok") {
    let box = document.querySelector(".toasts");
    if (!box) { box = h("div", { class: "toasts", role: "status", "aria-live": "polite" }); document.body.append(box); }
    const item = h("div", { class: `toast ${kind === "error" ? "error" : ""}` }, h("strong", {}, title), detail ? h("small", {}, detail) : null);
    box.append(item);
    setTimeout(() => item.remove(), kind === "error" ? 7000 : 4000);
  }

  // ------------------------------------------------------------------ layers (dialog > palette > drawer)
  const layers = [];
  function pushLayer(close) { layers.push(close); return () => { const i = layers.indexOf(close); if (i >= 0) layers.splice(i, 1); }; }
  document.addEventListener("keydown", (event) => {
    if (event.key === "Escape" && layers.length) { event.preventDefault(); layers[layers.length - 1](); return; }
    const typing = /^(INPUT|TEXTAREA|SELECT)$/.test(document.activeElement?.tagName || "");
    if (state.session && ((event.key === "k" && (event.metaKey || event.ctrlKey)) || (event.key === "/" && !typing))) {
      event.preventDefault();
      openPalette();
    }
  });

  // ------------------------------------------------------------------ dialog
  /**
   * Confirmation dialog. Every write asks for a reason (stored in the audit log).
   * fields: [{name, label, type: "number"|"select"|"file"|"text", options, value, hint, min, max, accept}]
   */
  function openDialog({ title, description, impact, danger = false, confirmText = "确认", typeToConfirm,
    fields = [], reason = true, onSubmit }) {
    const previousFocus = document.activeElement;
    const errors = h("div", { class: "field-error", role: "alert" });
    const inputs = {};
    const fieldNodes = fields.map((field) => {
      let input;
      if (field.type === "select") {
        input = h("select", { class: "select", id: `f-${field.name}` },
          field.options.map(([value, label]) => h("option", { value, selected: String(value) === String(field.value) }, label)));
      } else if (field.type === "file") {
        input = h("input", { class: "input", type: "file", id: `f-${field.name}`, accept: field.accept });
      } else {
        input = h("input", { class: "input", id: `f-${field.name}`, type: field.type || "text", value: field.value ?? "",
          min: field.min, max: field.max, step: field.step, inputmode: field.type === "number" ? "numeric" : null });
      }
      inputs[field.name] = input;
      return h("label", { class: "field", for: `f-${field.name}` }, h("span", {}, field.label), input,
        field.hint ? h("small", {}, field.hint) : null);
    });
    let confirmInput = null;
    if (typeToConfirm) {
      confirmInput = h("input", { class: "input", id: "f-confirm", autocomplete: "off" });
      fieldNodes.push(h("label", { class: "field", for: "f-confirm" },
        h("span", {}, "输入 ", h("strong", {}, typeToConfirm), " 以确认"), confirmInput));
    }
    let reasonInput = null;
    if (reason) {
      reasonInput = h("textarea", { class: "textarea", id: "f-reason", maxlength: "200", placeholder: "例如：用户投诉违规内容，工单 #123" });
      fieldNodes.push(h("label", { class: "field", for: "f-reason" }, h("span", {}, "操作原因（必填，写入审计日志）"), reasonInput));
    }
    const submit = h("button", { class: `btn ${danger ? "danger" : "primary"}`, type: "submit" }, confirmText);
    const cancel = h("button", { class: "btn", type: "button" }, "取消");
    const form = h("form", { class: "dialog", role: "dialog", "aria-modal": "true", "aria-labelledby": "dialog-title" },
      h("div", { class: "dialog-head" }, h("h2", { id: "dialog-title" }, title), description ? h("p", {}, description) : null),
      h("div", { class: "dialog-body" }, impact ? h("div", { class: `impact ${danger ? "danger" : ""}` }, impact) : null, fieldNodes, errors),
      h("div", { class: "dialog-foot" }, cancel, submit));
    const overlay = h("div", { class: "overlay" });
    const wrap = h("div", { class: "dialog-wrap" }, form);
    let busy = false;
    const close = () => { if (busy) return; overlay.remove(); wrap.remove(); removeLayer(); previousFocus?.focus?.(); };
    const removeLayer = pushLayer(close);
    cancel.addEventListener("click", close);
    wrap.addEventListener("mousedown", (event) => { if (event.target === wrap) close(); });
    form.addEventListener("submit", async (event) => {
      event.preventDefault();
      errors.textContent = "";
      const values = {};
      for (const field of fields) {
        const input = inputs[field.name];
        if (field.type === "file") values[field.name] = input.files[0] || null;
        else if (field.type === "number") values[field.name] = input.value === "" ? null : Number(input.value);
        else values[field.name] = input.value;
        if (field.required !== false && (values[field.name] === null || values[field.name] === "")) {
          errors.textContent = `请填写「${field.label}」`;
          input.focus();
          return;
        }
      }
      if (confirmInput && confirmInput.value.trim() !== String(typeToConfirm)) {
        errors.textContent = `请输入「${typeToConfirm}」确认`;
        confirmInput.focus();
        return;
      }
      if (reasonInput) {
        values.reason = reasonInput.value.trim();
        if (values.reason.length < 2) {
          errors.textContent = "请填写操作原因（至少 2 个字）";
          reasonInput.focus();
          return;
        }
      }
      busy = true;
      submit.classList.add("loading");
      submit.disabled = cancel.disabled = true;
      try {
        await onSubmit(values);
        busy = false;
        close();
      } catch (error) {
        busy = false;
        submit.classList.remove("loading");
        submit.disabled = cancel.disabled = false;
        errors.textContent = error.message || "操作失败";
      }
    });
    document.body.append(overlay, wrap);
    (fieldNodes.length ? form.querySelector("input, select, textarea") : submit).focus();
  }

  // ------------------------------------------------------------------ drawer
  let drawer = null;
  function openDrawer(title, onClose) {
    if (drawer) drawer.close(true);
    const body = h("div", { class: "drawer-body" });
    const actions = h("div", { class: "drawer-actions" });
    const heading = h("h2", { id: "drawer-title" }, title);
    const closeButton = h("button", { class: "icon-btn", type: "button", "aria-label": "关闭" }, icon("close"));
    const panel = h("aside", { class: "drawer", role: "dialog", "aria-modal": "true", "aria-labelledby": "drawer-title" },
      h("div", { class: "drawer-head" }, heading, closeButton), body, actions);
    const overlay = h("div", { class: "overlay" });
    const self = {
      body, actions,
      setTitle: (text) => { heading.textContent = text; },
      close: (silent = false) => {
        if (drawer !== self) return;
        drawer = null;
        panel.remove();
        overlay.remove();
        removeLayer();
        if (!silent) onClose?.();
      },
    };
    const removeLayer = pushLayer(() => self.close());
    closeButton.addEventListener("click", () => self.close());
    overlay.addEventListener("click", () => self.close());
    document.body.append(overlay, panel);
    drawer = self;
    closeButton.focus();
    return self;
  }
  const closeDrawer = () => drawer?.close(true);

  // ------------------------------------------------------------------ states
  const skeletonBlock = (cls = "") => h("span", { class: `skeleton ${cls}` });
  function emptyState(title, hint, action) {
    return h("div", { class: "state" }, icon("inbox"), h("h3", {}, title), hint ? h("p", {}, hint) : null, action || null);
  }
  function errorState(error, retry) {
    return h("div", { class: "state error", role: "alert" }, icon("alert"), h("h3", {}, "加载失败"),
      h("p", {}, error.message), h("span", { class: "code" }, `${error.code || "error"}${error.status ? ` · HTTP ${error.status}` : ""}`),
      retry ? h("button", { class: "btn", type: "button", onClick: retry }, icon("refresh"), "重试") : null);
  }
  const section = (title, ...children) => h("div", { class: "section" }, h("h3", {}, title), children);
  const facts = (rows) => h("div", { class: "facts" }, rows.filter(Boolean).map(([label, value]) =>
    h("div", {}, h("span", {}, label), h("strong", {}, value ?? "—"))));
  function miniTable(columns, rows, empty = "暂无记录") {
    if (!rows.length) return h("p", { class: "faint" }, empty);
    return h("div", { class: "mini-table table-scroll" }, h("table", {},
      h("thead", {}, h("tr", {}, columns.map(([label, , cls]) => h("th", { class: cls || null }, label)))),
      h("tbody", {}, rows.map((row) => h("tr", {}, columns.map(([, render, cls]) => h("td", { class: cls || null }, render(row))))))));
  }
  function auditTimeline(entries, empty = "暂无操作记录") {
    if (!entries.length) return h("p", { class: "faint" }, empty);
    return h("ul", { class: "timeline" }, entries.map((entry) => {
      const tone = (ACTIONS[entry.action] || [])[1];
      return h("li", {}, h("i", { class: tone === "red" ? "red" : tone === "green" ? "green" : "" }),
        h("div", {}, h("strong", {}, (ACTIONS[entry.action] || [entry.action])[0]),
          h("small", {}, `${entry.actor_name || entry.actor_subject} · ${fmtTime(entry.at)}`),
          entry.reason ? h("div", { class: "reason" }, entry.reason) : null));
    }));
  }
  function meter(fraction) {
    const bar = h("i", { class: fraction > 0.9 ? "danger" : fraction > 0.75 ? "warn" : "" });
    bar.style.width = `${Math.max(0, Math.min(1, fraction)) * 100}%`;
    return h("div", { class: "meter", role: "meter", "aria-valuenow": Math.round(fraction * 100), "aria-valuemin": "0", "aria-valuemax": "100" }, bar);
  }

  // ------------------------------------------------------------------ data table
  /**
   * Server-side list with search, filters, sortable columns, paging, selection and CSV export.
   * Keeps its filters while the page stays mounted.
   */
  function dataTable(opts) {
    const st = { page: 1, page_size: opts.pageSize || 20, sort: opts.defaultSort || null, filters: { ...(opts.initialFilters || {}) },
      selected: new Map(), items: [], total: 0, loading: false, seq: 0 };
    const columns = opts.columns;
    const selectable = Boolean(opts.batchActions?.some((action) => !action.permission || can(action.permission)));
    const span = columns.length + (selectable ? 1 : 0);

    const tbody = h("tbody");
    const headRow = h("tr");
    const pager = h("div", { class: "pager" });
    const batchBar = h("div", { class: "batch-bar", hidden: true });
    const csvLink = opts.csv ? h("a", { class: "btn", href: "#", download: "" }, icon("download"), "导出 CSV") : null;
    const selectAll = selectable ? h("input", { type: "checkbox", "aria-label": "全选本页" }) : null;

    const params = () => ({ ...st.filters, page: st.page, page_size: st.page_size, sort: st.sort });

    function toolbar() {
      const items = [];
      for (const filter of opts.filters || []) {
        if (filter.type === "search") {
          let timer = null;
          const input = h("input", { class: "input", type: "search", placeholder: filter.placeholder, value: st.filters[filter.name] || "",
            "aria-label": filter.placeholder });
          input.addEventListener("input", () => {
            clearTimeout(timer);
            timer = setTimeout(() => { st.filters[filter.name] = input.value.trim(); st.page = 1; load(); }, 300);
          });
          items.push(h("div", { class: "search" }, icon("search"), input));
        } else if (filter.type === "select") {
          const select = h("select", { class: "select", "aria-label": filter.label },
            filter.options.map(([value, label]) => h("option", { value, selected: (st.filters[filter.name] || "") === value }, label)));
          select.addEventListener("change", () => { st.filters[filter.name] = select.value; st.page = 1; load(); });
          items.push(select);
        } else if (filter.type === "date") {
          const input = h("input", { class: "input", type: "date", "aria-label": filter.label, title: filter.label, value: st.filters[filter.name] || "" });
          input.addEventListener("change", () => { st.filters[filter.name] = input.value; st.page = 1; load(); });
          items.push(input);
        }
      }
      items.push(h("div", { class: "spacer" }));
      items.push(h("button", { class: "icon-btn", type: "button", title: "刷新", "aria-label": "刷新", onClick: () => load() }, icon("refresh")));
      if (csvLink) items.push(csvLink);
      for (const extra of opts.toolbarExtra || []) items.push(extra);
      return h("div", { class: "toolbar" }, items);
    }

    function renderHead() {
      fill(headRow);
      if (selectable) headRow.append(h("th", { class: "check" }, selectAll));
      const current = (st.sort || "").replace(/^-/, "");
      const desc = (st.sort || "").startsWith("-");
      for (const column of columns) {
        const sorted = column.sort && column.sort === current;
        const th = h("th", { class: [column.sort ? "sortable" : "", column.cls || ""].join(" ").trim() || null,
          "aria-sort": sorted ? (desc ? "descending" : "ascending") : null, scope: "col" },
          column.label, column.sort ? h("span", { class: "arrow", "aria-hidden": "true" }, sorted ? (desc ? "↓" : "↑") : "") : null);
        if (column.sort) {
          th.tabIndex = 0;
          const toggle = () => {
            st.sort = sorted ? (desc ? column.sort : `-${column.sort}`) : (column.ascFirst ? column.sort : `-${column.sort}`);
            st.page = 1;
            load();
          };
          th.addEventListener("click", toggle);
          th.addEventListener("keydown", (event) => { if (event.key === "Enter" || event.key === " ") { event.preventDefault(); toggle(); } });
        }
        headRow.append(th);
      }
    }

    function renderBatch() {
      const count = st.selected.size;
      batchBar.hidden = count === 0;
      if (!count) return;
      fill(batchBar, h("strong", {}, `已选 ${count} 项`), h("div", { class: "spacer" }),
        opts.batchActions.filter((action) => !action.permission || can(action.permission)).map((action) =>
          h("button", { class: `btn sm ${action.danger ? "danger-ghost" : ""}`, type: "button",
            onClick: () => action.run([...st.selected.values()], () => { st.selected.clear(); load(); }) }, action.label)),
        h("button", { class: "btn sm", type: "button", onClick: () => { st.selected.clear(); renderRows(); renderBatch(); } }, "取消选择"));
    }

    function renderRows() {
      fill(tbody);
      if (selectAll) {
        const onPage = st.items.filter((row) => st.selected.has(opts.rowKey(row))).length;
        selectAll.checked = onPage > 0 && onPage === st.items.length;
        selectAll.indeterminate = onPage > 0 && onPage < st.items.length;
      }
      for (const row of st.items) {
        const key = opts.rowKey(row);
        const tr = h("tr", { class: [opts.onRow ? "clickable" : "", st.selected.has(key) ? "selected" : ""].join(" ").trim() || null });
        if (selectable) {
          const box = h("input", { type: "checkbox", checked: st.selected.has(key), "aria-label": "选择" });
          box.addEventListener("click", (event) => event.stopPropagation());
          box.addEventListener("change", () => {
            if (box.checked) st.selected.set(key, row); else st.selected.delete(key);
            tr.classList.toggle("selected", box.checked);
            renderBatch();
            const onPage = st.items.filter((item) => st.selected.has(opts.rowKey(item))).length;
            selectAll.checked = onPage === st.items.length;
            selectAll.indeterminate = onPage > 0 && onPage < st.items.length;
          });
          tr.append(h("td", { class: "check" }, box));
        }
        for (const column of columns) tr.append(h("td", { class: column.cls || null }, column.render(row)));
        if (opts.onRow) {
          tr.tabIndex = 0;
          tr.addEventListener("click", () => opts.onRow(row));
          tr.addEventListener("keydown", (event) => { if (event.key === "Enter") opts.onRow(row); });
        }
        tbody.append(tr);
      }
    }

    function renderPager() {
      const pages = Math.max(1, Math.ceil(st.total / st.page_size));
      const from = st.total ? (st.page - 1) * st.page_size + 1 : 0;
      const to = Math.min(st.total, st.page * st.page_size);
      const size = h("select", { class: "select", "aria-label": "每页条数" },
        [20, 50, 100].map((n) => h("option", { value: n, selected: n === st.page_size }, `${n} 条/页`)));
      size.addEventListener("change", () => { st.page_size = Number(size.value); st.page = 1; load(); });
      fill(pager, 
        h("span", {}, st.total ? `第 ${fmtNum(from)}–${fmtNum(to)} 条，共 ${fmtNum(st.total)} 条` : "共 0 条"),
        h("div", { class: "pages" }, size,
          h("button", { class: "btn sm", type: "button", disabled: st.page <= 1, onClick: () => { st.page -= 1; load(); } }, "上一页"),
          h("span", {}, `${st.page} / ${pages}`),
          h("button", { class: "btn sm", type: "button", disabled: st.page >= pages, onClick: () => { st.page += 1; load(); } }, "下一页")));
    }

    async function load() {
      const seq = ++st.seq;
      st.loading = true;
      renderHead();
      if (csvLink) csvLink.href = opts.csv(params());
      fill(tbody, ...Array.from({ length: Math.min(st.page_size, 6) }, () =>
        h("tr", { class: "skeleton-row" }, Array.from({ length: span }, (_, i) =>
          h("td", {}, skeletonBlock(i === (selectable ? 1 : 0) ? "w80" : i % 2 ? "w40" : "w60"))))));
      try {
        const data = await opts.load(params());
        if (seq !== st.seq) return;
        st.items = data.items;
        st.total = data.total;
        const pages = Math.max(1, Math.ceil(st.total / st.page_size));
        if (st.page > pages) { st.page = pages; load(); return; }
        if (!st.items.length) {
          const filtered = Object.values(st.filters).some(Boolean);
          fill(tbody, h("tr", {}, h("td", { colspan: span },
            filtered ? emptyState("没有符合条件的记录", "试试调整搜索词或筛选条件。")
              : emptyState(opts.empty?.title || "暂无数据", opts.empty?.hint, opts.empty?.action?.()))));
        } else {
          renderRows();
        }
      } catch (error) {
        if (seq !== st.seq) return;
        st.items = [];
        st.total = 0;
        fill(tbody, h("tr", {}, h("td", { colspan: span }, errorState(error, load))));
      } finally {
        if (seq === st.seq) { st.loading = false; renderPager(); renderBatch(); }
      }
    }

    if (selectAll) {
      selectAll.addEventListener("change", () => {
        for (const row of st.items) {
          if (selectAll.checked) st.selected.set(opts.rowKey(row), row); else st.selected.delete(opts.rowKey(row));
        }
        renderRows();
        renderBatch();
      });
    }

    const el = h("div", {}, toolbar(), batchBar,
      h("div", { class: "card table-card" }, h("div", { class: "table-scroll" }, h("table", {}, h("thead", {}, headRow), tbody)), pager));
    load();
    return { el, reload: load, state: st };
  }

  // ------------------------------------------------------------------ page scaffolding
  function pageHead(eyebrow, title, description, ...actions) {
    return h("div", { class: "page-head" },
      h("div", {}, h("p", { class: "eyebrow" }, eyebrow), h("h1", {}, title), description ? h("p", {}, description) : null),
      actions.length ? h("div", { class: "head-actions" }, actions) : null);
  }

  // ------------------------------------------------------------------ overview
  function overviewPage() {
    const body = h("div", {}, h("div", { class: "kpis" }, Array.from({ length: 5 }, () => skeletonBlock("block"))));
    const el = h("div", {}, pageHead("Platform", "概览", "MKread 的用户、书库、存储和服务状态。",
      h("button", { class: "btn", type: "button", onClick: () => load() }, icon("refresh"), "刷新")), body);

    function kpi(label, value, foot, extra) {
      return h("div", { class: "card kpi" }, h("div", { class: "kpi-label" }, label), h("div", { class: "kpi-value" }, value),
        extra || null, h("div", { class: "kpi-foot" }, foot));
    }

    function chart(daily) {
      const width = 700;
      const height = 200;
      const top = 10;
      const bottom = 24;
      const left = 28;
      const plotH = height - top - bottom;
      const max = Math.max(1, ...daily.map((d) => Math.max(d.active, d.signups)));
      const step = (width - left) / daily.length;
      const barW = Math.max(4, step / 3);
      const scale = (v) => (v / max) * plotH;
      const ticks = [0, 0.5, 1].map((f) => Math.round(max * f));
      return h("div", { class: "chart" }, s("svg", { viewBox: `0 0 ${width} ${height}`, role: "img", "aria-label": "近 14 天活跃与新增用户" },
        [...new Set(ticks)].map((t) => {
          const y = top + plotH - scale(t);
          return [s("line", { class: "grid-line", x1: left, x2: width, y1: y, y2: y }), s("text", { x: 0, y: y + 4 }, t)];
        }),
        daily.map((d, i) => {
          const x = left + i * step + (step - barW * 2 - 2) / 2;
          return [
            s("rect", { class: "bar-a", x, y: top + plotH - scale(d.active), width: barW, height: Math.max(scale(d.active), d.active ? 2 : 0), rx: 2 },
              s("title", {}, `${d.day} 活跃 ${d.active}`)),
            s("rect", { class: "bar-b", x: x + barW + 2, y: top + plotH - scale(d.signups), width: barW, height: Math.max(scale(d.signups), d.signups ? 2 : 0), rx: 2 },
              s("title", {}, `${d.day} 新增 ${d.signups}`)),
            i % 2 === 0 ? s("text", { x: x - 2, y: height - 6 }, d.day.slice(5)) : null,
          ];
        })));
    }

    async function load() {
      fill(body, h("div", { class: "kpis" }, Array.from({ length: 5 }, () => skeletonBlock("block"))));
      let data;
      try { data = await api("/overview"); } catch (error) { fill(body, h("div", { class: "card" }, errorState(error, load))); return; }
      const { users, books, storage, latest_release: latest, health } = data;
      const used = storage.books + storage.voices + storage.releases;
      const diskUsed = storage.disk_total - storage.disk_free;
      fill(body, 
        h("div", { class: "kpis" },
          kpi("用户总数", fmtNum(users.total), `近 7 天新增 ${fmtNum(users.new_7d)} · 管理员 ${fmtNum(users.admins)}`),
          kpi("7 日活跃", fmtNum(users.active_7d), users.total ? `占全部用户 ${Math.round((users.active_7d / users.total) * 100)}%` : "还没有用户"),
          kpi("上架书籍", fmtNum(books.active), `已下架 ${fmtNum(books.deleted)} · 共 ${fmtChars(books.chars)}`),
          kpi("存储用量", fmtBytes(used), `磁盘剩余 ${fmtBytes(storage.disk_free)}`, meter(storage.disk_total ? diskUsed / storage.disk_total : 0)),
          kpi("最新版本", latest ? latest.version_name : "—", latest ? `versionCode ${latest.version_code} · ${fmtDate(latest.published_at)}` : "尚未发布")),
        h("div", { class: "grid-2" },
          h("div", { class: "card panel" },
            h("div", { class: "panel-head" }, h("h2", {}, "近 14 天用户"),
              h("div", { class: "legend" }, h("span", {}, h("i", { class: "a" }), "活跃"), h("span", {}, h("i", { class: "b" }), "新增"))),
            chart(data.daily)),
          h("div", { class: "card panel" },
            h("div", { class: "panel-head" }, h("h2", {}, "服务状态")),
            h("div", { class: "health-list" },
              h("div", { class: "health-row" }, h("span", {}, "数据库"), h("span", { class: "pill green" }, `正常 · ${health.database_ms} ms`)),
              h("div", { class: "health-row" }, h("span", {}, "MKauth 登录"),
                health.login === "ok" ? h("span", { class: "pill green" }, "已配置") : h("span", { class: "pill amber" }, "未配置")),
              h("div", { class: "health-row" }, h("span", {}, "书库访问"),
                h("span", { class: "pill plain" }, health.read_access === "login" ? "需要登录" : "公开")),
              h("div", { class: "health-row" }, h("span", {}, "存储构成"),
                h("span", { class: "muted" }, `书 ${fmtBytes(storage.books)} · 音色 ${fmtBytes(storage.voices)} · 安装包 ${fmtBytes(storage.releases)}`)),
              latest ? h("div", { class: "health-row" }, h("span", {}, "强制更新"),
                latest.min_supported ? h("span", { class: "pill amber" }, `低于 ${latest.min_supported}`) : h("span", { class: "pill plain" }, "未设置")) : null))),
        h("div", { class: "card panel", id: "recent" },
          h("div", { class: "panel-head" }, h("h2", {}, "最近操作"), h("a", { href: "#/audit" }, "查看全部审计")),
          auditTimeline(data.recent_audit, "还没有管理操作。所有修改都会记录在这里。")));
    }
    load();
    return { el };
  }

  // ------------------------------------------------------------------ users
  const userCell = (row) => h("div", { class: "cell-main" }, h("span", { class: "avatar" }, initials(row.display_name)),
    h("div", {}, h("strong", {}, row.display_name || "（未命名）"), h("small", {}, row.email || row.subject)));

  function usersPage() {
    const table = dataTable({
      rowKey: (row) => row.subject,
      defaultSort: "-last_seen_at",
      filters: [
        { type: "search", name: "q", placeholder: "搜索名称、邮箱或 subject" },
        { type: "select", name: "status", label: "状态", options: [["", "全部状态"], ["active", "正常"], ["disabled", "已停用"]] },
        { type: "select", name: "role", label: "角色", options: [["", "全部角色"], ["admin", "任意管理员"], ["superadmin", "超级管理员"], ["operator", "运营"], ["viewer", "只读管理员"], ["none", "普通用户"]] },
      ],
      columns: [
        { label: "用户", sort: "display_name", ascFirst: true, render: userCell },
        { label: "角色", render: (row) => h("div", {}, rolePill(row.role), row.role_source ? h("small", { class: "faint" }, ` ${ROLE_SOURCES[row.role_source]}`) : null) },
        { label: "状态", render: (row) => userStatus(row.status) },
        { label: "有效设备", sort: "active_sessions", cls: "num", render: (row) => fmtNum(row.active_sessions) },
        { label: "最近活跃", sort: "last_seen_at", render: (row) => timeCell(row.last_seen_at) },
        { label: "注册时间", sort: "created_at", cls: "hide-sm", render: (row) => h("span", { class: "nowrap" }, fmtDate(row.created_at)) },
        { label: "配额", sort: "quota_bytes", cls: "num hide-sm", render: (row) => fmtBytes(row.quota_bytes) },
      ],
      load: (params) => api("/users", { params }),
      csv: (params) => csvHref("/users", params),
      onRow: (row) => go(`#/users/${encodeURIComponent(row.subject)}`),
      batchActions: [
        { label: "吊销会话", permission: "users.write", danger: true, run: (rows, done) => batchUsers(rows, "revoke", done) },
        { label: "停用", permission: "users.write", danger: true, run: (rows, done) => batchUsers(rows, "disable", done) },
        { label: "启用", permission: "users.write", run: (rows, done) => batchUsers(rows, "enable", done) },
      ],
      empty: { title: "还没有用户", hint: "用户第一次在 App 或管理平台通过 MKauth 登录后会自动出现在这里。" },
    });

    function batchUsers(rows, action, done) {
      const labels = { revoke: "吊销所选用户的全部设备会话", disable: "停用所选用户", enable: "启用所选用户" };
      openDialog({
        title: labels[action],
        description: `共 ${rows.length} 个账号：${rows.slice(0, 5).map((r) => r.display_name || r.subject).join("、")}${rows.length > 5 ? " 等" : ""}`,
        impact: action === "enable" ? "这些账号可以重新使用 App 和管理平台。" : "这些账号的 App 会立即要求重新登录（停用后无法再登录）。",
        danger: action !== "enable",
        confirmText: action === "enable" ? "启用" : action === "disable" ? "停用" : "吊销",
        onSubmit: async ({ reason }) => {
          let ok = 0;
          const failed = [];
          for (const row of rows) {
            try {
              if (action === "revoke") await api(`/users/${encodeURIComponent(row.subject)}/revoke-sessions`, { method: "POST", body: { reason } });
              else await api(`/users/${encodeURIComponent(row.subject)}`, { method: "PATCH", body: { status: action === "enable" ? "active" : "disabled", reason } });
              ok += 1;
            } catch (error) {
              failed.push(`${row.display_name || row.subject}：${error.message}`);
            }
          }
          if (failed.length) toast(`完成 ${ok} 个，失败 ${failed.length} 个`, failed.slice(0, 3).join("；"), "error");
          else toast(`已处理 ${ok} 个账号`);
          done();
        },
      });
    }

    const el = h("div", {}, pageHead("Platform", "用户与账号", "账号来自 MKauth，每个 subject 只有一条记录。停用后该账号的 App 和管理会话立即失效。"), table.el);
    return { el, openDetail: (subject) => userDrawer(subject, () => table.reload()) };
  }

  function userDrawer(subject, onChanged) {
    const d = openDrawer("用户详情", () => go("#/users"));
    async function load() {
      fill(d.body, skeletonBlock("block"));
      fill(d.actions);
      let user;
      try { user = await api(`/users/${encodeURIComponent(subject)}`); } catch (error) { fill(d.body, errorState(error, load)); return; }
      d.setTitle(user.display_name || user.subject);
      const self = user.subject === state.session.subject;
      fill(d.body, 
        h("div", { class: "profile" }, h("span", { class: "avatar lg" }, initials(user.display_name)),
          h("div", {}, h("h3", {}, user.display_name || "（未命名）"), h("p", {}, user.email || "未提供邮箱"),
            h("div", { class: "head-actions" }, rolePill(user.role), userStatus(user.status)))),
        section("基本信息", facts([
          ["Subject", h("span", { class: "mono" }, user.subject)],
          ["角色来源", user.role_source ? ROLE_SOURCES[user.role_source] : "—"],
          ["平台分配角色", ROLE_NAMES[user.assigned_role]],
          ["MKauth 授予角色", ROLE_NAMES[user.claim_role]],
          ["注册时间", fmtTime(user.created_at)],
          ["最近活跃", `${fmtRel(user.last_seen_at)}（${fmtTime(user.last_seen_at)}）`],
          ["个人云端配额", `${fmtBytes(user.storage_used_bytes)} / ${fmtBytes(user.quota_bytes)}`],
          ["发布过的书", fmtNum(user.published_books)],
        ])),
        section(`设备会话（有效 ${user.active_sessions}）`, miniTable([
          ["设备", (r) => h("span", { class: "ellipsis", title: r.user_agent || "" }, r.user_agent || "未知设备")],
          ["登录", (r) => fmtDate(r.created_at), "nowrap"],
          ["最近使用", (r) => timeCell(r.last_used_at), "nowrap"],
          ["状态", (r) => h("span", { class: `pill ${r.state === "active" ? "green" : r.state === "revoked" ? "red" : ""}` },
            { active: "有效", revoked: "已吊销", expired: "已过期" }[r.state])],
        ], user.sessions, "没有设备登录记录")),
        user.console_sessions.n ? h("p", { class: "faint" }, `另有 ${user.console_sessions.n} 个管理平台会话，最近使用 ${fmtRel(user.console_sessions.last_used_at)}。`) : null,
        section("操作记录", auditTimeline(user.audit, "还没有针对该用户的管理操作")));

      const patch = (body) => api(`/users/${encodeURIComponent(subject)}`, { method: "PATCH", body });
      const after = (message) => { toast(message); onChanged(); load(); };
      const actions = [];
      if (can("users.write") && !self) {
        if (user.status === "active") {
          actions.push(h("button", { class: "btn danger-ghost", type: "button", onClick: () => openDialog({
            title: "停用账号", description: `${user.display_name || user.subject}`,
            impact: "停用后该账号的 App 立即退出登录，也无法再次登录或进入管理平台；数据保留，可随时启用。",
            danger: true, confirmText: "停用", typeToConfirm: user.display_name || user.subject,
            onSubmit: async ({ reason }) => { await patch({ status: "disabled", reason }); after("账号已停用"); },
          }) }, "停用账号"));
        } else {
          actions.push(h("button", { class: "btn", type: "button", onClick: () => openDialog({
            title: "启用账号", description: `${user.display_name || user.subject}`, confirmText: "启用",
            onSubmit: async ({ reason }) => { await patch({ status: "active", reason }); after("账号已启用"); },
          }) }, "启用账号"));
        }
      }
      if (can("users.write")) {
        actions.push(h("button", { class: "btn", type: "button", disabled: !user.active_sessions && !user.console_sessions.n, onClick: () => openDialog({
          title: "吊销全部会话", description: `${user.display_name || user.subject} 的 ${user.active_sessions} 台设备`,
          impact: self ? "包括你当前的管理平台会话，完成后需要重新登录。" : "该账号需要在每台设备上重新登录。",
          danger: true, confirmText: "吊销",
          onSubmit: async ({ reason }) => {
            const result = await api(`/users/${encodeURIComponent(subject)}/revoke-sessions`, { method: "POST", body: { reason } });
            after(`已吊销 ${result.revoked} 个设备会话`);
          },
        }) }, "吊销会话"));
        actions.push(h("button", { class: "btn", type: "button", onClick: () => openDialog({
          title: "调整个人云端配额", description: `当前 ${fmtBytes(user.quota_bytes)}`,
          fields: [{ name: "gb", label: "配额（GB）", type: "number", value: (user.quota_bytes / 1024 ** 3).toFixed(1).replace(/\.0$/, ""), min: 0, max: 1024, step: "0.5" }],
          onSubmit: async ({ gb, reason }) => { await patch({ quota_bytes: Math.round(gb * 1024 ** 3), reason }); after("配额已更新"); },
        }) }, "调整配额"));
      }
      if (can("users.role") && !self) {
        actions.push(h("button", { class: "btn", type: "button", onClick: () => openDialog({
          title: "分配角色", description: "有效角色取「平台分配」和「MKauth 授予」中较高的一个。",
          impact: user.claim_role !== "none" ? `MKauth 已授予「${ROLE_NAMES[user.claim_role]}」，在这里降级不会低于它。` : null,
          fields: [{ name: "role", label: "平台分配角色", type: "select", value: user.assigned_role,
            options: [["none", "普通用户（无管理权限）"], ["viewer", "只读管理员：查看与导出"], ["operator", "运营：管理书籍与用户"], ["superadmin", "超级管理员：全部权限"]] }],
          confirmText: "保存",
          onSubmit: async ({ role, reason }) => { await patch({ role, reason }); after("角色已更新"); },
        }) }, "分配角色"));
      }
      if (!actions.length) actions.push(h("span", { class: "faint" }, self ? "不能修改自己的状态和角色。" : "当前角色只能查看。"));
      fill(d.actions, ...actions);
    }
    load();
  }

  // ------------------------------------------------------------------ books
  function coverNode(row, large = false) {
    const placeholder = () => h("span", { class: `cover ${large ? "lg" : ""}`, "aria-hidden": "true" }, initials(row.title));
    if (!row.has_cover) return placeholder();
    const img = h("img", { class: `cover ${large ? "lg" : ""}`, alt: "", loading: "lazy",
      src: `/api/v1/books/${encodeURIComponent(row.id)}/cover?r=${row.revision}` });
    img.addEventListener("error", () => img.replaceWith(placeholder()));
    return img;
  }
  const bookState = (row) => (row.state === "active" ? h("span", { class: "pill green" }, "上架") : h("span", { class: "pill red" }, "已下架"));

  function booksPage() {
    const upload = can("books.write") ? h("button", { class: "btn primary", type: "button", onClick: () => openDialog({
      title: "上传书籍", description: "上传 .mkbook。同一本书的新版本 revision 必须更大，App 会原地更新并保留阅读进度。",
      fields: [{ name: "file", label: "MKBook 文件", type: "file", accept: ".mkbook,application/vnd.mkread.book+zip" }],
      confirmText: "上传",
      onSubmit: async ({ file, reason }) => {
        const form = new FormData();
        form.append("file", file);
        form.append("reason", reason);
        const book = await api("/books", { method: "POST", form });
        toast(`《${book.title}》已发布`, `r${book.revision} · ${fmtNum(book.chapterCount)} 章`);
        table.reload();
      },
    }) }, icon("upload"), "上传书籍") : null;

    const table = dataTable({
      rowKey: (row) => row.id,
      defaultSort: "-published_at",
      filters: [
        { type: "search", name: "q", placeholder: "搜索书名、作者或 ID" },
        { type: "select", name: "state", label: "状态", options: [["", "全部状态"], ["active", "上架"], ["deleted", "已下架"]] },
      ],
      columns: [
        { label: "书籍", sort: "title", ascFirst: true, render: (row) => h("div", { class: "cell-main" }, coverNode(row),
          h("div", {}, h("strong", {}, row.title), h("small", {}, row.author || "佚名"))) },
        { label: "ID", cls: "hide-sm", render: (row) => h("span", { class: "mono" }, row.id) },
        { label: "版本", sort: "revision", cls: "num", render: (row) => h("span", { title: `共 ${row.revisions} 个版本` }, `r${row.revision}`) },
        { label: "章节", sort: "chapter_count", cls: "num", render: (row) => fmtNum(row.chapter_count) },
        { label: "字数", sort: "char_count", cls: "num", render: (row) => fmtChars(row.char_count) },
        { label: "大小", sort: "package_size", cls: "num hide-sm", render: (row) => fmtBytes(row.package_size) },
        { label: "状态", render: bookState },
        { label: "发布", sort: "published_at", render: (row) => timeCell(row.published_at) },
      ],
      load: (params) => api("/books", { params }),
      csv: (params) => csvHref("/books", params),
      onRow: (row) => go(`#/books/${encodeURIComponent(row.id)}`),
      toolbarExtra: upload ? [upload] : [],
      batchActions: [
        { label: "下架", permission: "books.write", danger: true, run: (rows, done) => batchBooks(rows, "unpublish", done) },
        { label: "恢复上架", permission: "books.write", run: (rows, done) => batchBooks(rows, "restore", done) },
      ],
      empty: { title: "书库还是空的", hint: "用 tools/mkbook 生成 .mkbook，然后点右上角「上传书籍」。" },
    });

    function batchBooks(rows, action, done) {
      openDialog({
        title: action === "unpublish" ? "批量下架" : "批量恢复上架",
        description: `${rows.length} 本：${rows.slice(0, 4).map((r) => `《${r.title}》`).join("")}${rows.length > 4 ? " 等" : ""}`,
        impact: action === "unpublish" ? "已下载的设备保留这些书，但不再收到更新；新设备不再看到。" : "这些书重新出现在所有设备的云书库里。",
        danger: action === "unpublish",
        confirmText: action === "unpublish" ? "下架" : "恢复",
        onSubmit: async ({ reason }) => {
          const result = await api("/books/batch", { method: "POST", body: { action, ids: rows.map((r) => r.id), reason } });
          const failed = result.results.filter((r) => !r.ok);
          if (failed.length) toast(`完成 ${result.succeeded} 本，跳过 ${failed.length} 本`, failed.map((r) => `${r.id}：${r.message}`).slice(0, 3).join("；"), "error");
          else toast(`已处理 ${result.succeeded} 本书`);
          done();
        },
      });
    }

    const el = h("div", {}, pageHead("MKread", "书籍", "全局书库：所有登录用户同步同一份目录。下架不会删除设备上已下载的书。"), table.el);
    return { el, openDetail: (id) => bookDrawer(id, () => table.reload()) };
  }

  function bookDrawer(id, onChanged) {
    const d = openDrawer("书籍详情", () => go("#/books"));
    async function load() {
      fill(d.body, skeletonBlock("block"));
      fill(d.actions);
      let book;
      try { book = await api(`/books/${encodeURIComponent(id)}`); } catch (error) { fill(d.body, errorState(error, load)); return; }
      d.setTitle(`《${book.title}》`);
      fill(d.body, 
        h("div", { class: "profile" }, coverNode(book, true),
          h("div", {}, h("h3", {}, book.title), h("p", {}, book.author || "佚名"),
            h("div", { class: "head-actions" }, bookState(book), h("span", { class: "pill plain" }, `r${book.revision}`)))),
        book.description ? section("简介", h("p", { class: "muted" }, book.description)) : null,
        section("基本信息", facts([
          ["ID", h("span", { class: "mono" }, book.id)],
          ["连载状态", book.status || "—"],
          ["章节", fmtNum(book.chapter_count)],
          ["字数", fmtChars(book.char_count)],
          ["包大小", fmtBytes(book.package_size)],
          ["标签", (book.tags || []).join("、") || "—"],
          ["发布时间", fmtTime(book.published_at)],
          ["发布人", book.published_by_name || book.published_by],
          ["SHA-256", h("span", { class: "mono" }, `${book.package_sha256.slice(0, 16)}…`)],
          ["目录游标", fmtNum(book.seq)],
        ])),
        section(`分卷（${book.volumes.length}）`, miniTable([
          ["卷名", (r) => r.name || "（未分卷）"], ["章节", (r) => fmtNum(r.chapters), "num"], ["字数", (r) => fmtChars(r.chars), "num"],
        ], book.volumes)),
        section("前 10 章", miniTable([
          ["章节 ID", (r) => h("span", { class: "mono" }, r.id)], ["标题", (r) => r.title], ["字数", (r) => fmtNum(r.chars), "num"],
        ], book.first_chapters)),
        section("版本历史", miniTable([
          ["版本", (r) => `r${r.revision}`], ["大小", (r) => fmtBytes(r.package_size), "num"],
          ["发布时间", (r) => fmtTime(r.published_at), "nowrap"], ["发布人", (r) => r.published_by_name || r.published_by],
        ], book.revision_history)),
        section("操作记录", auditTimeline(book.audit, "还没有针对这本书的管理操作")));

      const actions = [];
      if (book.state === "active") {
        actions.push(h("a", { class: "btn", href: `/api/v1/books/${encodeURIComponent(book.id)}/package?r=${book.revision}` }, icon("download"), "下载 .mkbook"));
      }
      if (can("books.write")) {
        const unpublishing = book.state === "active";
        actions.push(h("button", { class: `btn ${unpublishing ? "danger-ghost" : ""}`, type: "button", onClick: () => openDialog({
          title: unpublishing ? "下架书籍" : "恢复上架", description: `《${book.title}》 r${book.revision}`,
          impact: unpublishing ? "已下载的设备保留这本书，但不再收到更新；新设备在云书库里看不到它。" : "这本书重新出现在所有设备的云书库里。",
          danger: unpublishing, confirmText: unpublishing ? "下架" : "恢复",
          onSubmit: async ({ reason }) => {
            await api(`/books/${encodeURIComponent(book.id)}/${unpublishing ? "unpublish" : "restore"}`, { method: "POST", body: { reason } });
            toast(unpublishing ? "已下架" : "已恢复上架", `《${book.title}》`);
            onChanged();
            load();
          },
        }) }, unpublishing ? "下架" : "恢复上架"));
      }
      fill(d.actions, ...actions);
    }
    load();
  }

  // ------------------------------------------------------------------ voices
  function voicesPage() {
    const body = h("div", { class: "card table-card" }, h("div", { class: "state" }, skeletonBlock("w60")));
    async function load() {
      try {
        const data = await api("/voice-packs");
        if (!data.items.length) { fill(body, emptyState("还没有发布音色包", "在应用服务器上用 python -m app.voices publish 发布。")); return; }
        fill(body, h("div", { class: "table-scroll" }, h("table", {},
          h("thead", {}, h("tr", {}, ["音色包", "版本", "下载大小", "文件数", "解压后", "发布时间"].map((label, i) =>
            h("th", { class: i && i < 5 ? "num" : null }, label)))),
          h("tbody", {}, data.items.map((row) => h("tr", {},
            h("td", {}, h("span", { class: "mono" }, row.id)), h("td", { class: "num" }, `r${row.revision}`),
            h("td", { class: "num" }, fmtBytes(row.package_size)), h("td", { class: "num" }, fmtNum(row.file_count)),
            h("td", { class: "num" }, fmtBytes(row.unpacked_size)), h("td", {}, timeCell(row.published_at))))))));
      } catch (error) { fill(body, errorState(error, load)); }
    }
    load();
    return { el: h("div", {}, pageHead("MKread", "音色", "可下载的朗读音色包。包内容永不变化，Cloudflare 按文件名长期缓存。"),
      h("div", { class: "stack" }, body,
        h("div", { class: "notice" }, icon("plug"), h("div", {}, h("h3", {}, "朗读音色目录"),
          h("p", {}, "单个音色（Kokoro 的 100 种声音）的启用、改名和排序由 narration_voices 表管理，接口上线后在这里增加一个标签页。"))))) };
  }

  // ------------------------------------------------------------------ releases
  function releasesPage() {
    const body = h("div", { class: "card table-card" }, h("div", { class: "state" }, skeletonBlock("w60")));
    async function act(row, kind) {
      const configs = {
        withdraw: { title: "撤回版本", impact: "撤回后，还没更新的设备不会再收到这个版本；已安装的不受影响。", danger: true, confirmText: "撤回",
          typeToConfirm: row.version_name, run: (reason) => api(`/releases/${row.version_code}/withdraw`, { method: "POST", body: { reason } }) },
        restore: { title: "恢复版本", impact: "恢复后，这个版本重新参与更新检查。", confirmText: "恢复",
          run: (reason) => api(`/releases/${row.version_code}/restore`, { method: "POST", body: { reason } }) },
        min: { title: "设置最低支持版本", impact: "versionCode 低于此值的安装会被强制更新（更新弹窗无法关闭）。填 0 取消强制更新。", confirmText: "保存",
          fields: [{ name: "min_supported", label: "最低支持 versionCode", type: "number", value: row.min_supported, min: 0, max: row.version_code }],
          run: (reason, values) => api(`/releases/${row.version_code}`, { method: "PATCH", body: { min_supported: values.min_supported, reason } }) },
      };
      const config = configs[kind];
      openDialog({ ...config, description: `${row.version_name}（versionCode ${row.version_code}）`,
        onSubmit: async (values) => { await config.run(values.reason, values); toast(`${config.title}：已完成`, row.version_name); load(); } });
    }
    async function load() {
      try {
        const data = await api("/releases");
        if (!data.items.length) { fill(body, emptyState("还没有发布版本", "用 scripts/publish-release.sh 构建并发布。")); return; }
        const manage = can("releases.write");
        fill(body, h("div", { class: "table-scroll" }, h("table", {},
          h("thead", {}, h("tr", {}, ["版本", "状态", "最低支持", "大小", "SHA-256", "发布时间", manage ? "操作" : null].filter(Boolean)
            .map((label) => h("th", { class: label === "最低支持" || label === "大小" ? "num" : null }, label)))),
          h("tbody", {}, data.items.map((row) => h("tr", {},
            h("td", {}, h("div", { class: "cell-main" }, h("div", {}, h("strong", {}, row.version_name), h("small", {}, `versionCode ${row.version_code}`)))),
            h("td", {}, h("div", { class: "head-actions" },
              row.state === "active" ? h("span", { class: "pill green" }, "可更新") : h("span", { class: "pill" }, "已撤回"),
              row.is_latest ? h("span", { class: "pill blue plain" }, "最新") : null)),
            h("td", { class: "num" }, row.min_supported ? h("span", { class: "pill amber plain" }, `< ${row.min_supported} 强制`) : "—"),
            h("td", { class: "num" }, fmtBytes(row.apk_size)),
            h("td", {}, h("span", { class: "mono", title: row.apk_sha256 }, `${row.apk_sha256.slice(0, 12)}…`)),
            h("td", {}, timeCell(row.published_at)),
            manage ? h("td", {}, h("div", { class: "head-actions" },
              row.state === "active"
                ? h("button", { class: "btn sm danger-ghost", type: "button", onClick: () => act(row, "withdraw") }, "撤回")
                : h("button", { class: "btn sm", type: "button", onClick: () => act(row, "restore") }, "恢复"),
              h("button", { class: "btn sm", type: "button", onClick: () => act(row, "min") }, "最低版本"))) : null))))));
      } catch (error) { fill(body, errorState(error, load)); }
    }
    load();
    return { el: h("div", {}, pageHead("MKread", "应用版本", "App 启动和「检查更新」时读取最新的未撤回版本。撤回和强制更新只有超级管理员能操作。",
      h("a", { class: "btn", href: csvHref("/releases", {}) }, icon("download"), "导出 CSV")), body) };
  }

  // ------------------------------------------------------------------ audit
  function auditPage() {
    const table = dataTable({
      rowKey: (row) => row.id,
      defaultSort: "-at",
      filters: [
        { type: "search", name: "actor", placeholder: "操作人名称或 subject" },
        { type: "select", name: "action", label: "操作", options: [["", "全部操作"], ["user.", "用户相关"], ["book.", "书籍相关"], ["release.", "版本相关"],
          ...Object.entries(ACTIONS).map(([key, [label]]) => [key, `· ${label}`])] },
        { type: "select", name: "resource_type", label: "对象", options: [["", "全部对象"], ["user", "用户"], ["book", "书籍"], ["release", "版本"]] },
        { type: "date", name: "since", label: "开始日期" },
        { type: "date", name: "until", label: "结束日期（不含）" },
      ],
      columns: [
        { label: "时间", sort: "at", render: (row) => h("span", { class: "nowrap" }, fmtTime(row.at)) },
        { label: "操作人", sort: "actor_name", ascFirst: true, render: (row) => h("div", { class: "cell-main" },
          h("div", {}, h("strong", {}, row.actor_name || row.actor_subject), h("small", {}, ROLE_NAMES[row.actor_role] || row.actor_role))) },
        { label: "操作", sort: "action", ascFirst: true, render: (row) => actionPill(row.action) },
        { label: "对象", render: (row) => h("div", { class: "cell-main" }, h("div", {}, h("strong", { class: "mono" }, row.resource_id),
          h("small", {}, RESOURCES[row.resource_type] || row.resource_type))) },
        { label: "原因", render: (row) => h("span", { class: "ellipsis muted", title: row.reason || "" }, row.reason || "—") },
      ],
      load: (params) => api("/audit", { params }),
      csv: (params) => csvHref("/audit", params),
      onRow: (row) => auditDrawer(row),
      empty: { title: "还没有审计记录", hint: "所有通过管理平台和管理接口做的修改都会记录在这里，包括操作人、时间、原因和改前改后。" },
    });
    return { el: h("div", {}, pageHead("Platform", "审计日志", "谁在什么时候、因为什么做了什么修改。记录只增不删。"), table.el) };
  }

  function auditDrawer(entry) {
    const d = openDrawer("审计详情");
    const pretty = (value) => (value === null || value === undefined ? "（无）" : JSON.stringify(value, null, 2));
    const link = entry.resource_type === "user" ? `#/users/${encodeURIComponent(entry.resource_id)}`
      : entry.resource_type === "book" ? `#/books/${encodeURIComponent(entry.resource_id)}`
        : entry.resource_type === "release" ? "#/releases" : null;
    fill(d.body, 
      h("div", { class: "profile" }, h("div", {}, actionPill(entry.action), h("h3", {}, `#${entry.id}`), h("p", {}, fmtTime(entry.at)))),
      section("操作", facts([
        ["操作人", entry.actor_name || entry.actor_subject],
        ["操作人角色", ROLE_NAMES[entry.actor_role] || entry.actor_role],
        ["操作人 Subject", h("span", { class: "mono" }, entry.actor_subject)],
        ["对象", `${RESOURCES[entry.resource_type] || entry.resource_type} · ${entry.resource_id}`],
        ["来源 IP", entry.ip || "—"],
        ["客户端", entry.user_agent || "—"],
      ])),
      section("原因", h("p", {}, entry.reason || "—")),
      section("改动", h("div", { class: "diff" }, h("div", {}, h("h4", {}, "改前"), h("pre", {}, pretty(entry.before))),
        h("div", {}, h("h4", {}, "改后"), h("pre", {}, pretty(entry.after))))));
    if (link) fill(d.actions, h("a", { class: "btn", href: link, onClick: () => d.close(true) }, "打开对象"));
  }

  // ------------------------------------------------------------------ other apps and system
  function appPage(name, database, description) {
    return () => ({ el: h("div", {}, pageHead("Apps", name, description),
      h("div", { class: "stack" },
        h("div", { class: "notice" }, icon("plug"), h("div", {}, h("h3", {}, "尚未接入"),
          h("p", {}, `${name}的数据在数据库 ${database} 中，由该应用自己的服务管理。平台不直接连库，等它实现同样规范的管理接口后在这里挂载。`),
          h("ul", {},
            h("li", {}, "前缀 /api/v1/admin/<资源>，分页参数 page、page_size、sort，返回 {items, page, page_size, total}"),
            h("li", {}, "错误格式 {code, message, details}；写操作必须带 reason 并记审计"),
            h("li", {}, "鉴权：MKauth 登录 + 应用自己的角色（如 notes:admin）"),
            h("li", {}, "用户统一用 MKauth 的 sub 关联，不跨库 join")))),
        h("a", { class: "btn", href: "/admin/api-docs", target: "_blank", rel: "noopener" }, icon("external"), "参考 MKread 的接口文档"))) });
  }

  function systemPage() {
    const session = state.session;
    const themeSelect = h("select", { class: "select", "aria-label": "主题" },
      [["system", "跟随系统"], ["dark", "深色"], ["light", "浅色"]].map(([value, label]) => h("option", { value, selected: storedTheme() === value }, label)));
    themeSelect.addEventListener("change", () => { saveTheme(themeSelect.value); renderThemeButton(); });
    const permissionNames = { read: "查看与导出", "books.write": "上传、下架、恢复书籍", "users.write": "停用、启用用户，吊销会话，调整配额",
      "users.role": "分配管理角色", "releases.write": "撤回版本、设置强制更新" };
    return { el: h("div", {}, pageHead("System", "系统", "当前账号、权限和接口说明。"),
      h("div", { class: "grid-2" },
        h("div", { class: "card panel" }, h("div", { class: "panel-head" }, h("h2", {}, "当前账号")),
          facts([["名称", session.name || "—"], ["Subject", h("span", { class: "mono" }, session.subject)],
            ["角色", ROLE_NAMES[session.role]], ["登录方式", { console: "MKauth 单点登录", device: "App 设备令牌", token: "管理脚本令牌" }[session.via] || session.via]]),
          h("div", { class: "section" }, h("h3", {}, "拥有的权限"),
            h("ul", { class: "timeline" }, session.permissions.map((p) => h("li", {}, h("i", { class: "green" }), h("div", {}, permissionNames[p] || p)))))),
        h("div", { class: "stack" },
          h("div", { class: "card panel" }, h("div", { class: "panel-head" }, h("h2", {}, "外观")),
            h("label", { class: "field" }, h("span", {}, "主题"), themeSelect)),
          h("div", { class: "card panel" }, h("div", { class: "panel-head" }, h("h2", {}, "数据接口")),
            h("p", { class: "muted" }, "管理接口统一在 /api/v1/admin 下，需要管理角色；App 使用的数据接口（/api/v1/catalog、/books、/voices、/app）用设备令牌，两者分开。"),
            h("a", { class: "btn", href: "/admin/api-docs", target: "_blank", rel: "noopener" }, icon("external"), "打开接口文档（OpenAPI）"))))) };
  }

  // ------------------------------------------------------------------ command palette
  let paletteOpen = false;
  function openPalette() {
    if (paletteOpen) return;
    paletteOpen = true;
    const input = h("input", { type: "search", placeholder: "搜索用户、书名、ID，或输入页面名称", "aria-label": "全局搜索", autocomplete: "off" });
    const results = h("div", { class: "palette-results", role: "listbox" });
    const overlay = h("div", { class: "overlay" });
    const wrap = h("div", { class: "palette-wrap" }, h("div", { class: "palette", role: "dialog", "aria-modal": "true", "aria-label": "全局搜索" },
      h("div", { class: "palette-input" }, icon("search"), input, h("kbd", {}, "Esc")), results,
      h("div", { class: "palette-hint" }, h("span", {}, h("kbd", {}, "↑"), " ", h("kbd", {}, "↓"), " 选择"), h("span", {}, h("kbd", {}, "Enter"), " 打开"))));
    let items = [];
    let active = 0;
    let timer = null;
    let seq = 0;
    const close = () => { paletteOpen = false; overlay.remove(); wrap.remove(); removeLayer(); };
    const removeLayer = pushLayer(close);
    wrap.addEventListener("mousedown", (event) => { if (event.target === wrap) close(); });

    function render(groups) {
      items = [];
      fill(results);
      for (const [title, entries] of groups) {
        if (!entries.length) continue;
        results.append(h("h4", {}, title));
        for (const entry of entries) {
          const index = items.length;
          const button = h("button", { class: "palette-item", type: "button", role: "option" }, entry.lead,
            h("div", {}, h("strong", {}, entry.title), entry.sub ? h("small", {}, entry.sub) : null), entry.tail || null);
          button.addEventListener("click", () => { close(); go(entry.href); });
          button.addEventListener("mousemove", () => highlight(index));
          items.push(button);
          results.append(button);
        }
      }
      if (!items.length) results.append(h("div", { class: "palette-empty" }, input.value.trim() ? "没有找到匹配的结果" : "输入关键词开始搜索"));
      highlight(0);
    }
    function highlight(index) {
      active = index;
      items.forEach((item, i) => { item.classList.toggle("active", i === index); item.setAttribute("aria-selected", String(i === index)); });
      items[index]?.scrollIntoView({ block: "nearest" });
    }
    const pageEntries = (q) => NAV.flatMap((group) => group.items)
      .filter((item) => !q || item.label.includes(q) || item.key.includes(q.toLowerCase()))
      .map((item) => ({ title: item.label, sub: "页面", href: `#/${item.key}`, lead: h("span", { class: "avatar" }, icon(item.icon)) }));

    async function search() {
      const q = input.value.trim();
      const mine = ++seq;
      if (!q) { render([["页面", pageEntries("")]]); return; }
      let data = { users: [], books: [] };
      try { data = await api("/search", { params: { q } }); } catch (error) { if (mine === seq) fill(results, errorState(error)); return; }
      if (mine !== seq) return;
      render([
        ["用户", data.users.map((u) => ({ title: u.display_name || u.subject, sub: u.email || u.subject, href: `#/users/${encodeURIComponent(u.subject)}`,
          lead: h("span", { class: "avatar" }, initials(u.display_name)), tail: u.status === "disabled" ? h("span", { class: "pill red" }, "已停用") : null }))],
        ["书籍", data.books.map((b) => ({ title: `《${b.title}》`, sub: `${b.author || "佚名"} · ${b.id}`, href: `#/books/${encodeURIComponent(b.id)}`,
          lead: h("span", { class: "cover" }, initials(b.title)), tail: b.deleted ? h("span", { class: "pill red" }, "已下架") : null }))],
        ["页面", pageEntries(q)],
      ]);
    }
    input.addEventListener("input", () => { clearTimeout(timer); timer = setTimeout(search, 180); });
    input.addEventListener("keydown", (event) => {
      if (event.key === "ArrowDown") { event.preventDefault(); highlight(Math.min(items.length - 1, active + 1)); }
      else if (event.key === "ArrowUp") { event.preventDefault(); highlight(Math.max(0, active - 1)); }
      else if (event.key === "Enter") { event.preventDefault(); items[active]?.click(); }
    });
    document.body.append(overlay, wrap);
    render([["页面", pageEntries("")]]);
    input.focus();
  }

  // ------------------------------------------------------------------ navigation and shell
  const NAV = [
    { title: "平台", items: [
      { key: "overview", label: "概览", icon: "overview", page: overviewPage },
      { key: "users", label: "用户与账号", icon: "users", page: usersPage },
      { key: "audit", label: "审计日志", icon: "audit", page: auditPage },
    ] },
    { title: "MKread", items: [
      { key: "books", label: "书籍", icon: "books", page: booksPage },
      { key: "voices", label: "音色", icon: "voices", page: voicesPage },
      { key: "releases", label: "应用版本", icon: "releases", page: releasesPage },
    ] },
    { title: "其他应用", items: [
      { key: "notes", label: "笔记", icon: "notes", tag: "未接入", page: appPage("笔记", "jev_notes", "个人笔记（jev_notes）的用户与数据管理。") },
      { key: "files", label: "文件", icon: "files", tag: "未接入", page: appPage("文件", "filehub", "文件中心（filehub）的用户与存储管理。") },
    ] },
    { title: "系统", items: [{ key: "system", label: "系统", icon: "system", page: systemPage }] },
  ];
  const PAGES = Object.fromEntries(NAV.flatMap((group) => group.items).map((item) => [item.key, item]));

  let current = { key: null, instance: null };
  let content = null;
  let sidebar = null;
  let themeButton = null;

  function renderThemeButton() {
    if (!themeButton) return;
    const theme = storedTheme();
    const label = { system: "跟随系统", dark: "深色", light: "浅色" }[theme];
    fill(themeButton, icon(theme === "dark" ? "moon" : theme === "light" ? "sun" : "auto"));
    themeButton.title = `主题：${label}（点击切换）`;
    themeButton.setAttribute("aria-label", `主题：${label}`);
  }

  function renderShell() {
    const session = state.session;
    sidebar = h("nav", { class: "sidebar", "aria-label": "主导航" },
      h("div", { class: "brand" }, h("span", { class: "brand-mark" }, "MK"), h("div", {}, h("strong", {}, "MKREAD"), h("small", {}, "数据管理平台"))),
      NAV.map((group) => h("div", { class: "nav-group" }, h("p", {}, group.title),
        group.items.map((item) => h("a", { class: "nav-link", href: `#/${item.key}`, "data-key": item.key, onClick: () => sidebar.classList.remove("open") },
          icon(item.icon), item.label, item.tag ? h("span", { class: "tag" }, item.tag) : null)))),
      h("div", { class: "sidebar-foot" }, "MKread Cloud · 管理接口 v1"));
    themeButton = h("button", { class: "icon-btn", type: "button", onClick: () => {
      const order = ["system", "dark", "light"];
      saveTheme(order[(order.indexOf(storedTheme()) + 1) % order.length]);
      renderThemeButton();
    } });
    renderThemeButton();
    const logout = h("button", { class: "icon-btn", type: "button", title: "退出登录", "aria-label": "退出登录", onClick: async () => {
      try { await fetch("/api/v1/auth/admin/logout", { method: "POST", headers: { "X-MKread-Admin": "1" }, credentials: "same-origin" }); } catch { /* offline */ }
      state.session = null;
      showLogin("signed_out");
    } }, icon("logout"));
    const topbar = h("header", { class: "topbar" },
      h("button", { class: "icon-btn menu-btn", type: "button", "aria-label": "打开导航", onClick: () => sidebar.classList.toggle("open") }, icon("menu")),
      h("button", { class: "search-trigger", type: "button", onClick: openPalette, "aria-label": "全局搜索" }, icon("search"),
        h("span", {}, "搜索用户、书名、ID…"), h("kbd", {}, "⌘K")),
      h("div", { class: "topbar-right" }, themeButton,
        h("div", { class: "account" }, h("div", { class: "account-text" }, h("strong", { class: "ellipsis" }, session.name || session.subject),
          h("small", {}, ROLE_NAMES[session.role])), h("span", { class: "avatar" }, initials(session.name)), logout)));
    content = h("div", { class: "content", id: "main", tabindex: "-1" });
    fill(root(), h("div", { class: "shell" }, sidebar, h("main", {}, topbar, content)));
    current = { key: null, instance: null };
    route();
  }

  function parseRoute() {
    const raw = location.hash.replace(/^#\/?/, "");
    const [key, ...rest] = raw.split("/");
    return { key: key || "overview", id: rest.length ? decodeURIComponent(rest.join("/")) : null };
  }

  function route() {
    if (!state.session || !content) return;
    const { key, id } = parseRoute();
    const entry = PAGES[key];
    if (!entry) { location.replace("#/overview"); return; }
    if (current.key !== key) {
      closeDrawer();
      current = { key, instance: entry.page() };
      fill(content, current.instance.el);
      document.title = `${entry.label} · MKread 数据管理`;
      for (const link of sidebar.querySelectorAll(".nav-link")) {
        const on = link.dataset.key === key;
        link.classList.toggle("active", on);
        if (on) link.setAttribute("aria-current", "page"); else link.removeAttribute("aria-current");
      }
      window.scrollTo(0, 0);
    }
    if (id && current.instance.openDetail) current.instance.openDetail(id);
    else if (!id) closeDrawer();
  }
  window.addEventListener("hashchange", route);

  // ------------------------------------------------------------------ login
  const LOGIN_ERRORS = {
    no_role: "你的账号没有管理权限。请让超级管理员在「用户与账号」里为你分配角色，或在 MKauth 中授予 mkread:viewer / mkread:operator。",
    account_disabled: "这个账号已被停用，请联系管理员。",
    access_denied: "已取消登录。",
    expired: "登录已过期，请重新登录。",
    signed_out: "已退出登录。",
  };

  function showLogin(reason) {
    state.session = null;
    closeDrawer();
    document.querySelectorAll(".overlay, .dialog-wrap, .palette-wrap").forEach((node) => node.remove());
    layers.length = 0;
    paletteOpen = false;
    content = null;
    document.title = "登录 · MKread 数据管理";
    const target = `/api/v1/auth/admin/start${query({ return_to: `/admin/${location.hash || "#/overview"}` })}`;
    const message = LOGIN_ERRORS[reason] || (reason ? `登录失败：${reason}` : null);
    fill(root(), h("div", { class: "login" }, h("main", { class: "login-card" },
      h("span", { class: "brand-mark" }, "MK"), h("p", { class: "eyebrow" }, "MKread Cloud"), h("h1", {}, "数据管理平台"),
      h("p", {}, "使用 MKauth 账号登录。需要只读管理员及以上角色。"),
      message ? h("div", { class: reason === "signed_out" ? "impact" : "login-error", role: "alert" }, message) : null,
      h("a", { class: "btn primary", href: target }, "使用 MKauth 登录"),
      h("div", { class: "login-foot" }, "所有管理操作都会记录操作人、时间和原因。"))));
  }

  // ------------------------------------------------------------------ boot
  async function boot() {
    const params = new URLSearchParams(location.search);
    const loginError = params.get("login_error");
    if (loginError) history.replaceState(null, "", `/admin/${location.hash}`);
    fill(root(), h("div", { class: "login" }, h("div", { class: "login-card" }, skeletonBlock("w60"))));
    try {
      state.session = await api("/session");
    } catch (error) {
      showLogin(loginError || (error.status === 403 ? "no_role" : error.status === 401 ? null : error.message));
      return;
    }
    renderShell();
  }

  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", boot);
  else boot();
})();
