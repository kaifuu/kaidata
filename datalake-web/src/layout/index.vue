<template>
  <el-container class="layout">
    <el-aside width="220px" class="aside">
      <div class="logo">
        <el-icon class="logo-ico"><DataLine /></el-icon>
        <div class="logo-txt">
          <div class="t1">{{ $t('app.name') }}</div>
          <div class="t2">KAIDATA</div>
        </div>
      </div>
      <el-menu :default-active="route.path" router class="nav" unique-opened>
        <template v-for="m in menuTree">
          <el-sub-menu v-if="m.children && m.children.length" :key="'sub-' + m.id" :index="m.path || ('cat-' + m.id)">
            <template #title>
              <el-icon><component :is="m.icon || 'Menu'" /></el-icon>
              <span>{{ groupLabel(m) }}</span>
            </template>
            <el-menu-item v-for="c in m.children" :key="c.id" :index="c.path">
              <el-icon><component :is="c.icon || 'Menu'" /></el-icon>
              <span>{{ leafLabel(c) }}</span>
            </el-menu-item>
          </el-sub-menu>
          <el-menu-item v-else :key="m.id" :index="m.path">
            <el-icon><component :is="m.icon || 'Menu'" /></el-icon>
            <span>{{ leafLabel(m) }}</span>
          </el-menu-item>
        </template>
      </el-menu>
      <div class="aside-foot">
        <div class="led" /><span>{{ $t('layout.lakehouseRunning') }}</span>
      </div>
    </el-aside>

    <el-container>
      <el-header class="header">
        <div class="crumb">
          <el-icon class="crumb-ico"><Cpu /></el-icon>
          <span class="title">{{ pageTitle }}</span>
        </div>
        <div class="header-right">
          <ThemeToggle />
          <LangToggle />
          <TodoBell />
          <span class="clock">{{ now }}</span>
          <el-dropdown @command="onCmd">
            <div class="user">
              <el-icon><UserFilled /></el-icon>
              <span>{{ user?.name || $t('layout.notLoggedIn') }}</span>
              <el-tag size="small" effect="dark">{{ user?.role || '' }}</el-tag>
            </div>
            <template #dropdown>
              <el-dropdown-menu>
                <el-dropdown-item command="profile"><el-icon><User /></el-icon> {{ $t('layout.profile') }}</el-dropdown-item>
                <el-dropdown-item command="password"><el-icon><Lock /></el-icon> {{ $t('layout.password') }}</el-dropdown-item>
                <el-dropdown-item command="logs"><el-icon><Document /></el-icon> {{ $t('layout.logs') }}</el-dropdown-item>
                <el-dropdown-item divided command="logout"><el-icon><SwitchButton /></el-icon> {{ $t('layout.logout') }}</el-dropdown-item>
              </el-dropdown-menu>
            </template>
          </el-dropdown>
        </div>
      </el-header>
      <el-main class="main"><router-view /></el-main>
      <PasswordDialog ref="pwdDlg" />
      <ProfileDialog ref="profileDlg" />
    </el-container>
  </el-container>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { auth } from '@/auth'
import { api, type MenuRow } from '@/api'
import { locale, menuKey } from '@/locale'
import ThemeToggle from '@/components/ThemeToggle.vue'
import LangToggle from '@/components/LangToggle.vue'
import TodoBell from '@/components/TodoBell.vue'
import PasswordDialog from '@/components/PasswordDialog.vue'
import ProfileDialog from '@/components/ProfileDialog.vue'

const { t, te } = useI18n()
const route = useRoute()
const router = useRouter()
const user = computed(() => auth.user())
const menus = ref<MenuRow[]>(auth.menus())
const pwdDlg = ref()
const profileDlg = ref()

const menuTree = computed(() => {
  const flat = menus.value
  const roots = flat.filter(m => !m.parent_id || m.parent_id === 0)
  return roots.map(r => ({
    ...r,
    children: flat.filter(c => c.parent_id === r.id)
  })).sort((a, b) => (a.sort || 0) - (b.sort || 0))
})

// 菜单文案：叶子按 menu.<path>、父级按 menuGroup.<子项 path 首段>；字典缺失时回退后端 name
function leafLabel(m: MenuRow) {
  const k = 'menu.' + menuKey(m.path)
  return te(k) ? t(k) : m.name
}
function groupLabel(m: any) {
  const prefix = menuKey(m.children?.[0]?.path).split('/')[0]
  const k = 'menuGroup.' + prefix
  return prefix && te(k) ? t(k) : m.name
}
// 面包屑标题：优先菜单字典，回退 route.meta.title
const pageTitle = computed(() => {
  const k = 'menu.' + menuKey(route.path)
  return te(k) ? t(k) : ((route.meta.title as string) || '')
})

const now = ref('')
let timer: number
function tick() { now.value = new Date().toLocaleString(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { hour12: false }) }
onMounted(async () => {
  tick()
  timer = window.setInterval(tick, 1000)
  try { menus.value = await api.authMenus() } catch { /* 未登录等 */ }
})
onUnmounted(() => clearInterval(timer))

function onCmd(c: string) {
  if (c === 'logout') { auth.clear(); router.replace('/login') }
  else if (c === 'profile') profileDlg.value?.open('profile')
  else if (c === 'logs') profileDlg.value?.open('logs')
  else if (c === 'password') pwdDlg.value?.open()
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
