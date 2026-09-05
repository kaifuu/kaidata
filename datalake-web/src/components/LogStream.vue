<template>
  <div class="ls-wrap">
    <div ref="box" class="ls-box" :style="{ height }" @scroll="onScroll">
      <div v-if="skipped > 0" class="ls-skip">… 日志过长，已省略前 {{ skipped }} 行（仅展示末尾 {{ shown.length }} 行）</div>
      <div v-for="(ln, i) in shown" :key="i" class="ls-line" :class="lineCls(ln)"><span class="ls-no">{{ skipped + i + 1 }}</span><span class="ls-txt">{{ ln || ' ' }}</span></div>
      <div v-if="running" class="ls-run"><i class="ls-pulse" />实时输出中…</div>
      <div v-else-if="!lines.length" class="ls-empty">等待日志…</div>
    </div>
    <transition name="ls-fade">
      <button v-show="!follow" class="ls-bottom" title="回到底部（恢复自动跟随）" @click="goBottom">
        <el-icon><Bottom /></el-icon><span>回到底部</span>
      </button>
    </transition>
    <div class="ls-meta">
      <span>{{ lines.length }} 行</span>
      <span v-if="running" class="ls-live"><i />LIVE</span>
      <span v-else-if="follow" class="ls-ok">✓ 最新</span>
      <span v-else class="ls-paused">已暂停跟随</span>
    </div>
  </div>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { Bottom } from '@element-plus/icons-vue'

const props = withDefaults(defineProps<{ log: string; running?: boolean; height?: string }>(), {
  log: '', running: false, height: '380px'
})

/** 渲染上限：docker build/load 全量日志可达数千行，全渲染 DOM 太重；留尾部 + 省略提示 */
const MAX_LINES = 2000
/** 距底 <40px 视为"贴底"，新日志到达才自动跟随 */
const NEAR_BOTTOM = 40

const box = ref<HTMLElement>()
const follow = ref(true)

const lines = computed<string[]>(() => (props.log || '').split('\n'))
const shown = computed<string[]>(() => (lines.value.length > MAX_LINES ? lines.value.slice(-MAX_LINES) : lines.value))
const skipped = computed(() => Math.max(0, lines.value.length - shown.value.length))

/** 行级着色：命令行/成功行提亮，错误行红、告警行黄 */
function lineCls(ln: string): string {
  const s = (ln || '').toUpperCase()
  if (s.includes('ERROR') || s.includes('FAIL') || ln.includes('✗')) return 'l-err'
  if (s.includes('WARN') || ln.includes('⚠')) return 'l-warn'
  if (/^\$/.test(ln)) return 'l-cmd'
  if (ln.startsWith('✓')) return 'l-ok'
  return ''
}

function onScroll() {
  const el = box.value
  if (!el) return
  follow.value = el.scrollHeight - el.scrollTop - el.clientHeight < NEAR_BOTTOM
}
function goBottom() {
  const el = box.value
  if (!el) return
  el.scrollTop = el.scrollHeight
  follow.value = true
}

watch(() => props.log, async () => {
  if (!follow.value) return          // 用户上滚阅读中：不打扰
  await nextTick()
  const el = box.value
  if (el) el.scrollTop = el.scrollHeight
})

defineExpose({ goBottom })
</script>

<style scoped>
.ls-wrap { position: relative; }
.ls-box {
  background: #0b1220; border: 1px solid var(--tech-panel-border, #2a3a5c); border-radius: 8px;
  padding: 10px 0 10px 0; overflow: auto;
  font-family: ui-monospace, Menlo, Consolas, monospace; font-size: 12px; line-height: 1.65;
  color: #c9d6f2; box-sizing: border-box;
}
html:not(.dark) .ls-box { background: #0e1526; }
.ls-line { display: flex; gap: 10px; padding: 0 12px; white-space: pre-wrap; word-break: break-all; }
.ls-line:hover { background: rgba(255, 255, 255, 0.04); }
.ls-no { flex-shrink: 0; width: 40px; text-align: right; color: #55658c; user-select: none; font-size: 11px; padding-top: 1px; }
.ls-txt { flex: 1; min-width: 0; }
.l-cmd .ls-txt { color: #6ee7ff; }
.l-ok .ls-txt { color: #34e0a1; }
.l-err .ls-txt { color: #ff6b81; }
.l-warn .ls-txt { color: #ffc24b; }
.ls-skip { padding: 4px 12px 8px; color: #55658c; font-size: 11px; border-bottom: 1px dashed #2a3a5c; margin-bottom: 6px; }
.ls-run { display: flex; align-items: center; gap: 7px; padding: 6px 12px 2px; color: #6ee7ff; font-size: 11px; }
.ls-pulse { width: 7px; height: 7px; border-radius: 50%; background: #6ee7ff; animation: ls-blink 1s ease-in-out infinite; }
@keyframes ls-blink { 0%, 100% { opacity: .25 } 50% { opacity: 1 } }
.ls-empty { padding: 24px 12px; color: #55658c; text-align: center; }

/* 回到底部悬浮按钮 */
.ls-bottom {
  position: absolute; right: 14px; bottom: 34px; z-index: 2;
  display: inline-flex; align-items: center; gap: 5px;
  padding: 4px 10px; border-radius: 14px; border: 1px solid #2a3a5c;
  background: rgba(13, 22, 44, 0.92); color: #6ee7ff;
  font-size: 12px; cursor: pointer; box-shadow: 0 4px 14px rgba(0, 0, 0, 0.4);
  transition: filter .15s ease;
}
.ls-bottom:hover { filter: brightness(1.15); }
.ls-fade-enter-active, .ls-fade-leave-active { transition: opacity .18s ease; }
.ls-fade-enter-from, .ls-fade-leave-to { opacity: 0; }

/* 底部状态条 */
.ls-meta {
  display: flex; align-items: center; gap: 10px;
  margin-top: 6px; font-size: 11.5px; color: var(--tech-text-muted);
}
.ls-live { display: inline-flex; align-items: center; gap: 5px; color: var(--tech-primary); font-weight: 600; letter-spacing: .5px; }
.ls-live i { width: 7px; height: 7px; border-radius: 50%; background: var(--tech-primary); animation: ls-blink 1s ease-in-out infinite; }
.ls-ok { color: var(--tech-success); }
.ls-paused { color: var(--tech-warn); }
</style>
