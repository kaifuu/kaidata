<template>
  <!-- resolveIcon 总能给出 svg-key（最差 generic）；仅当未命中映射且传入 emoji 时回退文本 -->
  <span v-if="showEmoji" class="step-icon-emoji" :style="{ fontSize: (size ? size - 2 : 14) + 'px' }">{{ emoji }}</span>
  <svg v-else class="step-svg" :width="size" :height="size" viewBox="0 0 24 24" fill="none"
       stroke="currentColor" stroke-width="1.8" stroke-linecap="round" stroke-linejoin="round">
    <path v-for="(d, i) in paths" :key="i" :d="d" />
  </svg>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { ICONS, isSvgKey, resolveIcon } from './dag-icons'

const props = withDefaults(defineProps<{ icon?: string; group?: string; kind?: string; size?: number }>(), {
  size: 16,
})
const resolved = computed(() => resolveIcon(props.icon, props.group, props.kind))
const paths = computed(() => ICONS[resolved.value] || ICONS.generic)
// kind/group 都没命中（generic）且原 icon 是 emoji 文本 → 保留 emoji 显示，兜底防漏
const showEmoji = computed(() => resolved.value === 'generic' && !!props.icon && !isSvgKey(props.icon))
const emoji = computed(() => (showEmoji.value ? (props.icon as string) : ''))
</script>

<style scoped>
.step-svg { display: block; flex-shrink: 0; }
.step-icon-emoji { display: inline-flex; align-items: center; justify-content: center; line-height: 1; }
</style>
