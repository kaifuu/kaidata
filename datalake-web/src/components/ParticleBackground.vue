<template>
  <canvas ref="cv" class="particle-bg" />
</template>

<script setup lang="ts">
/**
 * Canvas 粒子连线背景（对标阿里云登录页）
 * - 零依赖，自写 requestAnimationFrame 主循环
 * - DPR/resize 适配，粒子数随面积有上限，prefers-reduced-motion 降级静态
 * - 主题色读 --tech-primary，监听 <html>.dark 变化时重读
 * - pointer-events:none，鼠标交互监听 window
 */
import { onMounted, onUnmounted, ref } from 'vue'

const cv = ref<HTMLCanvasElement | null>(null)
let ctx: CanvasRenderingContext2D | null = null
let raf = 0
let ro: ResizeObserver | null = null
let mo: MutationObserver | null = null
let w = 0, h = 0, dpr = 1
let color = '#00e0ff'
const mouse = { x: -9999, y: -9999 }

interface P { x: number; y: number; vx: number; vy: number }
let particles: P[] = []

const reduced = !!window.matchMedia && window.matchMedia('(prefers-reduced-motion: reduce)').matches

function readColor() {
  const v = getComputedStyle(document.documentElement).getPropertyValue('--tech-primary').trim()
  if (v) color = v
}

function rgb(hex: string): [number, number, number] {
  let s = hex.replace('#', '').trim()
  if (s.length === 3) s = s.split('').map(c => c + c).join('')
  const n = parseInt(s, 16)
  return [(n >> 16) & 255, (n >> 8) & 255, n & 255]
}

function resize() {
  if (!cv.value || !ctx) return
  dpr = Math.min(window.devicePixelRatio || 1, 2)
  w = window.innerWidth
  h = window.innerHeight
  cv.value.width = w * dpr
  cv.value.height = h * dpr
  cv.value.style.width = w + 'px'
  cv.value.style.height = h + 'px'
  ctx.setTransform(dpr, 0, 0, dpr, 0, 0)
}

function init() {
  if (!cv.value) return
  ctx = cv.value.getContext('2d')
  readColor()
  resize()
  const n = Math.min(90, Math.max(28, Math.floor((w * h) / 14000)))
  particles = Array.from({ length: n }, () => ({
    x: Math.random() * w,
    y: Math.random() * h,
    vx: (Math.random() - 0.5) * 0.6,
    vy: (Math.random() - 0.5) * 0.6,
  }))
  if (reduced) { draw(); return }
  cancelAnimationFrame(raf)
  raf = requestAnimationFrame(loop)
}

function step() {
  for (const p of particles) {
    p.x += p.vx; p.y += p.vy
    if (p.x < 0 || p.x > w) p.vx *= -1
    if (p.y < 0 || p.y > h) p.vy *= -1
    const dx = p.x - mouse.x, dy = p.y - mouse.y
    const d = Math.sqrt(dx * dx + dy * dy)
    if (d < 120 && d > 0) {            // 鼠标附近粒子轻推
      const f = (1 - d / 120) * 0.8
      p.x += (dx / d) * f; p.y += (dy / d) * f
    }
  }
}

function draw() {
  if (!ctx) return
  const [r, g, b] = rgb(color)
  ctx.clearRect(0, 0, w, h)
  // 邻近连线
  for (let i = 0; i < particles.length; i++) {
    for (let j = i + 1; j < particles.length; j++) {
      const a = particles[i], c = particles[j]
      const dist = Math.hypot(a.x - c.x, a.y - c.y)
      if (dist < 130) {
        ctx.strokeStyle = `rgba(${r},${g},${b},${(1 - dist / 130) * 0.5})`
        ctx.lineWidth = 1
        ctx.beginPath(); ctx.moveTo(a.x, a.y); ctx.lineTo(c.x, c.y); ctx.stroke()
      }
    }
  }
  // 粒子点
  ctx.fillStyle = `rgba(${r},${g},${b},0.85)`
  for (const p of particles) {
    ctx.beginPath(); ctx.arc(p.x, p.y, 1.8, 0, Math.PI * 2); ctx.fill()
  }
}

function loop() { step(); draw(); raf = requestAnimationFrame(loop) }

function onMove(e: MouseEvent) { mouse.x = e.clientX; mouse.y = e.clientY }
function onLeave() { mouse.x = -9999; mouse.y = -9999 }

onMounted(() => {
  init()
  ro = new ResizeObserver(() => resize())
  if (cv.value) ro.observe(cv.value)
  mo = new MutationObserver(() => readColor())   // 切主题(html.dark)时重读色
  mo.observe(document.documentElement, { attributes: true, attributeFilter: ['class'] })
  window.addEventListener('mousemove', onMove)
  window.addEventListener('mouseout', onLeave)
})

onUnmounted(() => {
  cancelAnimationFrame(raf)
  ro?.disconnect()
  mo?.disconnect()
  window.removeEventListener('mousemove', onMove)
  window.removeEventListener('mouseout', onLeave)
})
</script>

<style scoped>
.particle-bg {
  position: fixed; inset: 0; z-index: 1;
  pointer-events: none;
}
</style>
