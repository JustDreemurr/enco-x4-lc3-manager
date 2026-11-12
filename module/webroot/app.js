'use strict';

// KernelSU / APatch / WebUI X 都提供 window.ksu.exec(cmd, optionsJson, callbackName)
const MODDIR = '/data/adb/modules/enco_x4_lc3';
const CLI = `sh ${MODDIR}/x4lc3.sh`;
const PKG_RE = /^[A-Za-z][A-Za-z0-9_]*(\.[A-Za-z0-9_]+)+$/;

const $ = (id) => document.getElementById(id);

let seq = 0;
let busy = false;
let current = null;

function exec(cmd) {
  return new Promise((resolve, reject) => {
    if (typeof ksu === 'undefined' || typeof ksu.exec !== 'function') {
      reject(new Error('当前环境没有 ksu.exec 接口，请在 KernelSU / APatch / WebUI X 中打开'));
      return;
    }
    const name = `__x4lc3_cb_${Date.now()}_${seq++}`;
    window[name] = (errno, stdout, stderr) => {
      delete window[name];
      resolve({ errno, stdout: stdout || '', stderr: stderr || '' });
    };
    try {
      ksu.exec(cmd, '{}', name);
    } catch (e) {
      delete window[name];
      reject(e);
    }
  });
}

const cli = (args) => exec(`${CLI} ${args}`);

function toast(msg) {
  const el = $('toast');
  el.textContent = msg;
  el.classList.add('show');
  clearTimeout(toast.timer);
  toast.timer = setTimeout(() => el.classList.remove('show'), 3200);
}

function setBusy(on, text = '') {
  busy = on;
  $('busy').classList.toggle('hidden', !on);
  $('busy-text').textContent = text;
}

function showOutput(text) {
  const el = $('output');
  el.textContent = text;
  el.classList.toggle('hidden', !text);
}

function lastLine(s) {
  const lines = s.trim().split('\n').filter(Boolean);
  return lines[lines.length - 1] || '';
}

// 执行一条命令：显示进度、提示结果、结束后刷新状态
async function run(label, args) {
  if (busy) return null;
  setBusy(true, label);
  let r = null;
  try {
    r = await cli(args);
    const out = r.stdout.trim();
    const err = r.stderr.trim();
    if (r.errno === 0) {
      toast(lastLine(out) || '完成');
    } else {
      toast(lastLine(err) || lastLine(out) || `执行失败（${r.errno}）`);
    }
    showOutput([out, err].filter(Boolean).join('\n'));
  } catch (e) {
    toast(e.message || String(e));
  } finally {
    setBusy(false);
  }
  await refresh();
  return r;
}

async function refresh() {
  try {
    const r = await cli('status --json');
    if (r.errno !== 0) throw new Error(lastLine(r.stderr) || `状态读取失败（${r.errno}）`);
    current = JSON.parse(r.stdout);
    render(current);
  } catch (e) {
    $('lc3-state').textContent = '无法读取状态';
    $('lea-device').textContent = e.message || String(e);
  }
}

function li(text, extra) {
  const el = document.createElement('li');
  const span = document.createElement('span');
  span.textContent = text;
  el.appendChild(span);
  if (extra) el.appendChild(extra);
  return el;
}

function button(text, cls, onClick) {
  const b = document.createElement('button');
  b.type = 'button';
  b.textContent = text;
  if (cls) b.className = cls;
  b.addEventListener('click', onClick);
  return b;
}

function render(s) {
  $('version').textContent = `${s.version || ''} · ${s.manager}`;

  const on = s.effective;
  const state = $('lc3-state');
  state.textContent = on ? '已开启' : '未开启';
  state.classList.toggle('on', on);

  let device = '未知';
  if (!s.btOn) device = '蓝牙已关闭';
  else if (s.leaActive === true) device = 'LE Audio（LC3）';
  else if (s.leaActive === false) device = '经典蓝牙（A2DP）';
  $('lea-device').textContent = `活动设备：${device}（据最近事件推断）`;

  const toggle = $('btn-toggle');
  toggle.disabled = false;
  toggle.textContent = on ? '关闭' : '开启';
  toggle.classList.toggle('off', on);

  // 提示
  const warnings = $('warnings');
  warnings.replaceChildren();
  if (s.pendingRestart) {
    warnings.appendChild(li('白名单改动尚未生效', button('重启蓝牙', 'small', restartBt)));
  }
  if (s.state === 'on' && !on) {
    warnings.appendChild(li('游戏态已被系统改回，LC3 已失效。可重新开启，或打开守护进程'));
  }
  if (!s.anchor) {
    warnings.appendChild(li('没有可用的白名单应用：请先在下方添加一个已安装的应用'));
  }
  if (s.leacoexist !== 'true') {
    warnings.appendChild(li(`persist.sys.bt.leacoexist=${s.leacoexist || '空'}：本机蓝牙可能未启用双模共存，全局 LC3 很可能无效。请点“工具 → 收集诊断信息”`));
  }

  const g = s.gamemode;
  $('d-savegame').textContent = g.savegame || '—';
  $('d-value').textContent = g.value || '—';
  $('d-prefer').textContent = g.llProp === 'true' ? (g.userPrefer || '—') : '本机不需要';
  $('d-coexist').textContent = s.leacoexist || '—';
  $('d-watchdog').textContent = s.watchdog ? '运行中' : '未运行';
  $('events').textContent = s.events.length ? s.events.join('\n') : '（无记录）';

  // 白名单
  const wl = $('whitelist');
  wl.replaceChildren();
  if (!s.whitelist.length) {
    const empty = li('（空）');
    empty.className = 'empty';
    wl.appendChild(empty);
  }
  for (const item of s.whitelist) {
    const row = li(item.pkg, button('删除', 'small', () => delPkg(item.pkg)));
    if (!item.installed) {
      const tag = document.createElement('span');
      tag.className = 'tag';
      tag.textContent = '未安装';
      row.firstChild.appendChild(tag);
    }
    wl.appendChild(row);
  }

  const builtin = $('builtin');
  builtin.replaceChildren();
  if (!s.builtinInstalled.length) {
    const empty = li('（未安装任何内置白名单应用）');
    empty.className = 'empty';
    builtin.appendChild(empty);
  }
  for (const p of s.builtinInstalled) builtin.appendChild(li(p));

  // 设置
  const c = s.config;
  $('cfg-restore').checked = c.restoreOnBoot === '1';
  $('cfg-watchdog').checked = c.watchdog === '1';
  $('cfg-interval').value = c.watchInterval;

  const sel = $('cfg-anchor');
  sel.replaceChildren();
  const auto = new Option(`自动${!c.anchor && s.anchor ? `（当前：${s.anchor}）` : ''}`, '');
  sel.appendChild(auto);
  const candidates = [
    ...s.whitelist.filter((w) => w.installed).map((w) => w.pkg),
    ...s.builtinInstalled,
  ];
  if (c.anchor && !candidates.includes(c.anchor)) candidates.unshift(c.anchor);
  for (const p of new Set(candidates)) sel.appendChild(new Option(p, p));
  sel.value = c.anchor;
}

// ---------- 操作 ----------

async function toggleLc3() {
  if (!current) return;
  if (current.effective) {
    await run('正在关闭全局 LC3…', 'off');
  } else {
    await run('正在开启全局 LC3…', 'on');
  }
  // 活动设备切换需要几秒，稍后再刷新一次
  setTimeout(refresh, 4000);
}

async function restartBt() {
  await run('正在重启蓝牙…', 'restart-bt');
}

async function addPkg(pkg) {
  if (!PKG_RE.test(pkg)) {
    toast('包名格式不正确');
    return;
  }
  if (current && current.whitelist.some((w) => w.pkg === pkg)) {
    toast('已在白名单中');
    return;
  }
  const r = await run('正在添加…', `add --no-restart ${pkg}`);
  if (r && r.errno === 0) $('add-input').value = '';
}

async function delPkg(pkg) {
  if (!PKG_RE.test(pkg)) return;
  if (!confirm(`从白名单删除 ${pkg}？`)) return;
  await run('正在删除…', `del --no-restart ${pkg}`);
}

async function setConfig(key, value) {
  await run('正在保存…', `config set ${key} ${value === '' ? "''" : value}`);
}

async function loadPackages() {
  try {
    const r = await cli('pkgs');
    const list = $('pkg-list');
    list.replaceChildren();
    for (const p of r.stdout.split('\n').map((x) => x.trim()).filter((x) => PKG_RE.test(x))) {
      list.appendChild(new Option(p, p));
    }
  } catch {
    // 无法获取应用列表时仍可手动输入包名
  }
}

function bind() {
  $('btn-toggle').addEventListener('click', toggleLc3);
  $('btn-refresh').addEventListener('click', refresh);
  $('btn-restart').addEventListener('click', restartBt);

  $('add-form').addEventListener('submit', (e) => {
    e.preventDefault();
    addPkg($('add-input').value.trim());
  });

  $('cfg-restore').addEventListener('change', (e) => setConfig('RESTORE_ON_BOOT', e.target.checked ? 1 : 0));
  $('cfg-watchdog').addEventListener('change', (e) => setConfig('WATCHDOG', e.target.checked ? 1 : 0));
  $('cfg-interval').addEventListener('change', (e) => {
    const v = parseInt(e.target.value, 10);
    if (!(v >= 3 && v <= 600)) {
      toast('检查间隔范围为 3–600 秒');
      refresh();
      return;
    }
    setConfig('WATCH_INTERVAL', v);
  });
  $('cfg-anchor').addEventListener('change', (e) => {
    const v = e.target.value;
    if (v && !PKG_RE.test(v)) return;
    setConfig('ANCHOR', v);
  });

  $('btn-log').addEventListener('click', async () => {
    const r = await cli('log');
    showOutput(r.stdout.trim() || '（日志为空）');
  });

  $('btn-diag').addEventListener('click', async () => {
    if (busy) return;
    setBusy(true, '正在收集诊断信息…');
    try {
      const r = await cli('diag');
      showOutput(r.stdout.trim() || r.stderr.trim());
      toast(lastLine(r.stdout) || '完成');
    } catch (e) {
      toast(e.message || String(e));
    } finally {
      setBusy(false);
    }
  });

  $('btn-reset').addEventListener('click', async () => {
    if (!confirm('将关闭全局 LC3、清空自定义白名单、还原 conf 并重启蓝牙。确定？')) return;
    await run('正在撤销全部改动…', 'reset');
  });

  document.addEventListener('visibilitychange', () => {
    if (!document.hidden && !busy) refresh();
  });
}

bind();
refresh();
loadPackages();
