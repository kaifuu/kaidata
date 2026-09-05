<template>
  <div class="dl-card cfg-page">
    <!-- 页头 -->
    <div class="cfg-head">
      <div class="head-ic"><el-icon><Brush /></el-icon></div>
      <div class="head-txt">
        <div class="head-title">配置管理 <span class="role-tag">系统管理员</span></div>
        <div class="head-sub">登录页与首页的系统基础信息 · 保存后即时生效（浏览器标签 / 登录页 / 首页侧栏随改随变）</div>
      </div>
      <div class="head-side">
        <span v-if="updatedTip" class="muted">{{ updatedTip }}</span>
        <el-button @click="load" :loading="loading">重置</el-button>
        <el-button type="primary" :loading="saving" @click="save"><el-icon><Check /></el-icon>&nbsp;保存并生效</el-button>
      </div>
    </div>

    <el-row :gutter="16" class="cfg-body">
      <!-- 左：配置表单 -->
      <el-col :span="13">
        <!-- 基础信息 -->
        <div class="grp">
          <div class="grp-head">
            <el-icon class="grp-ic"><InfoFilled /></el-icon><span class="grp-name">基础信息</span>
            <span class="grp-hint">留空使用内置默认，不做覆盖</span>
          </div>
          <el-form label-width="84px" size="small" class="cfg-form">
            <el-form-item label="系统名称">
              <el-input v-model="form['sys.name']" placeholder="如 湖仓大数据平台（默认「数据中台」）" clearable />
            </el-form-item>
            <el-form-item label="英文名称">
              <el-input v-model="form['sys.name_en']" placeholder="如 KAIDATA（默认 kaidata）" clearable />
            </el-form-item>
            <el-form-item label="登录页标语">
              <el-input v-model="form['sys.slogan']" placeholder="如 一站式湖仓数据中台" clearable />
            </el-form-item>
          </el-form>
        </div>

        <!-- 品牌图片 -->
        <div class="grp">
          <div class="grp-head">
            <el-icon class="grp-ic"><Picture /></el-icon><span class="grp-name">品牌图片</span>
            <span class="grp-hint">点方框上传或直接粘贴图片 URL</span>
          </div>
          <el-form label-width="84px" size="small" class="cfg-form">
            <el-form-item label="LOGO">
              <div class="up-row">
                <div class="up-box" :class="{ has: !!form['sys.logo'] }" title="点击上传" @click="pickFile('sys.logo')">
                  <img v-if="form['sys.logo']" :src="form['sys.logo']" alt="LOGO" />
                  <div v-else class="up-empty"><el-icon><Plus /></el-icon><span>上传</span></div>
                  <div v-if="form['sys.logo']" class="up-mask"><el-icon><RefreshRight /></el-icon><span>更换</span></div>
                </div>
                <div class="up-side">
                  <el-input v-model="form['sys.logo']" placeholder="图片 URL（≤300KB，存 base64）" clearable />
                  <div class="up-meta">
                    <span class="muted">登录页品牌图 · 首页侧栏图标</span>
                    <el-button v-if="form['sys.logo']" link type="danger" size="small" @click="form['sys.logo'] = ''">清除</el-button>
                  </div>
                </div>
              </div>
            </el-form-item>
            <el-form-item label="标签图标">
              <div class="up-row">
                <div class="up-box" :class="{ has: !!form['sys.icon'] }" title="点击上传" @click="pickFile('sys.icon')">
                  <img v-if="form['sys.icon']" :src="form['sys.icon']" alt="ICON" />
                  <div v-else class="up-empty"><el-icon><Plus /></el-icon><span>上传</span></div>
                  <div v-if="form['sys.icon']" class="up-mask"><el-icon><RefreshRight /></el-icon><span>更换</span></div>
                </div>
                <div class="up-side">
                  <el-input v-model="form['sys.icon']" placeholder="favicon URL（≤64KB，建议正方形）" clearable />
                  <div class="up-meta">
                    <span class="muted">浏览器标签图标 + 页面标题</span>
                    <el-button v-if="form['sys.icon']" link type="danger" size="small" @click="form['sys.icon'] = ''">清除</el-button>
                  </div>
                </div>
              </div>
            </el-form-item>
          </el-form>
        </div>

        <!-- 页脚信息 -->
        <div class="grp">
          <div class="grp-head">
            <el-icon class="grp-ic"><Document /></el-icon><span class="grp-name">页脚信息</span>
            <span class="grp-hint">展示在登录页底部</span>
          </div>
          <el-form label-width="84px" size="small" class="cfg-form">
            <el-form-item label="ICP 备案号">
              <el-input v-model="form['sys.icp']" placeholder="如 京ICP备2026000000号（点击跳转工信部备案系统）" clearable />
            </el-form-item>
            <el-form-item label="版权落款">
              <el-input v-model="form['sys.copyright']" placeholder="如 © 2026 kaidata 保留所有权利" clearable />
            </el-form-item>
          </el-form>
        </div>
      </el-col>

      <!-- 右：应用效果实时预览（模拟界面） -->
      <el-col :span="11">
        <div class="pv-panel">
          <div class="pv-head"><el-icon><View /></el-icon><span>应用效果预览</span><span class="pv-hint">随左侧输入实时变化</span></div>

          <!-- ① 浏览器标签 -->
          <div class="mock-browser">
            <div class="mb-bar">
              <span class="mb-dot r" /><span class="mb-dot y" /><span class="mb-dot g" />
              <div class="mb-tab">
                <img v-if="form['sys.icon']" :src="form['sys.icon']" class="mb-fav" alt="" />
                <el-icon v-else class="mb-fav-ic"><Star /></el-icon>
                <span class="mb-tab-name">{{ form['sys.name'] || '数据中台' }}</span>
                <el-icon class="mb-x"><Close /></el-icon>
              </div>
            </div>
            <div class="mb-body"><span class="mb-line w60" /><span class="mb-line w90" /><span class="mb-line w75" /></div>
          </div>

          <!-- ② 首页侧栏 -->
          <div class="mock-app">
            <div class="ma-side">
              <div class="ma-logo">
                <img v-if="form['sys.logo']" :src="form['sys.logo']" alt="" />
                <el-icon v-else><DataLine /></el-icon>
              </div>
              <div class="ma-txt">
                <div class="ma-t1">{{ form['sys.name'] || '数据中台' }}</div>
                <div class="ma-t2">{{ (form['sys.name_en'] || 'KAIDATA').toUpperCase() }}</div>
              </div>
              <div class="ma-menu">
                <i class="on" /><i /><i /><i /><i /><i />
              </div>
            </div>
            <div class="ma-main">
              <div class="ma-cards"><div class="ma-card" v-for="n in 3" :key="n"><span class="ma-kpi" /><span class="ma-line w80" /></div></div>
              <div class="ma-chart" />
            </div>
          </div>

          <!-- ③ 登录页 -->
          <div class="mock-login">
            <div class="ml-brand">
              <img v-if="form['sys.logo']" :src="form['sys.logo']" class="ml-logo" alt="" />
              <div v-else class="ml-logo ml-logo-def" />
              <div class="ml-name">{{ form['sys.name'] || '数据中台' }}</div>
              <div class="ml-slogan">{{ form['sys.slogan'] || '现代湖仓 · Kafka → Flink → StarRocks → Spark' }}</div>
            </div>
            <div class="ml-card"><span class="ml-field" /><span class="ml-field" /><span class="ml-btn" /></div>
          </div>

          <!-- ④ 登录页页脚 -->
          <div class="mock-footer">
            Powered by {{ form['sys.name'] || '数据中台' }} {{ (form['sys.name_en'] || 'KAIDATA').toUpperCase() }}
            <template v-if="form['sys.copyright']"> · {{ form['sys.copyright'] }}</template>
            <template v-if="form['sys.icp']"> · <span class="mf-icp">{{ form['sys.icp'] }}</span></template>
          </div>
        </div>
      </el-col>
    </el-row>

    <input ref="fileInput" type="file" accept="image/*" style="display:none" @change="onFile" />
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { Brush, Check, InfoFilled, Picture, Document, View, Plus, RefreshRight, Close, Star, DataLine } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
import { loadBrand } from '@/brand'

// 配置键（与后端种子 cfg 1-7 对应；空值=回退内置默认）
const KEYS = ['sys.name', 'sys.name_en', 'sys.slogan', 'sys.logo', 'sys.icon', 'sys.icp', 'sys.copyright']
const form = reactive<Record<string, string>>({})
const loading = ref(false)
const saving = ref(false)
const updatedTip = ref('')

async function load() {
  loading.value = true
  try {
    const r: any = await api.sysConfig()
    const vals = r?.values || {}
    KEYS.forEach(k => { form[k] = vals[k] || '' })
    const rows: any[] = r?.rows || []
    const withTime = rows.filter(x => x.update_time)
    updatedTip.value = withTime.length ? `最近更新 ${withTime.map(x => x.update_time).sort().pop().replace('T', ' ').slice(0, 16)}` : ''
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}

async function save() {
  saving.value = true
  try {
    const body: Record<string, string> = {}
    KEYS.forEach(k => { body[k] = (form[k] || '').trim() })
    await api.sysSaveConfig(body)
    await loadBrand()          // 立即生效：标签标题/favicon/登录页/侧栏
    await load()
    ElMessage.success('已保存并生效')
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { saving.value = false }
}

// ----- 图片：点击方框选文件 → base64 直存配置（URL 粘贴亦可） -----
const fileInput = ref<HTMLInputElement>()
let pickKey = ''
function pickFile(key: string) { pickKey = key; fileInput.value && (fileInput.value.value = ''); fileInput.value?.click() }
function onFile(e: Event) {
  const el = e.target as HTMLInputElement
  const f = el.files?.[0]
  if (!f) return
  const cap = pickKey === 'sys.icon' ? 64 * 1024 : 300 * 1024   // favicon 要小；LOGO ≤300KB
  if (f.size > cap) return ElMessage.error(`图片 ${(f.size / 1024).toFixed(0)}KB 超限，请 ≤ ${cap / 1024}KB`)
  if (!f.type.startsWith('image/')) return ElMessage.error('请选择图片文件')
  const reader = new FileReader()
  reader.onload = () => { form[pickKey] = String(reader.result) }
  reader.readAsDataURL(f)
}

onMounted(load)
</script>

<style scoped>
.muted { color: var(--tech-text-muted); font-size: 12px; }

/* ===== 页头 ===== */
.cfg-head {
  display: flex; align-items: center; gap: 12px; padding-bottom: 14px; margin-bottom: 14px;
  border-bottom: 1px solid var(--tech-panel-border);
}
.head-ic {
  flex-shrink: 0; width: 42px; height: 42px; border-radius: 10px;
  display: flex; align-items: center; justify-content: center; font-size: 20px;
  color: var(--tech-primary);
  background: color-mix(in srgb, var(--tech-primary) 12%, transparent);
  box-shadow: inset 0 0 0 1px color-mix(in srgb, var(--tech-primary) 26%, transparent), var(--tech-glow);
}
.head-title { font-size: 16px; font-weight: 700; color: var(--tech-text); display: flex; align-items: center; gap: 8px; }
.head-sub { font-size: 12px; color: var(--tech-text-muted); margin-top: 3px; }
.head-side { margin-left: auto; display: flex; align-items: center; gap: 10px; }
.role-tag { font-size: 11px; font-weight: 400; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 1px 8px; border-radius: 4px; }

/* ===== 左侧分组卡片 ===== */
.grp {
  border: 1px solid var(--tech-panel-border); border-radius: 10px;
  padding: 12px 14px 4px; margin-bottom: 14px;
  background: color-mix(in srgb, var(--tech-panel) 55%, transparent);
  transition: border-color .2s ease;
}
.grp:hover { border-color: color-mix(in srgb, var(--tech-primary) 40%, var(--tech-panel-border)); }
.grp-head { display: flex; align-items: center; gap: 7px; margin-bottom: 10px; }
.grp-ic { font-size: 15px; color: var(--tech-primary); }
.grp-name { font-size: 13px; font-weight: 600; color: var(--tech-text); }
.grp-hint { font-size: 11px; color: var(--tech-text-muted); margin-left: auto; }
.cfg-form :deep(.el-form-item) { margin-bottom: 14px; }
.cfg-form :deep(.el-form-item__label) { font-size: 12px; color: var(--tech-text-muted); }

/* ===== 图片上传行 ===== */
.up-row { display: flex; gap: 12px; align-items: flex-start; width: 100%; }
.up-box {
  position: relative; flex-shrink: 0; width: 64px; height: 64px; border-radius: 10px; cursor: pointer; overflow: hidden;
  border: 1px dashed var(--tech-panel-border);
  display: flex; align-items: center; justify-content: center;
  transition: border-color .15s, box-shadow .15s, transform .15s;
}
.up-box:hover { border-color: var(--tech-primary); box-shadow: var(--tech-glow); transform: translateY(-1px); }
.up-box.has { border-style: solid; }
.up-box img { width: 100%; height: 100%; object-fit: contain; }
.up-empty { display: flex; flex-direction: column; align-items: center; gap: 2px; color: var(--tech-text-muted); font-size: 11px; }
.up-empty .el-icon { font-size: 17px; }
.up-mask {
  position: absolute; inset: 0; display: flex; flex-direction: column; align-items: center; justify-content: center; gap: 2px;
  background: rgba(8, 14, 26, .62); color: #fff; font-size: 11px;
  opacity: 0; transition: opacity .15s;
}
.up-box:hover .up-mask { opacity: 1; }
.up-side { flex: 1; display: flex; flex-direction: column; gap: 6px; }
.up-meta { display: flex; align-items: center; justify-content: space-between; }

/* ===== 右侧预览面板：与左栏等高，弹性填充不留底空 ===== */
.pv-panel {
  border: 1px solid var(--tech-panel-border); border-radius: 10px; padding: 12px 14px 14px;
  height: 100%; box-sizing: border-box; min-height: 420px;
  background:
    radial-gradient(circle at 85% -10%, color-mix(in srgb, var(--tech-primary) 10%, transparent), transparent 45%),
    color-mix(in srgb, var(--tech-panel) 55%, transparent);
  display: flex; flex-direction: column; gap: 12px;
}
.cfg-body { align-items: stretch; }
.pv-head { display: flex; align-items: center; gap: 7px; font-size: 13px; font-weight: 700; color: var(--tech-text); }
.pv-head .el-icon { color: var(--tech-primary); }
.pv-hint { margin-left: auto; font-size: 11px; font-weight: 400; color: var(--tech-text-muted); }

/* ① 迷你浏览器 */
.mock-browser { border: 1px solid var(--tech-panel-border); border-radius: 9px; overflow: hidden; }
.mb-bar {
  display: flex; align-items: center; gap: 5px; padding: 6px 8px;
  background: color-mix(in srgb, var(--tech-bg-2, #101a30) 88%, transparent);
  border-bottom: 1px solid var(--tech-panel-border);
}
.mb-dot { width: 8px; height: 8px; border-radius: 50%; }
.mb-dot.r { background: #ff5f57; } .mb-dot.y { background: #febc2e; } .mb-dot.g { background: #28c840; }
.mb-tab {
  display: flex; align-items: center; gap: 6px; margin-left: 6px; padding: 3px 10px;
  border-radius: 7px 7px 0 0; font-size: 11px; color: var(--tech-text);
  background: color-mix(in srgb, var(--tech-primary) 9%, transparent);
  border: 1px solid var(--tech-panel-border); border-bottom: none;
}
.mb-fav { width: 13px; height: 13px; object-fit: contain; }
.mb-fav-ic { font-size: 13px; color: var(--tech-primary); }
.mb-tab-name { max-width: 130px; overflow: hidden; white-space: nowrap; text-overflow: ellipsis; }
.mb-x { font-size: 10px; color: var(--tech-text-muted); }
.mb-body { padding: 10px 12px; display: flex; flex-direction: column; gap: 7px; }
.mb-line { height: 7px; border-radius: 4px; background: color-mix(in srgb, var(--tech-text-muted) 22%, transparent); }
.mb-line.w60 { width: 60%; } .mb-line.w90 { width: 90%; } .mb-line.w75 { width: 75%; }

/* ② 迷你应用框架（首页）：弹性撑满余下空间 */
.mock-app { display: flex; border: 1px solid var(--tech-panel-border); border-radius: 9px; overflow: hidden; flex: 1 1 auto; min-height: 150px; }
.ma-side {
  flex-shrink: 0; width: 100px; display: flex; flex-direction: column;
  background: color-mix(in srgb, var(--tech-bg-2, #101a30) 88%, transparent);
  border-right: 1px solid var(--tech-panel-border); padding: 9px 8px;
}
.ma-logo { width: 24px; height: 24px; margin-bottom: 5px; }
.ma-logo img { width: 100%; height: 100%; object-fit: contain; border-radius: 5px; }
.ma-logo .el-icon { font-size: 24px; color: var(--tech-primary); }
.ma-txt { margin-bottom: 9px; }
.ma-t1 { font-size: 11px; font-weight: 700; color: var(--tech-text); white-space: nowrap; overflow: hidden; text-overflow: ellipsis; }
.ma-t2 { font-size: 8px; letter-spacing: 2px; color: var(--tech-primary); opacity: .9; }
.ma-menu { display: flex; flex-direction: column; gap: 5px; }
.ma-menu i { height: 8px; border-radius: 4px; background: color-mix(in srgb, var(--tech-text-muted) 20%, transparent); }
.ma-menu i.on { background: color-mix(in srgb, var(--tech-primary) 45%, transparent); box-shadow: var(--tech-glow); }
.ma-main { flex: 1; padding: 9px; display: flex; flex-direction: column; gap: 8px; }
.ma-cards { display: flex; gap: 7px; }
.ma-card { flex: 1; border: 1px solid var(--tech-panel-border); border-radius: 7px; padding: 7px 8px; display: flex; flex-direction: column; gap: 5px; }
.ma-kpi { width: 55%; height: 9px; border-radius: 4px; background: color-mix(in srgb, var(--tech-primary) 40%, transparent); }
.ma-line { height: 6px; border-radius: 4px; background: color-mix(in srgb, var(--tech-text-muted) 20%, transparent); }
.ma-line.w80 { width: 80%; }
.ma-chart { flex: 1; border: 1px solid var(--tech-panel-border); border-radius: 7px;
  background: linear-gradient(180deg, transparent 55%, color-mix(in srgb, var(--tech-primary) 16%, transparent)); }

/* ③ 迷你登录页：同样弹性 */
.mock-login {
  position: relative; display: flex; align-items: center; justify-content: space-between; gap: 12px;
  border: 1px solid var(--tech-panel-border); border-radius: 9px; padding: 16px 14px;
  flex: 1 1 auto; min-height: 128px; overflow: hidden;
  background:
    radial-gradient(circle at 20% 30%, color-mix(in srgb, var(--tech-primary) 14%, transparent), transparent 42%),
    radial-gradient(circle at 85% 80%, color-mix(in srgb, var(--tech-accent, #7c5cff) 12%, transparent), transparent 45%),
    color-mix(in srgb, var(--tech-bg-2, #101a30) 88%, transparent);
}
.ml-brand { max-width: 62%; }
.ml-logo { width: 34px; height: 34px; object-fit: contain; border-radius: 8px; margin-bottom: 7px; }
.ml-logo-def { background: linear-gradient(135deg, #00e0ff, #7c5cff); opacity: .85; box-shadow: 0 0 14px rgba(0, 224, 255, .4); }
.ml-name { font-size: 14px; font-weight: 700; letter-spacing: 1px; color: var(--tech-text); text-shadow: var(--tech-glow); }
.ml-slogan { font-size: 10px; color: var(--tech-text-muted); margin-top: 4px; }
.ml-card {
  flex-shrink: 0; width: 96px; height: 100%; border-radius: 8px; padding: 10px;
  display: flex; flex-direction: column; gap: 7px; justify-content: center;
  background: color-mix(in srgb, var(--tech-panel) 80%, transparent);
  border: 1px solid color-mix(in srgb, var(--tech-primary) 30%, var(--tech-panel-border));
}
.ml-field { height: 10px; border-radius: 5px; background: color-mix(in srgb, var(--tech-text-muted) 20%, transparent); }
.ml-btn { height: 14px; border-radius: 7px; margin-top: 3px; background: color-mix(in srgb, var(--tech-primary) 65%, transparent); box-shadow: var(--tech-glow); }

/* ④ 页脚预览 */
.mock-footer { font-size: 11px; color: var(--tech-text-muted); text-align: center; opacity: .9; }
.mf-icp { border-bottom: 1px dotted var(--tech-text-muted); }

/* ===== 响应式：窄屏上下堆叠，预览不再强制等高 ===== */
@media (max-width: 1200px) {
  .cfg-body :deep(.el-col) { flex: 0 0 100% !important; max-width: 100% !important; }
  .pv-panel { min-height: 0; height: auto; }
  .mock-app, .mock-login { flex: 0 0 auto; }
}
</style>
