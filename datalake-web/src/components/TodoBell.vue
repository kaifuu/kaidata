<template>
  <el-popover placement="bottom-end" :width="280" trigger="click" popper-class="todo-pop">
    <template #reference>
      <el-badge :value="total" :hidden="total === 0" :max="99">
        <button class="todo-btn" aria-label="我的待办">
          <el-icon :size="18"><Bell /></el-icon>
        </button>
      </el-badge>
    </template>
    <div class="todo-body">
      <div class="todo-title">我的待办</div>
      <div class="todo-item" v-for="t in items" :key="t.key" @click="go(t.path)">
        <span class="todo-ico" :style="{ background: t.soft, color: t.on }"><el-icon><component :is="t.icon" /></el-icon></span>
        <span class="todo-name">{{ t.name }}</span>
        <el-tag v-if="t.count > 0" type="danger" size="small" effect="dark" round>{{ t.count }}</el-tag>
        <span v-else class="todo-zero">暂无</span>
      </div>
      <div class="todo-foot">点击进入对应页面处理</div>
    </div>
  </el-popover>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { Bell } from '@element-plus/icons-vue'
import { api } from '@/api'

const router = useRouter()
const todo = ref({ alerts: 0, assets: 0, quality: 0 })

const items = computed(() => [
  { key: 'alerts', name: '未处理告警', count: todo.value.alerts, icon: 'Warning', path: '/security/alert', soft: 'color-mix(in srgb, var(--tech-danger) 12%, transparent)', on: 'var(--tech-danger)' },
  { key: 'assets', name: '待审资产', count: todo.value.assets, icon: 'Box', path: '/asset/audit', soft: 'color-mix(in srgb, var(--tech-warn) 12%, transparent)', on: 'var(--tech-warn)' },
  { key: 'quality', name: '质量异常', count: todo.value.quality, icon: 'WarnTriangleFilled', path: '/data-gov/quality', soft: 'color-mix(in srgb, var(--tech-primary) 12%, transparent)', on: 'var(--tech-primary)' }
])
const total = computed(() => todo.value.alerts + todo.value.assets + todo.value.quality)

function go(path: string) { router.push(path) }

let timer: number
async function load() {
  try { todo.value = await api.authTodo() } catch { /* 静默 */ }
}
onMounted(() => { load(); timer = window.setInterval(load, 60000) })
onUnmounted(() => clearInterval(timer))
</script>

<style scoped>
.todo-btn {
  display: inline-flex; align-items: center; justify-content: center;
  width: 34px; height: 34px; padding: 0; border-radius: 9px; cursor: pointer;
  color: var(--tech-text);
  background: color-mix(in srgb, var(--tech-primary) 10%, transparent);
  border: 1px solid var(--tech-panel-border);
  transition: all 0.25s ease;
}
.todo-btn:hover { color: var(--tech-primary); background: color-mix(in srgb, var(--tech-primary) 18%, transparent); box-shadow: var(--tech-glow); }
.todo-body { margin: -4px -2px; }
.todo-title { font-size: 13px; color: var(--tech-text-muted); padding: 2px 4px 8px; }
.todo-item { display: flex; align-items: center; gap: 10px; padding: 8px 6px; border-radius: 8px; cursor: pointer; transition: background .2s ease; }
.todo-item:hover { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); }
.todo-ico { width: 28px; height: 28px; border-radius: 7px; display: inline-flex; align-items: center; justify-content: center; }
.todo-name { flex: 1; font-size: 14px; color: var(--tech-text); }
.todo-zero { font-size: 12px; color: var(--tech-text-muted); opacity: .6; }
.todo-foot { font-size: 12px; color: var(--tech-text-muted); opacity: .7; padding: 8px 4px 2px; text-align: center; }
</style>
