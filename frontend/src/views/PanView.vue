<script setup>
import { computed, onMounted, ref, watch } from 'vue'
import router from '../router'
import api from '../api'

// ------------------------------------------------------------------
// 页面操作范式：罐体阵列 + 洗罐倒计时。
// 每个罐画成一只立着的圆柱：罐身高度按容积归一化，罐口一圈表示状态，
// 罐底一枚倒计时徽章显示「距上次洗罐多少小时」，超过 72 小时整只罐描红。
// 顶部一条「按罐型筛选 + 批量洗罐」操作条：勾中若干罐可以一次洗完。
// ------------------------------------------------------------------

const routeQuery = computed(() => router.currentRoute.value.query || {})
const pans = ref([])
const error = ref('')
const tip = ref('')
const checked = ref([])
const filterType = ref('')

const visible = computed(() =>
  filterType.value ? pans.value.filter((p) => p.panType === filterType.value) : pans.value
)

const dueCount = computed(() => pans.value.filter((p) => p.isWashDue).length)

async function load() {
  error.value = ''
  try {
    const q = routeQuery.value.q
    pans.value = await api.listPans(q ? { panType: q } : {})
    if (!pans.value.length && q) pans.value = await api.listPans()
    checked.value = checked.value.filter((id) => pans.value.some((p) => p.id === id))
  } catch (e) { error.value = e.message }
}

function toggle(id) {
  const i = checked.value.indexOf(id)
  if (i >= 0) checked.value.splice(i, 1)
  else checked.value.push(id)
}

async function washOne(id) {
  error.value = ''; tip.value = ''
  try {
    await api.washPan(id)
    tip.value = '洗罐已记录'
    await load()
  } catch (e) { error.value = e.message }
}

async function washBatch() {
  if (!checked.value.length) return
  error.value = ''; tip.value = ''
  let ok = 0
  const fails = []
  for (const id of checked.value) {
    try { await api.washPan(id); ok++ } catch (e) { fails.push(e.message) }
  }
  tip.value = `批量洗罐完成：成功 ${ok} 只` + (fails.length ? `，失败 ${fails.length} 只（${fails[0]}）` : '')
  checked.value = []
  await load()
}

async function transfer(p, target) {
  error.value = ''; tip.value = ''
  try {
    await api.transferPan(p.id, target)
    tip.value = `${p.panName} 已转为「${target}」`
    await load()
  } catch (e) { error.value = e.message }
}

const maxCap = computed(() => {
  let m = 1
  for (const p of pans.value) m = Math.max(m, Number(p.capacityM3))
  return m
})

function h(p) {
  return Math.max(48, (Number(p.capacityM3) / maxCap.value) * 150) + 'px'
}

watch(() => routeQuery.value.q, () => load())
onMounted(load)
</script>

<template>
  <div>
    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">蒸发 / 结晶罐 · 罐体阵列</span>
        <span class="panel-desc">罐高按容积归一；超 72 小时未洗的罐整只描红</span>
      </div>

      <div v-if="error" class="err">{{ error }}</div>
      <div v-if="tip" class="ok-tip">{{ tip }}</div>

      <div class="stat-row">
        <div class="stat"><div class="k">罐体</div><div class="v">{{ pans.length }}</div></div>
        <div class="stat"><div class="k">运行</div><div class="v">{{ pans.filter(p => p.status === '运行').length }}</div></div>
        <div class="stat"><div class="k">该洗罐</div><div class="v">{{ dueCount }}</div></div>
      </div>

      <div class="tool-row">
        <select v-model="filterType" class="fld">
          <option value="">全部罐型</option>
          <option>蒸发罐</option>
          <option>结晶罐</option>
        </select>
        <span class="spacer"></span>
        <span class="picked">已勾 {{ checked.length }} 只</span>
        <button class="act" :disabled="!checked.length" @click="checked = []">清空</button>
        <button class="act primary" :disabled="!checked.length" @click="washBatch">批量洗罐</button>
      </div>

      <div class="pan-row">
        <div v-for="p in visible" :key="p.id" class="tank" :class="{ due: p.isWashDue, picked: checked.includes(p.id) }" @click="toggle(p.id)">
          <div class="t-cap" :class="'s-' + p.status"></div>
          <div class="t-body" :style="{ height: h(p) }">
            <div class="t-fill" :class="'s-' + p.status"></div>
            <span class="t-code">{{ p.panCode }}</span>
          </div>
          <div class="t-base"></div>
          <div class="t-name">{{ p.panName }}</div>
          <div class="t-type">{{ p.panType }} · {{ p.capacityM3 }}m³</div>
          <div class="t-wash" :class="{ warn: p.isWashDue }">
            {{ p.hoursSinceWash === null ? '未洗过' : p.hoursSinceWash + 'h 前洗' }}
          </div>
          <span v-if="checked.includes(p.id)" class="t-tick">✓</span>
        </div>
        <div v-if="!visible.length" class="empty">没有符合条件的罐体</div>
      </div>
    </div>

    <div class="panel">
      <div class="panel-head">
        <span class="panel-title">罐体状态流转</span>
        <span class="panel-desc">停用中的罐不能直接洗；超 72 小时未洗不允许继续运行</span>
      </div>
      <div class="pan-list">
        <div v-for="p in pans" :key="p.id" class="pl-row">
          <span class="pl-code">{{ p.panCode }}</span>
          <span class="pl-name">{{ p.panName }}</span>
          <span class="pl-st">{{ p.status }}</span>
          <button class="act sm" @click="washOne(p.id)">洗罐</button>
          <button class="act sm" :disabled="p.status === '运行'" @click="transfer(p, '运行')">转运行</button>
          <button class="act sm" :disabled="p.status === '洗罐'" @click="transfer(p, '洗罐')">转洗罐</button>
          <button class="act sm" :disabled="p.status === '停用'" @click="transfer(p, '停用')">停用</button>
        </div>
      </div>
    </div>
  </div>
</template>

<style scoped>
.tool-row { display: flex; align-items: center; gap: 8px; margin-bottom: 12px; flex-wrap: wrap; }
.spacer { flex: 1; }
.picked { font-size: 12px; color: var(--primary-deep); font-weight: 700; }

.pan-row { display: flex; gap: 16px; flex-wrap: wrap; align-items: flex-end; }

.tank {
  position: relative; width: 104px; text-align: center; cursor: pointer;
  padding: 8px 6px; border: 1px solid var(--line); border-radius: 10px; background: #fff;
}
.tank:hover { border-color: var(--primary-soft); }
.tank.picked { border-color: var(--primary); box-shadow: 0 0 0 2px var(--primary-wash); background: var(--primary-wash); }
.tank.due { border-color: #ef9a9a; }

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

.empty { font-size: 13px; color: var(--muted); padding: 10px; }

.pan-list { display: flex; flex-direction: column; gap: 5px; }
.pl-row { display: flex; align-items: center; gap: 8px; padding: 6px 10px; border: 1px solid var(--line); border-radius: 8px; font-size: 12px; }
.pl-code { font-weight: 800; color: var(--primary-deep); width: 64px; }
.pl-name { flex: 1; }
.pl-st { color: var(--muted); width: 56px; }
</style>
