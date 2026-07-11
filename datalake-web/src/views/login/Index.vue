<template>
  <div class="login">
    <!-- 粒子连线背景（对标阿里云） -->
    <ParticleBackground />
    <!-- 背景层：网格 + 扫描线 + 浮动光点 -->
    <div class="bg-grid" />
    <div class="bg-scan" />
    <div class="orbs"><span v-for="n in 6" :key="n" :style="orb(n)" /></div>

    <!-- 主题 / 语言切换 -->
    <div class="top-tools"><ThemeToggle /><LangToggle /></div>

    <!-- 左侧品牌区 -->
    <div class="brand">
      <div class="brand-logo">
        <svg viewBox="0 0 120 120" class="logo-svg">
          <defs>
            <linearGradient id="kg" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#00e0ff"/><stop offset="1" stop-color="#7c5cff"/></linearGradient>
            <linearGradient id="kg2" x1="0" y1="0" x2="1" y2="1"><stop offset="0" stop-color="#2f6bff"/><stop offset="1" stop-color="#00e0ff"/></linearGradient>
            <filter id="kgw" x="-50%" y="-50%" width="200%" height="200%"><feGaussianBlur stdDeviation="2.4"/></filter>
          </defs>
          <g stroke="url(#kg)" stroke-width="1" opacity="0.4" fill="none"><path d="M16 16 L40 26 M104 16 L82 26 M16 104 L40 94 M104 104 L82 94 M16 60 L40 60 M104 60 L82 60 M60 12 L60 36"/></g>
          <g fill="#00e0ff" opacity="0.8"><circle cx="16" cy="16" r="2.6"/><circle cx="104" cy="16" r="2.6"/><circle cx="16" cy="104" r="2.6"/><circle cx="104" cy="104" r="2.6"/><circle cx="16" cy="60" r="2.6"/><circle cx="104" cy="60" r="2.6"/><circle cx="60" cy="12" r="2.2"/><circle cx="60" cy="36" r="2.2"/></g>
          <g fill="none" stroke="url(#kg)" stroke-width="9.5" stroke-linecap="round" opacity="0.55" filter="url(#kgw)"><path d="M40 26 L40 94 M40 60 L82 26 M40 60 L82 94"/></g>
          <g fill="none" stroke="url(#kg2)" stroke-width="6.5" stroke-linecap="round"><path d="M40 26 L40 94 M40 60 L82 26 M40 60 L82 94"/></g>
          <circle cx="40" cy="26" r="6" fill="#00e0ff"/><circle cx="40" cy="94" r="6" fill="#00e0ff"/>
          <circle cx="82" cy="26" r="6" fill="#7c5cff"/><circle cx="82" cy="94" r="6" fill="#7c5cff"/>
          <circle cx="40" cy="60" r="8" fill="#fff"/><circle cx="40" cy="60" r="3.5" fill="#00e0ff"/>
        </svg>
      </div>
      <div class="brand-name">kaidata</div>
      <h1>{{ $t('app.name') }}</h1>
      <p>{{ $t('app.slogan') }}</p>
      <ul class="brand-points">
        <li><el-icon><Connection /></el-icon> {{ $t('app.feature1') }}</li>
        <li><el-icon><Cpu /></el-icon> {{ $t('app.feature2') }}</li>
        <li><el-icon><DataAnalysis /></el-icon> {{ $t('app.feature3') }}</li>
      </ul>
    </div>

    <!-- 登录卡片 -->
    <div class="card">
      <div class="card-head">
        <span class="dot" />
        <span>{{ $t('login.title') }} / {{ $t('login.titleEn') }}</span>
      </div>

      <!-- 登录方式切换 -->
      <div class="seg">
        <button :class="['seg-btn', { on: tab === 'account' }]" @click="tab = 'account'">{{ $t('login.tabAccount') }}</button>
        <button :class="['seg-btn', { on: tab === 'qrcode' }]" @click="tab = 'qrcode'">{{ $t('login.tabQrcode') }}</button>
        <span class="seg-ink" :class="{ right: tab === 'qrcode' }" />
      </div>

      <!-- 账号登录 -->
      <el-form v-show="tab === 'account'" ref="formRef" :model="form" :rules="rules" size="large" @keyup.enter="onLogin">
        <el-form-item prop="username">
          <el-input v-model="form.username" :placeholder="$t('login.usernamePlaceholder')" :prefix-icon="User" clearable />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" show-password :placeholder="$t('login.passwordPlaceholder')" :prefix-icon="Lock" clearable />
        </el-form-item>
        <el-form-item prop="captchaCode">
          <div class="captcha-row">
            <el-input v-model="form.captchaCode" :placeholder="$t('login.captchaPlaceholder')" :prefix-icon="Picture" maxlength="4" clearable />
            <img class="captcha-img" :src="captchaImg" :alt="$t('login.captcha')" :title="$t('login.refreshCaptcha')" @click="refreshCaptcha" />
          </div>
        </el-form-item>
        <el-button class="enter" type="primary" :loading="loading" @click="onLogin">
          {{ $t('login.enter') }} <el-icon class="el-icon--right"><Right /></el-icon>
        </el-button>
        <div class="hint">{{ $t('login.demoPrefix') }} <b>admin</b> / <b>admin123</b></div>
      </el-form>

      <!-- 扫码登录 -->
      <div v-show="tab === 'qrcode'" class="qr-wrap">
        <div class="qr-box">
          <img v-if="qrSrc" :src="qrSrc" alt="登录二维码" class="qr-img" />
          <div class="qr-mask" :class="{ show: qrExpiry <= 0 }">
            <span>{{ $t('login.qrExpired') }}</span>
            <el-button size="small" type="primary" @click="genQR">{{ $t('login.qrRefresh') }}</el-button>
          </div>
          <span class="qr-corner tl" /><span class="qr-corner tr" /><span class="qr-corner bl" /><span class="qr-corner br" />
        </div>
        <div class="qr-tip"><el-icon><Iphone /></el-icon> {{ $t('login.qrTipPre') }} <b>{{ $t('login.qrAppName') }}</b> {{ $t('login.qrTipPost') }}</div>
        <div class="qr-sub">{{ $t('login.qrSub', { sec: qrExpiry }) }}</div>
      </div>
    </div>

    <div class="footer">{{ $t('login.footer') }}{{ $t('app.name') }} {{ $t('app.nameEn') }}</div>
  </div>
</template>

<script setup lang="ts">
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance } from 'element-plus'
import { User, Lock, Right, Iphone, Picture } from '@element-plus/icons-vue'
import QRCode from 'qrcode'
import { api, errMsg } from '@/api'
import { auth } from '@/auth'
import ThemeToggle from '@/components/ThemeToggle.vue'
import LangToggle from '@/components/LangToggle.vue'
import ParticleBackground from '@/components/ParticleBackground.vue'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const formRef = ref<FormInstance>()
const loading = ref(false)
const form = reactive({ username: 'admin', password: 'admin123', captchaCode: '' })
const rules = computed(() => ({
  username: [{ required: true, message: t('login.usernameRequired'), trigger: 'blur' }],
  password: [{ required: true, message: t('login.passwordRequired'), trigger: 'blur' }],
  captchaCode: [{ required: true, message: t('login.captchaRequired'), trigger: 'blur' }]
}))

// 验证码：进入页 / 点击图 / 登录失败 均拉取；后端一次性消费，失败必刷新
const captchaId = ref('')
const captchaImg = ref('')
async function refreshCaptcha() {
  const d = await api.captcha()
  captchaId.value = d.captchaId
  captchaImg.value = d.img
  form.captchaCode = ''
}

const tab = ref<'account' | 'qrcode'>('account')

async function onLogin() {
  await formRef.value?.validate(async (ok) => {
    if (!ok) return
    loading.value = true
    try {
      const data = await api.login(form.username, form.password, captchaId.value, form.captchaCode)
      auth.set(data.token, data.user, data.menus || [])
      ElMessage.success(t('login.success'))
      router.replace((route.query.redirect as string) || '/')
    } catch (e) {
      ElMessage.error(errMsg(e, t('login.failed')))
      refreshCaptcha()   // 后端已一次性消费旧码，失败必刷新
    } finally {
      loading.value = false
    }
  })
}

// 二维码：生成可扫码的登录票据 URL，每 60s 自动刷新
const qrSrc = ref('')
const qrExpiry = ref(60)
async function genQR() {
  const ticket = Math.random().toString(36).slice(2, 10) + Date.now().toString(36)
  const payload = `${location.origin}/#/m/login?t=${ticket}`
  qrSrc.value = await QRCode.toDataURL(payload, { margin: 1, width: 224, color: { dark: '#101828', light: '#ffffff' } })
  qrExpiry.value = 60
}
let qrTimer: number
let countdown: number

// 浮动光点随机位置（按索引确定，避免渲染抖动）
function orb(n: number) {
  const seed = [12, 78, 30, 62, 88, 45][n - 1]
  const size = 120 + ((n * 37) % 160)
  return {
    left: `${seed}%`,
    top: `${(n * 53) % 90}%`,
    width: `${size}px`,
    height: `${size}px`,
    animationDuration: `${14 + n * 3}s`,
    animationDelay: `${-n * 2}s`
  }
}

onMounted(() => {
  refreshCaptcha()
  genQR()
  qrTimer = window.setInterval(genQR, 60000)
  countdown = window.setInterval(() => { if (qrExpiry.value > 0) qrExpiry.value-- }, 1000)
})
onUnmounted(() => { clearInterval(qrTimer); clearInterval(countdown) })
</script>

<style scoped>
.login {
  position: relative;
  width: 100%;
  height: 100%;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 80px;
  overflow: hidden;
}
.bg-grid {
  position: absolute; inset: 0; z-index: 0;
  background-image:
    linear-gradient(color-mix(in srgb, var(--tech-primary) 9%, transparent) 1px, transparent 1px),
    linear-gradient(90deg, color-mix(in srgb, var(--tech-primary) 9%, transparent) 1px, transparent 1px);
  background-size: 44px 44px;
  mask-image: radial-gradient(ellipse at center, #000 35%, transparent 80%);
}
.bg-scan {
  position: absolute; inset: 0; z-index: 0; pointer-events: none;
  background: linear-gradient(180deg, transparent, color-mix(in srgb, var(--tech-primary) 7%, transparent) 50%, transparent);
  animation: scan 7s linear infinite;
}
@keyframes scan { 0% { transform: translateY(-100%); } 100% { transform: translateY(100%); } }
.orbs span {
  position: absolute; border-radius: 50%; filter: blur(40px); opacity: .35;
  background: radial-gradient(circle, var(--tech-primary), transparent 70%);
  animation: float 16s ease-in-out infinite;
}
@keyframes float { 0%, 100% { transform: translate(0, 0); } 50% { transform: translate(40px, -30px); } }

.top-tools { position: absolute; top: 20px; right: 24px; z-index: 5; display: flex; gap: 8px; }

/* 品牌 */
.brand { position: relative; z-index: 2; color: var(--tech-text); max-width: 420px; }
.brand-logo {
  width: 96px; height: 96px; margin-bottom: 14px;
  filter: drop-shadow(0 6px 22px rgba(0, 224, 255, 0.4));
  animation: logoFloat 5s ease-in-out infinite;
}
.brand-logo .logo-svg { width: 100%; height: 100%; display: block; }
@keyframes logoFloat { 0%, 100% { transform: translateY(0); } 50% { transform: translateY(-5px); } }
.brand-name { font-size: 16px; letter-spacing: 8px; color: var(--tech-primary); font-weight: 700; margin-bottom: 6px; text-shadow: var(--tech-glow); }
.brand h1 { font-size: 34px; margin: 0 0 8px; letter-spacing: 2px; text-shadow: var(--tech-glow); }
.brand > p { color: var(--tech-text-muted); margin: 0 0 26px; letter-spacing: 1px; }
.brand-points { list-style: none; padding: 0; margin: 0; }
.brand-points li {
  display: flex; align-items: center; gap: 10px; color: var(--tech-text-muted);
  padding: 10px 0; border-bottom: 1px dashed var(--tech-panel-border);
}
.brand-points .el-icon { color: var(--tech-primary); }

/* 登录卡片 */
.card {
  position: relative; z-index: 2;
  width: 360px; padding: 26px 26px 22px;
  background: var(--tech-panel);
  border: 1px solid var(--tech-panel-border);
  border-radius: 14px;
  backdrop-filter: blur(18px) saturate(1.2);
  box-shadow: var(--tech-shadow), 0 0 34px color-mix(in srgb, var(--tech-primary) 20%, transparent), 0 8px 40px rgba(0, 0, 0, .25);
}
.card::before {
  content: ""; position: absolute; inset: -1px; border-radius: 14px; padding: 1px;
  background: linear-gradient(135deg, var(--tech-primary), transparent 40%, var(--tech-accent));
  -webkit-mask: linear-gradient(#000 0 0) content-box, linear-gradient(#000 0 0);
  -webkit-mask-composite: xor; mask-composite: exclude; pointer-events: none; opacity: .7;
}
.card-head {
  display: flex; align-items: center; gap: 8px; color: var(--tech-primary);
  font-size: 13px; letter-spacing: 2px; margin-bottom: 18px;
}
.card-head .dot { width: 8px; height: 8px; border-radius: 50%; background: var(--tech-primary); box-shadow: 0 0 10px var(--tech-primary); animation: pulse 1.6s infinite; }
@keyframes pulse { 50% { opacity: .3; } }

/* 分段切换 */
.seg { position: relative; display: flex; margin-bottom: 22px; background: color-mix(in srgb, var(--tech-primary) 7%, transparent); border-radius: 9px; padding: 3px; }
.seg-btn { flex: 1; position: relative; z-index: 2; padding: 8px 0; border: none; background: transparent; cursor: pointer; font-size: 14px; color: var(--tech-text-muted); transition: color .25s ease; }
.seg-btn.on { color: var(--tech-primary); font-weight: 600; }
.seg-ink { position: absolute; z-index: 1; top: 3px; left: 3px; width: calc(50% - 3px); height: calc(100% - 6px); border-radius: 7px; background: var(--tech-bg-2); box-shadow: var(--tech-shadow); transition: transform .28s cubic-bezier(.4,0,.2,1); }
.seg-ink.right { transform: translateX(100%); }

/* 验证码行 */
.captcha-row { display: flex; gap: 10px; align-items: center; width: 100%; }
.captcha-row .el-input { flex: 1; }
.captcha-img {
  width: 112px; height: 40px; flex-shrink: 0; cursor: pointer; border-radius: 8px;
  border: 1px solid var(--tech-panel-border); box-shadow: var(--tech-glow);
  object-fit: cover; transition: filter .2s ease, transform .2s ease;
}
.captcha-img:hover { filter: brightness(1.18); transform: translateY(-1px); }

.enter { width: 100%; letter-spacing: 4px; }
.hint { margin-top: 14px; text-align: center; color: var(--tech-text-muted); font-size: 12px; }
.hint b { color: var(--tech-primary); }

/* 扫码区 */
.qr-wrap { display: flex; flex-direction: column; align-items: center; padding: 4px 0 6px; }
.qr-box { position: relative; width: 224px; height: 224px; padding: 10px; background: #fff; border-radius: 12px; box-shadow: var(--tech-shadow); }
.qr-img { width: 100%; height: 100%; display: block; }
.qr-mask { position: absolute; inset: 0; display: none; flex-direction: column; align-items: center; justify-content: center; gap: 12px; background: rgba(255,255,255,.92); border-radius: 12px; color: #101828; font-size: 13px; }
.qr-mask.show { display: flex; }
.qr-corner { position: absolute; width: 16px; height: 16px; border: 2px solid var(--tech-primary); }
.qr-corner.tl { top: 4px; left: 4px; border-right: none; border-bottom: none; }
.qr-corner.tr { top: 4px; right: 4px; border-left: none; border-bottom: none; }
.qr-corner.bl { bottom: 4px; left: 4px; border-right: none; border-top: none; }
.qr-corner.br { bottom: 4px; right: 4px; border-left: none; border-top: none; }
.qr-tip { margin-top: 16px; display: flex; align-items: center; gap: 6px; color: var(--tech-text); font-size: 14px; }
.qr-tip .el-icon { color: var(--tech-primary); }
.qr-tip b { color: var(--tech-primary); }
.qr-sub { margin-top: 4px; color: var(--tech-text-muted); font-size: 12px; }

.footer { position: absolute; bottom: 18px; color: var(--tech-text-muted); font-size: 12px; z-index: 2; opacity: .7; }

@media (max-width: 880px) { .brand { display: none; } }
</style>
