<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon class="logo-ico"><DataLine /></el-icon>
        <div class="logo-txt">
          <div class="t1">药厂数据中台</div>
          <div class="t2">DATA LAKE</div>
        </div>
      </div>
      <el-menu :default-active="route.path" router class="nav" unique-opened>
        <template v-for="m in menuTree">
          <el-sub-menu v-if="m.children && m.children.length" :key="'sub-' + m.id" :index="m.path || ('cat-' + m.id)">
            <template #title>
              <el-icon><component :is="m.icon || 'Menu'" /></el-icon>
              <span>{{ m.name }}</span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path">
              <el-icon><component :is="c.icon || 'Menu'" /></el-icon>
              <span>{{ c.name }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :key="m.id" :index="m.path">
            <el-icon><component :is="m.icon || 'Menu'" /></el-icon>
            <span>{{ m.name }}</span>
          </el-menu-item>
        </template>
      </el-menu>
      <div class="aside-foot">
        <div class="led" /><span>湖仓运行中</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">
          <el-icon class="crumb-ico"><Cpu /></el-icon>
          <span class="title">{{ route.meta.title }}</span>
        </div>
        <div class="header-right">
          <ThemeToggle />
          <span class="clock">{{ now }}</span>
          <el-dropdown @command="onCmd">
            <div class="user">
              <el-icon><UserFilled /></el-icon>
              <span>{{ user?.name || '未登录' }}</span>
              <el-tag size="small" effect="dark">{{ user?.role || '' }}</el-tag>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="logout"><el-icon><SwitchButton /></el-icon> 退出登录</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { auth } from '@/auth'
import { api, type MenuRow } from '@/api'
import ThemeToggle from '@/components/ThemeToggle.vue'

const route = useRoute()
const router = useRouter()
const user = computed(() => auth.user())
const menus = ref<MenuRow[]>(auth.menus())

const menuTree = computed(() => {
  const flat = menus.value
  const roots = flat.filter(m => !m.parent_id || m.parent_id === 0)
  return roots.map(r => ({
    ...r,
    children: flat.filter(c => c.parent_id === r.id)
  })).sort((a, b) => (a.sort || 0) - (b.sort || 0))
})

const now = ref('')
let timer: number
function tick() { now.value = new Date().toLocaleString('zh-CN', { hour12: false }) }
onMounted(async () => {
  tick()
  timer = window.setInterval(tick, 1000)
  try { menus.value = await api.authMenus() } catch { /* 未登录等 */ }
})
onUnmounted(() => clearInterval(timer))

function onCmd(c: string) {
  if (c === 'logout') {
    auth.clear()
    router.replace('/login')
  }
}
</script>

<style scoped>
/* ============ 布局骨架（浅色/暗色统一用语义变量） ============ */
.layout { height: 100%; position: relative; z-index: 1; }

/* 侧栏：浅色白底渐变；暗色深蓝玻璃 */
.aside {
  background: linear-gradient(180deg, var(--tech-bg-2), var(--tech-bg));
  border-right: 1px solid var(--tech-panel-border);
  display: flex; flex-direction: column;
  transition: background 0.3s ease, border-color 0.3s ease;
}
html.dark .aside {
  background: linear-gradient(180deg, #0a1430 0%, #060c1c 100%);
  border-right-color: var(--tech-panel-border);
  backdrop-filter: blur(6px);
}

/* Logo 区 */
.logo { display: flex; align-items: center; gap: 10px; padding: 16px 18px; border-bottom: 1px solid var(--tech-panel-border); }
.logo-ico { font-size: 28px; color: var(--tech-primary); }
.logo-txt .t1 { font-size: 16px; font-weight: 700; letter-spacing: 1px; color: var(--tech-text); }
.logo-txt .t2 { font-size: 10px; letter-spacing: 3px; color: var(--tech-primary); opacity: .85; }
html.dark .logo-ico { filter: drop-shadow(0 0 6px var(--tech-primary)); }

/* 菜单容器 */
.nav { flex: 1; border-right: none; background: transparent; overflow-y: auto; }
.aside :deep(.el-menu), .aside :deep(.el-sub-menu) { background: transparent; }
.aside :deep(.el-menu-item), .aside :deep(.el-sub-menu__title) { color: var(--tech-text-muted); transition: color .2s ease, background .2s ease; }
.aside :deep(.el-menu-item .el-icon), .aside :deep(.el-sub-menu__title .el-icon) { color: inherit; }
.aside :deep(.el-menu-item:hover), .aside :deep(.el-sub-menu__title:hover) { color: var(--tech-text); background: color-mix(in srgb, var(--tech-primary) 8%, transparent); }
.aside :deep(.el-menu-item.is-active) {
  color: var(--tech-primary);
  background: linear-gradient(90deg, color-mix(in srgb, var(--tech-primary) 16%, transparent), transparent);
  border-left: 2px solid var(--tech-primary);
}
html.dark .aside :deep(.el-menu-item.is-active) {
  color: var(--tech-primary);
  background: linear-gradient(90deg, color-mix(in srgb, var(--tech-primary) 22%, transparent), transparent);
  border-left: 2px solid var(--tech-primary);
  box-shadow: inset 0 0 18px rgba(0, 224, 255, .12);
}
html.dark .aside :deep(.el-menu-item.is-active .el-icon) { filter: drop-shadow(0 0 4px var(--tech-primary)); }

/* 侧栏底栏 */
.aside-foot { padding: 12px 18px; color: var(--tech-text-muted); font-size: 12px; display: flex; align-items: center; gap: 8px; border-top: 1px solid var(--tech-panel-border); }
.led { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-success); box-shadow: 0 0 8px var(--tech-success); animation: pulse 1.6s infinite; }
@keyframes pulse { 50% { opacity: .35; } }

/* 顶栏：浅色白底微阴影；暗色深蓝玻璃 */
.header {
  background: var(--tech-bg-2);
  border-bottom: 1px solid var(--tech-panel-border);
  display: flex; align-items: center; justify-content: space-between;
  transition: background 0.3s ease, border-color 0.3s ease;
}
html.dark .header { background: rgba(10, 20, 48, 0.55); backdrop-filter: blur(8px); }
.crumb { display: flex; align-items: center; gap: 10px; }
.crumb-ico { color: var(--tech-primary); }
html.dark .crumb-ico { filter: drop-shadow(0 0 4px var(--tech-primary)); }
.title { font-size: 16px; font-weight: 600; color: var(--tech-text); letter-spacing: 1px; }
.header-right { display: flex; align-items: center; gap: 20px; }
.clock { color: var(--tech-text-muted); font-size: 13px; font-variant-numeric: tabular-nums; }
.user { display: flex; align-items: center; gap: 8px; cursor: pointer; color: var(--tech-text); padding: 4px 10px; border-radius: 8px; transition: background .2s ease; }
.user:hover { background: color-mix(in srgb, var(--tech-primary) 10%, transparent); }
.user .el-icon { color: var(--tech-primary); }
.main { padding: 16px; overflow-y: auto; }
</style>
