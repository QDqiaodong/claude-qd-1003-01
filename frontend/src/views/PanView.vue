<script setup>
import { computed, onMounted, ref } from 'vue'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：待洗清单 + 洗罐排程看板。
// 上半屏罐体阵列仍按容积画圆柱：罐型 / 当前状态 / 距上次洗罐时长 / 运行资格
// 全部来自后端 overview 一次派生（刷新后一致），超 72h 未洗整只罐描红并标「需先洗罐」，
// 停用罐灰显不能排程，失败罐在罐身直接亮出「清洗失败·可重试」与重试按钮（不是另加一个列表）。
// 点「安排洗罐窗口」在当前时间基础上填默认窗口；勾中多只罐可以共用一个窗口批量排程。
// 下半屏排程看板推进 待执行 → 清洗中 → 已完成：待执行可取消 / 开始，
// 清洗中可「完成」或「标记失败」，失败后原地出现「重试」。
// ------------------------------------------------------------------

const rows = ref([])        // 待洗清单（PanOverview，后端派生）
const schedules = ref([])   // 洗罐安排
const error = ref('')
const tip = ref('')
const checked = ref([])
const filterType = ref('')
const filterStatus = ref('')

// 排程窗口表单
const showForm = ref(false)
const windowStart = ref('')
const windowEnd = ref('')
const requestId = ref(newRequestId())
const submitting = ref(false)
// 演示用：完成回执时勾上即模拟本次清洗验收失败
const failMark = ref({})

const visible = computed(() =>
  rows.value.filter((p) =>
    (!filterType.value || p.panType === filterType.value) &&
    (!filterStatus.value || p.status === filterStatus.value))
)

const dueCount = computed(() => rows.value.filter((p) => p.washDue).length)
const failedCount = computed(() =>
  schedules.value.filter((s) => s.status === '清洗中' && s.failureReason).length)

function newRequestId() {
  return window.crypto && crypto.randomUUID
    ? crypto.randomUUID()
    : String(Date.now()) + '-' + Math.random().toString(16).slice(2)
}

async function load() {
  error.value = ''
  try {
    const q = {}
    if (filterType.value) q.panType = filterType.value
    const [o, s] = await Promise.all([api.panOverview(q), api.listWashSchedules()])
    rows.value = o
    schedules.value = s
    checked.value = checked.value.filter((id) => rows.value.some((p) => p.id === id))
  } catch (e) { error.value = e.message }
}

function toggle(p) {
  // 停用罐、已有未完成安排的罐勾不上
  if (!canSchedule(p)) return
  const i = checked.value.indexOf(p.id)
  if (i >= 0) checked.value.splice(i, 1)
  else checked.value.push(p.id)
}

/** 能否（再）安排洗罐：没有未完成安排即可（停用罐可先排窗口，由闭环开始清洗，不能手工直接洗） */
function canSchedule(p) {
  return !p.activeSchedule
}

function fail(e) {
  const m = e && e.message ? e.message : '操作失败'
  if (/timeout|Network Error/.test(m)) {
    error.value = m + '；如不确定是否生效，可直接刷新页面核对（排程请求幂等，重试不会重复落单）'
  } else {
    error.value = m
  }
}

function pad(n) { return String(n).padStart(2, '0') }

/** datetime-local 的值：本地时区 yyyy-MM-ddTHH:mm */
function localInput(d) {
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`
}

/** 展示用：MM-dd HH:mm */
function fmt(t) {
  if (!t) return '—'
  const d = new Date(t)
  return `${pad(d.getMonth() + 1)}-${pad(d.getDate())} ${pad(d.getHours())}:${pad(d.getMinutes())}`
}

function openForm() {
  if (!checked.value.length) return
  const now = new Date()
  const start = new Date(now.getTime() + 30 * 60 * 1000)
  const end = new Date(start.getTime() + 3 * 60 * 60 * 1000)
  windowStart.value = localInput(start)
  windowEnd.value = localInput(end)
  requestId.value = newRequestId()
  showForm.value = true
}

async function submitSchedule() {
  error.value = ''; tip.value = ''
  if (!checked.value.length) return
  if (!windowStart.value || !windowEnd.value) { error.value = '请填写洗罐窗口的开始与结束时间'; return }
  if (new Date(windowEnd.value) <= new Date(windowStart.value)) {
    error.value = '洗罐窗口结束时间必须晚于开始时间'; return
  }
  submitting.value = true
  let ok = 0
  const fails = []
  // 每条排程独立请求（后端单事务 + requestId 幂等）：失败的不影响已落下的
  for (const id of checked.value) {
    const p = rows.value.find((x) => x.id === id)
    try {
      await api.scheduleWash({
        panId: id,
        panType: p.panType,
        windowStart: windowStart.value,
        windowEnd: windowEnd.value,
        requestId: newRequestId()
      })
      ok++
    } catch (e) { fails.push(`${p.panName}：${e.message}`) }
  }
  submitting.value = false
  showForm.value = false
  checked.value = []
  if (fails.length) {
    error.value = `排程完成：成功 ${ok} 只，失败 ${fails.length} 只 —— ${fails[0]}`
  } else {
    tip.value = `已为 ${ok} 只罐安排洗罐窗口：${fmt(windowStart.value)} ~ ${fmt(windowEnd.value)}`
  }
  await load()
}

async function startWash(s) {
  error.value = ''; tip.value = ''
  try {
    await api.startWash(s.id)
    tip.value = `${s.panName} 已开始清洗，期间不能重复开始或改换罐型`
    await load()
  } catch (e) { fail(e) }
}

async function retryWash(s) {
  error.value = ''; tip.value = ''
  try {
    await api.retryWash(s.id)
    tip.value = `${s.panName} 已重新开始清洗（上次失败 ${s.failCount} 次），请再次回执`
    await load()
  } catch (e) { fail(e) }
}

async function completeWash(s) {
  error.value = ''; tip.value = ''
  const failed = !!failMark.value[s.id]
  try {
    const r = await api.completeWash(s.id, failed ? { failed: true, reason: '值班员标记：清洗验收未通过' } : { failed: false })
    if (r.status === '已完成') {
      tip.value = `${s.panName} 洗罐完成：完成时间 ${fmt(r.completedAt)}，运行资格已恢复`
    } else {
      tip.value = `${s.panName} 本次清洗未通过，仍停在「清洗中」，可安全重试`
    }
    failMark.value[s.id] = false
    await load()
  } catch (e) { fail(e) }
}

async function cancelWash(s) {
  error.value = ''; tip.value = ''
  try {
    await api.cancelWash(s.id)
    tip.value = `${s.panName} 的洗罐安排 ${s.scheduleNo} 已取消`
    await load()
  } catch (e) { fail(e) }
}

async function transfer(p, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferPan(p.id, target)
    tip.value = `${p.panName} 已转为「${target}」`
    await load()
  } catch (e) { fail(e) }
}

const maxCap = computed(() => {
  let m = 1
  for (const p of rows.value) m = Math.max(m, Number(p.capacityM3))
  return m
})

function h(p) {
  return Math.max(48, (Number(p.capacityM3) / maxCap.value) * 150) + 'px'
}

onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">蒸发 / 结晶罐 · 待洗清单</span>
        <span class="panel-desc">超 72 小时未洗标「需先洗罐」；停用罐不能进入洗罐；同一罐只能有一条未完成安排</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">罐体</div><div class="v">{{ rows.length }}</div></div>
        <div class="stat"><div class="k">需先洗罐</div><div class="v">{{ dueCount }}</div></div>
        <div class="stat"><div class="k">失败待重试</div><div class="v">{{ failedCount }}</div></div>
        <div class="stat"><div class="k">有运行资格</div><div class="v">{{ rows.filter(p => p.runEligible).length }}</div></div>
      </div>

      <div class="tool-row">
        <select v-model="filterType" class="fld" @change="load">
          <option value="">全部罐型</option>
          <option>蒸发罐</option>
          <option>结晶罐</option>
        </select>
        <select v-model="filterStatus" class="fld">
          <option value="">全部状态</option>
          <option>运行</option>
          <option>洗罐</option>
          <option>停用</option>
        </select>
        <span class="spacer"></span>
        <span class="picked">已勾 {{ checked.length }} 只</span>
        <button class="act" :disabled="!checked.length" @click="checked = []">清空</button>
        <button class="act primary" :disabled="!checked.length" @click="openForm">安排洗罐窗口</button>
      </div>

      <div v-if="showForm" class="deal-bar">
        <span class="db-label">为 {{ checked.length }} 只罐安排同一窗口：</span>
        <input v-model="windowStart" type="datetime-local" class="fld" />
        <span>~</span>
        <input v-model="windowEnd" type="datetime-local" class="fld" />
        <button class="act primary" :disabled="submitting" @click="submitSchedule">
          {{ submitting ? '提交中…' : '确认排程' }}
        </button>
        <button class="act" @click="showForm = false">收起</button>
      </div>

      <div class="pan-row">
        <div
          v-for="p in visible" :key="p.id"
          class="tank"
          :class="{ due: p.washDue, picked: checked.includes(p.id), off: !canSchedule(p) }"
          @click="toggle(p)"
        >
          <div class="t-cap" :class="'s-' + p.status"></div>
          <div class="t-body" :style="{ height: h(p) }">
            <div class="t-fill" :class="'s-' + p.status"></div>
            <span class="t-code">{{ p.panCode }}</span>
          </div>
          <div class="t-base"></div>
          <div class="t-name">{{ p.panName }}</div>
          <div class="t-type">{{ p.panType }} · {{ p.capacityM3 }}m³ · {{ p.status }}</div>
          <div class="t-wash" :class="{ warn: p.washDue }">
            {{ p.hoursSinceWash === null ? '未洗过' : p.hoursSinceWash + 'h 前洗' }}
            <template v-if="p.washDue"> · 需先洗罐</template>
          </div>

          <!-- 运行资格：与罐状态 / 距上次洗罐时长同源派生，刷新后保持一致 -->
          <div class="t-eligible" :class="{ no: !p.runEligible }">
            {{ p.runEligible ? '● 可运行' : '○ 暂停运行资格' }}
          </div>

          <!-- 停用罐不能直接洗：需先排窗口，再由排程闭环开始 -->
          <div v-if="p.status === '停用'" class="t-block">停用中：需先排窗口，由闭环开始清洗</div>

          <!-- 未完成安排与窗口 -->
          <div v-if="p.activeSchedule" class="t-sched">
            <template v-if="p.activeSchedule.status === '待执行'">
              窗口 {{ fmt(p.activeSchedule.windowStart) }}<br />待执行
            </template>
            <template v-else>
              窗口 {{ fmt(p.activeSchedule.windowStart) }}<br />
              <span v-if="p.activeSchedule.failureReason" class="retry-flag">
                清洗失败 · 可重试
                <button class="act sm danger" @click.stop="retryWash(p.activeSchedule)">重试</button>
              </span>
              <span v-else>清洗中</span>
            </template>
          </div>

          <span v-if="checked.includes(p.id)" class="t-tick">✓</span>
        </div>
        <div v-if="!visible.length" class="empty">没有符合条件的罐体</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">洗罐排程看板</span>
        <span class="panel-desc">待执行可取消 / 开始；清洗中不能重复开始或改换罐型；完成同时写完成时间并恢复运行资格</span>
      </div>
      <div class="ws-list">
        <div v-for="s in schedules" :key="s.id" class="ws-row">
          <span class="ws-no">{{ s.scheduleNo }}</span>
          <span class="ws-pan">{{ s.panName }}<span class="ws-type">（{{ s.panType }}）</span></span>
          <span class="ws-win">窗口 {{ fmt(s.windowStart) }} ~ {{ fmt(s.windowEnd) }}</span>
          <span class="ws-st" :class="'st-' + s.status">{{ s.status }}</span>

          <template v-if="s.status === '待执行'">
            <span class="ws-time">排于 {{ fmt(s.createdAt) }}</span>
            <button class="act sm primary" @click="startWash(s)">开始清洗</button>
            <button class="act sm" @click="cancelWash(s)">取消</button>
          </template>

          <template v-else-if="s.status === '清洗中'">
            <span v-if="s.failureReason" class="ws-fail">
              ✕ {{ s.failureReason }}（失败 {{ s.failCount }} 次，末次 {{ fmt(s.lastAttemptAt) }}）
              <button class="act sm danger" @click="retryWash(s)">重试</button>
            </span>
            <span v-else class="ws-time">开始于 {{ fmt(s.startedAt) }}</span>
            <label class="ws-failopt" v-if="!s.failureReason">
              <input type="checkbox" v-model="failMark[s.id]" /> 模拟本次验收失败
            </label>
            <button class="act sm primary" @click="completeWash(s)">
              {{ failMark[s.id] ? '标记失败' : '完成洗罐' }}
            </button>
          </template>

          <template v-else>
            <span class="ws-time">
              {{ s.status === '已完成' ? '完成于 ' + fmt(s.completedAt) : '取消于 ' + fmt(s.cancelledAt) }}
            </span>
          </template>
        </div>
        <div v-if="!schedules.length" class="empty">暂无洗罐安排</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">罐体状态流转</span>
        <span class="panel-desc">停用中的罐不能直接洗；有未完成安排时不能停用；洗罐只能由排程闭环驱动</span>
      </div>
      <div class="pan-list">
        <div v-for="p in rows" :key="'pl-' + p.id" class="pl-row">
          <span class="pl-code">{{ p.panCode }}</span>
          <span class="pl-name">{{ p.panName }}</span>
          <span class="pl-st">{{ p.status }}</span>
          <span class="pl-elig" :class="{ no: !p.runEligible }">
            {{ p.runEligible ? '有运行资格' : '无运行资格' }}
          </span>
          <button class="act sm" :disabled="p.status === '停用' || !!p.activeSchedule" @click="transfer(p, '停用')">停用</button>
          <button
            class="act sm"
            :disabled="p.status === '运行' || !!p.activeSchedule"
            @click="transfer(p, '运行')"
          >转运行</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.picked { font-size: 12px; color: var(--primary-deep); font-weight: 700; }

.deal-bar {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  border: 1px dashed var(--primary-soft); border-radius: 9px;
  background: var(--primary-wash); padding: 10px 12px; margin-bottom: 12px;
  font-size: 13px;
}
.db-label { font-weight: 700; color: var(--primary-deep); }

.pan-row { display: flex; gap: 16px; flex-wrap: wrap; align-items: flex-start; }

.tank {
  position: relative; width: 118px; text-align: center; cursor: pointer;
  padding: 8px 6px; border: 1px solid var(--line); border-radius: 10px; background: #fff;
}
.tank:hover { border-color: var(--primary-soft); }
.tank.picked { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); background: var(--primary-wash); }
.tank.due { border-color: #ef9a9a; }
.tank.off { opacity: 0.62; cursor: not-allowed; }

.t-cap { height: 10px; border-radius: 5px 5px 0 0; background: var(--primary); margin: 0 8px; }
.t-cap.s-洗罐 { background: #ffb300; }
.t-cap.s-停用 { background: #bdbdbd; }

.t-body {
  margin: 0 8px; border-radius: 3px; background: #eef1e2;
  border: 1px solid var(--line); position: relative; overflow: hidden;
  display: flex; align-items: center; justify-content: center;
}
.t-fill { position: absolute; bottom: 0; left: 0; right: 0; height: 62%; }
.t-fill.s-运行 { background: linear-gradient(180deg, #d4e157, var(--primary)); }
.t-fill.s-洗罐 { background: repeating-linear-gradient(45deg, #ffe082, #ffe082 6px, #ffd54f 6px, #ffd54f 12px); }
.t-fill.s-停用 { background: #e0e0e0; }
.t-code { position: relative; font-size: 11px; font-weight: 800; color: var(--primary-deep); }

.t-base { height: 6px; margin: 0 4px; border-radius: 0 0 6px 6px; background: var(--primary-deep); }

.t-name { font-size: 12px; font-weight: 700; color: var(--primary-deep); margin-top: 5px; }
.t-type { font-size: 10px; color: var(--muted); }
.t-wash { font-size: 10px; color: var(--muted); margin-top: 3px; }
.t-wash.warn { color: #c62828; font-weight: 800; }
.t-tick { position: absolute; top: 4px; right: 6px; color: var(--primary-deep); font-weight: 800; }

.t-eligible { font-size: 10px; font-weight: 700; color: #2e7d32; margin-top: 2px; }
.t-eligible.no { color: var(--muted); }
.t-block { font-size: 10px; color: #c62828; font-weight: 700; margin-top: 2px; }

.t-sched {
  margin-top: 4px; font-size: 10px; line-height: 1.5; color: var(--primary-deep);
  background: var(--primary-wash); border: 1px solid #e0e8bd; border-radius: 6px; padding: 3px 4px;
}
.retry-flag { color: #c62828; font-weight: 800; display: inline-block; }

.empty { font-size: 13px; color: var(--muted); padding: 10px; }

/* 排程看板 */
.ws-list { display: flex; flex-direction: column; gap: 5px; }
.ws-row {
  display: flex; align-items: center; gap: 10px; flex-wrap: wrap;
  padding: 7px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px;
}
.ws-no { font-weight: 800; color: var(--primary-deep); width: 108px; }
.ws-pan { font-weight: 700; min-width: 130px; }
.ws-type { color: var(--muted); font-weight: 400; }
.ws-win { color: var(--ink); }
.ws-time { color: var(--muted); font-size: 11px; }
.ws-fail { color: #c62828; font-weight: 700; font-size: 11px; }
.ws-failopt { font-size: 11px; color: var(--muted); display: flex; align-items: center; gap: 3px; }

.ws-st { font-size: 11px; font-weight: 800; padding: 1px 8px; border-radius: 9px; }
.ws-st.st-待执行 { background: #fff8e1; color: #f57f17; }
.ws-st.st-清洗中 { background: #e3f2fd; color: #1565c0; }
.ws-st.st-已完成 { background: #e8f5e9; color: #2e7d32; }
.ws-st.st-已取消 { background: #eceff1; color: #607d8b; }

.act.danger { border-color: #ef9a9a; color: #c62828; }
.act.danger:hover { background: #fdecea; }

/* 状态流转 */
.pan-list { display: flex; flex-direction: column; gap: 5px; }
.pl-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.pl-code { font-weight: 800; color: var(--primary-deep); width: 64px; }
.pl-name { flex: 1; }
.pl-st { color: var(--muted); width: 48px; }
.pl-elig { font-size: 11px; font-weight: 700; color: #2e7d32; width: 84px; }
.pl-elig.no { color: var(--muted); }
</style>
