<template>
  <el-tooltip :content="isDark ? '切换到浅色模式' : '切换到暗色模式'" placement="bottom">
    <button class="theme-toggle" :aria-label="isDark ? '切换浅色' : '切换暗色'" @click="theme.toggle()">
      <el-icon :size="17"><Sunny v-if="isDark" /><Moon v-else /></el-icon>
    </button>
  </el-tooltip>
</template>

<script setup lang="ts">
import { Sunny, Moon } from '@element-plus/icons-vue'
import { theme } from '@/theme'

const isDark = theme.isDark
</script>

<style scoped>
.theme-toggle {
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
.theme-toggle:hover {
  color: var(--tech-primary);
  background: color-mix(in srgb, var(--tech-primary) 18%, transparent);
  box-shadow: var(--tech-glow);
}
</style>
