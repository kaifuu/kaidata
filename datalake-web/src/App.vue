<template>
  <el-config-provider :locale="elLocale">
    <router-view />
  </el-config-provider>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import en from 'element-plus/es/locale/lang/en'
import { locale } from '@/locale'

// Element Plus 内置组件文案（分页/空状态/日期选择器等）随语言切换
const elLocale = computed(() => (locale.value === 'zh-CN' ? zhCn : en))
</script>
