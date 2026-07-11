<template>
  <el-dropdown trigger="click" @command="onCmd">
    <button class="lang-toggle" aria-label="Language">
      <span class="lang-txt">{{ locale === 'zh-CN' ? '中' : 'En' }}</span>
    </button>
    <template #dropdown>
      <el-dropdown-menu>
        <el-dropdown-item command="zh-CN" :disabled="locale === 'zh-CN'">中文</el-dropdown-item>
        <el-dropdown-item command="en-US" :disabled="locale === 'en-US'">English</el-dropdown-item>
      </el-dropdown-menu>
    </template>
  </el-dropdown>
</template>

<script setup lang="ts">
import { locale, setLocale, type LocaleLang } from '@/locale'

function onCmd(c: string) {
  setLocale(c as LocaleLang)
}
</script>

<style scoped>
/* 尺寸/观感与 ThemeToggle 完全一致，便于在顶栏并列 */
.lang-toggle {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  padding: 0;
  border-radius: 9px;
  cursor: pointer;
  color: var(--tech-text);
  background: color-mix(in srgb, var(--tech-primary) 10%, transparent);
  border: 1px solid var(--tech-panel-border);
  transition: all 0.25s ease;
}
.lang-toggle:hover {
  color: var(--tech-primary);
  background: color-mix(in srgb, var(--tech-primary) 18%, transparent);
  box-shadow: var(--tech-glow);
}
.lang-txt { font-size: 13px; font-weight: 700; letter-spacing: 0; }
</style>
