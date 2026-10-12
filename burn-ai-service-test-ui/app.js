const samples = {
  burn: {
    title: "浅Ⅱ度热液烫伤测试样例",
    desc: "默认样例会提取水疱、深度、面积、部位、糖尿病状态等指标，再运行烧伤决策树。",
    text: "水疱存在状态：存在；烧伤致伤机制：热液烫伤；伤后时间：6；患者年龄：40；患者体型状态：正常；合并创伤状态：不存在；局部感染临床征象：明确无；用药：布洛芬缓释胶囊，每次0.3克，每日两次；创面完全愈合用时：未知；创面局部面积：173；医生记录烧伤部位：[左手, 右前臂, 躯干前侧]；糖尿病状态：无；烧伤视觉初判深度：浅Ⅱ度烧伤；",
    image: {
      水疱存在状态: "存在",
      烧伤视觉初判深度: "浅Ⅱ度烧伤",
      烧伤创面表面湿润度: "湿润",
      烧伤创面主色: "红润",
      焦痂视觉存在状态: "不存在",
    },
  },
  scar: {
    title: "增生性瘢痕测试样例",
    desc: "默认样例会提取颜色、血管性红度、隆起高度、来源、伤后时长和触诊柔软度，再运行瘢痕决策树。",
    text: "瘢痕主色类别：红色；瘢痕血管性红度：红色；瘢痕色素状态：色素沉着；瘢痕隆起高度：2；瘢痕来源：烧伤；伤后时长：90；瘢痕生长过程：缓慢增长；瘢痕疼痛存在状态：存在；瘢痕瘙痒存在状态：存在；Fitzpatrick 皮肤分型：三型；患者年龄：36；生长发育/激素阶段：非妊娠成人；既往/当前瘢痕治疗类别：[硅酮凝胶]；瘢痕功能受限类别：明确无；瘢痕疼痛评分：3；瘢痕瘙痒评分：5；瘢痕僵硬评分：4；POSAS：32；瘢痕触诊柔软度：柔软，轻压可变形；",
    image: {
      瘢痕血管性红度: "红色",
      瘢痕色素状态: "色素沉着",
      瘢痕隆起高度: 2,
      瘢痕触诊柔软度: "柔软，轻压可变形",
    },
  },
};

const state = {
  indicators: {},
  indicatorCodes: {},
  codeByName: {},
  sources: {},
  reportResponse: null,
  eventSource: null,
};

const $ = id => document.getElementById(id);
const json = value => JSON.stringify(value, null, 2);

function activeDisease() {
  return $("disease").value;
}

function setBusy(id, busy) {
  const button = $(id);
  button.disabled = busy;
  button.dataset.label ||= button.textContent;
  button.textContent = busy ? "运行中..." : button.dataset.label;
}

function nowRequestId() {
  return `ui-${activeDisease()}-${Date.now()}-${Math.random().toString(16).slice(2)}`;
}

function parseValue(raw) {
  const text = raw.trim();
  if (!text) return "";
  try {
    return JSON.parse(text);
  } catch {
    const numeric = Number(text);
    return Number.isFinite(numeric) && /^-?\d+(\.\d+)?$/.test(text) ? numeric : text;
  }
}

function loadSample() {
  const sample = samples[activeDisease()];
  $("caseTitle").textContent = sample.title;
  $("caseDesc").textContent = sample.desc;
  $("clinicalText").value = sample.text;
  $("imageJson").value = json(sample.image);
  state.indicators = {};
  state.indicatorCodes = {};
  state.codeByName = {};
  state.sources = {};
  state.reportResponse = null;
  $("extractionMeta").textContent = "护士文本和检测后的图片 JSON 将通过同一个接口提交。";
  $("reportMeta").textContent = "未生成";
  $("requestIdView").textContent = "requestId: -";
  $("logs").innerHTML = "";
  $("decisionJson").textContent = "{}";
  $("rawJson").textContent = "{}";
  $("reportSections").innerHTML = "";
  renderIndicators();
}

async function postJson(url, body) {
  const started = performance.now();
  const response = await fetch(url, {
    method: "POST",
    headers: { "content-type": "application/json" },
    body: JSON.stringify(body),
  });
  const text = await response.text();
  let payload;
  try {
    payload = JSON.parse(text);
  } catch {
    payload = { raw: text };
  }
  if (!response.ok) {
    throw new Error(payload.message || payload.error || `HTTP ${response.status}`);
  }
  return { payload, clientMs: Math.round(performance.now() - started) };
}

async function extractAll() {
  setBusy("extractAllBtn", true);
  $("logs").innerHTML = "";
  const requestId = nowRequestId();
  $("requestIdView").textContent = `requestId: ${requestId}`;
  if (state.eventSource) state.eventSource.close();
  state.eventSource = new EventSource(`/api/indicators/logs?requestId=${encodeURIComponent(requestId)}`);
  state.eventSource.addEventListener("ai-progress", event => {
    const payload = JSON.parse(event.data);
    addLog("EXTRACT", payload.message);
  });
  state.eventSource.onerror = () => addLog("SSE", "日志连接等待中或已断开。");
  try {
    const featureResult = JSON.parse($("imageJson").value);
    const { payload, clientMs } = await postJson("/api/indicators/extract", {
      requestId,
      diseaseCode: activeDisease(),
      clinicalText: $("clinicalText").value,
      featureResult,
    });
    state.indicators = { ...(payload.indicators || {}) };
    if (state.eventSource) {
      state.eventSource.close();
      state.eventSource = null;
    }
    state.indicatorCodes = { ...(payload.indicatorCodes || {}) };
    state.codeByName = {};
    const names = Object.keys(state.indicators);
    const codes = Object.keys(state.indicatorCodes);
    names.forEach((name, index) => { if (codes[index]) state.codeByName[name] = codes[index]; });
    state.sources = Object.fromEntries(names.map(name => [name, "统一提取"]));
    $("extractionMeta").textContent = `服务端 ${payload.elapsedMs}ms，浏览器侧 ${clientMs}ms，合并 ${names.length} 个指标，错误 ${payload.errors?.length || 0} 个。`;
    $("rawJson").textContent = json(payload);
    renderIndicators();
  } catch (error) {
    $("extractionMeta").textContent = `统一指标提取失败：${error.message}`;
  } finally {
    if (state.eventSource) {
      state.eventSource.close();
      state.eventSource = null;
    }
    setBusy("extractAllBtn", false);
  }
}

function collectIndicatorsFromTable() {
  const rows = Array.from(document.querySelectorAll("[data-indicator-row]"));
  const next = {};
  const sources = {};
  const codes = {};
  rows.forEach(row => {
    const key = row.querySelector("[data-key]").value.trim();
    const code = row.querySelector("[data-code]").value.trim();
    if (!key || !code) return;
    const value = parseValue(row.querySelector("[data-value]").value);
    next[key] = value;
    codes[code] = value;
    sources[key] = row.querySelector("[data-source]").textContent || "手动";
  });
  state.indicators = next;
  state.indicatorCodes = codes;
  state.sources = sources;
}

function renderIndicators() {
  const rows = $("indicatorRows");
  rows.innerHTML = "";
  const entries = Object.entries(state.indicators);
  for (const [name, value] of entries) {
    const code = state.codeByName[name] || name;
    const tr = document.createElement("tr");
    tr.dataset.indicatorRow = "true";
    tr.innerHTML = `
      <td><input data-key value="${escapeHtml(name)}" readonly /></td>
      <td><input data-code value="${escapeHtml(code)}" readonly /></td>
      <td><input data-value value="${escapeHtml(typeof value === "string" ? value : JSON.stringify(value))}" /></td>
      <td><span data-source class="source-tag">${escapeHtml(state.sources[name] || "手动")}</span></td>
      <td><button class="remove" type="button">删除</button></td>
    `;
    tr.querySelector(".remove").addEventListener("click", () => {
      delete state.indicators[name];
      delete state.sources[name];
      delete state.indicatorCodes[code];
      delete state.codeByName[name];
      renderIndicators();
    });
    rows.appendChild(tr);
  }
  $("indicatorSummary").textContent = entries.length
    ? `当前将按稳定 indicator_code 提交 ${entries.length} 个指标。指标值可以编辑，数组/对象请填合法 JSON。`
    : "还没有提取结果。请先点击统一指标提取。";
}

function addLog(stage, message, data) {
  const line = document.createElement("div");
  line.className = "log-line";
  const time = new Date().toLocaleTimeString();
  line.textContent = `${time} ${message}`;
  $("logs").appendChild(line);
  $("logs").scrollTop = $("logs").scrollHeight;
}

async function generateReport() {
  collectIndicatorsFromTable();
  if (Object.keys(state.indicators).length === 0) {
    addLog("ERROR", "没有可提交的指标。");
    return;
  }

  setBusy("generateBtn", true);
  $("logs").innerHTML = "";
  const requestId = nowRequestId();
  $("requestIdView").textContent = `requestId: ${requestId}`;

  if (state.eventSource) state.eventSource.close();
  state.eventSource = new EventSource(`/api/reports/logs?requestId=${encodeURIComponent(requestId)}`);
  state.eventSource.addEventListener("ai-progress", event => {
    const payload = JSON.parse(event.data);
    addLog(payload.stage, payload.message, payload.data);
  });
  state.eventSource.onerror = () => addLog("SSE", "日志连接等待中或已断开。");

  try {
    const { payload, clientMs } = await postJson("/api/reports/generate", {
      diseaseCode: activeDisease(),
      requestId,
      indicators: state.indicatorCodes,
    });
    state.reportResponse = payload;
    if (state.eventSource) {
      state.eventSource.close();
      state.eventSource = null;
    }
    $("reportMeta").textContent = `服务端 ${payload.elapsedMs}ms，浏览器侧 ${clientMs}ms`;
    renderReport(payload);
    $("decisionJson").textContent = json({
      normalizedIndicators: payload.normalizedIndicators,
      normalizedIndicatorCodes: payload.normalizedIndicatorCodes,
      decisionOutputs: payload.decisionOutputs,
      decisionOutputCodes: payload.decisionOutputCodes,
      trees: payload.trees,
      timings: payload.timings,
      warnings: payload.warnings,
    });
    $("rawJson").textContent = json(payload);
  } catch (error) {
    addLog("ERROR", `报告生成失败：${error.message}`);
  } finally {
    if (state.eventSource) {
      state.eventSource.close();
      state.eventSource = null;
    }
    setBusy("generateBtn", false);
  }
}

function renderReport(payload) {
  const target = $("reportSections");
  target.innerHTML = "";
  Object.entries(payload.report || {}).forEach(([title, body]) => {
    const card = document.createElement("div");
    card.className = "report-card";
    card.innerHTML = `<h4>${escapeHtml(title)}</h4>`;
    card.appendChild(renderReportValue(body));
    target.appendChild(card);
  });
}

function renderReportValue(value) {
  if (value && typeof value === "object" && value.table) return renderReportTable(value.table);
  if (Array.isArray(value)) {
    const ol = document.createElement("ol");
    value.forEach(item => { const li = document.createElement("li"); li.textContent = String(item); ol.appendChild(li); });
    return ol;
  }
  if (value && typeof value === "object") {
    const box = document.createElement("div");
    Object.entries(value).forEach(([key, item]) => {
      const row = document.createElement("div");
      row.className = "report-field";
      const label = document.createElement("strong"); label.textContent = `${key}：`;
      row.appendChild(label); row.appendChild(renderReportValue(item)); box.appendChild(row);
    });
    return box;
  }
  const p = document.createElement("p"); p.textContent = value == null ? "" : String(value); return p;
}

function renderReportTable(table) {
  const element = document.createElement("table");
  const columns = table.columns || [];
  const thead = document.createElement("thead");
  const header = document.createElement("tr");
  columns.forEach(column => { const th = document.createElement("th"); th.textContent = column; header.appendChild(th); });
  thead.appendChild(header); element.appendChild(thead);
  const tbody = document.createElement("tbody");
  (table.rows || []).forEach(data => {
    const row = document.createElement("tr");
    columns.forEach(column => { const td = document.createElement("td"); td.textContent = data?.[column] ?? ""; row.appendChild(td); });
    tbody.appendChild(row);
  });
  element.appendChild(tbody); return element;
}

function escapeHtml(value) {
  return String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");
}

$("disease").addEventListener("change", loadSample);
$("extractAllBtn").addEventListener("click", extractAll);
$("generateBtn").addEventListener("click", generateReport);

loadSample();
