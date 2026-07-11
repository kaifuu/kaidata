<template>
  <div>
    <!-- Hero 搜索（谷歌风：白底 / 大圆角胶囊搜索框 / 柔和阴影） -->
    <div class="hero">
      <div class="hero-title">发现开放数据</div>
      <div class="hero-sub">检索已审核通过的数据表 · 订阅后通过 API / 库表方式对接</div>
      <div class="hero-search">
        <el-input v-model="kw" placeholder="搜索表名 / 名称 / 注释..." size="large" clearable @keyup.enter="loadResources" @clear="loadResources" class="big-input">
          <template #prefix><el-icon><Search /></el-icon></template>
        </el-input>
        <el-button type="primary" size="large" round @click="loadResources"><el-icon style="margin-right:4px"><Search /></el-icon>搜索</el-button>
      </div>
      <div class="hero-row">
        <span class="ml">标签</span>
        <span v-for="t in tags" :key="t.id" class="chip" :class="{ on: tagId === t.id }" @click="toggleTag(t.id)">{{ t.name }}</span>
        <span v-if="!tags.length" class="ml">—</span>
      </div>
      <div class="hero-row">
        <span class="ml">分类</span>
        <el-tree-select v-model="catalogId" :data="catalogTree" :props="{ label: 'name', children: 'children' }" node-key="id" placeholder="全部分类" clearable check-strictly class="cat-sel" @change="loadResources" />
        <el-button v-if="kw || tagId || catalogId" link type="primary" @click="clearFilter">清除筛选</el-button>
      </div>
    </div>

    <div class="bar">
      <span class="muted">共 <b>{{ rows.length }}</b> 个已审核表</span>
      <el-badge :value="cart.length" :hidden="!cart.length"><el-button size="small" @click="cartDrawer = true"><el-icon><ShoppingCart /></el-icon> 购物车</el-button></el-badge>
    </div>

    <div class="grid2" v-loading="loading">
      <div v-for="r in rows" :key="r.asset_id" class="rcard">
        <div class="rhead"><el-icon class="ricon"><Files /></el-icon><div class="rtitle">{{ r.name }}</div><el-tag size="small" round effect="plain">{{ r.security_level || '内部' }}</el-tag></div>
        <div class="rtable mono">{{ r.schema_name }}.{{ r.table_name }}</div>
        <div class="rdesc">{{ r.comment || r.description || '暂无描述' }}</div>
        <div class="rmeta">{{ countCols(r.columns_json) }} 字段</div>
        <div class="rops">
          <el-button size="small" type="primary" link @click="openSchema(r)"><el-icon style="margin-right:3px"><View /></el-icon>结构/样例</el-button>
          <el-button size="small" link @click="addCart(r)">购物车</el-button>
          <el-button size="small" type="warning" link @click="openSubscribe([r])">订阅</el-button>
        </div>
      </div>
      <div v-if="!rows.length" class="empty">暂无已审核通过的表资产（需在「数据资产」挂载表资产并通过审核）</div>
    </div>

    <!-- 表结构 / 样例 -->
    <el-dialog v-model="schemaDlg" :title="`表结构 · ${curAsset?.name || ''}`" width="820px" top="6vh">
      <el-tabs v-model="schemaTab">
        <el-tab-pane label="表结构" name="struct">
          <el-table :data="schemaCols" size="small" border max-height="380">
            <el-table-column prop="name" label="字段名" min-width="150" />
            <el-table-column prop="type" label="类型" width="140" />
            <el-table-column prop="comment" label="注释" min-width="200" show-overflow-tooltip />
          </el-table>
        </el-tab-pane>
        <el-tab-pane label="样例数据" name="sample">
          <el-table :data="sample?.rows || []" size="small" border max-height="380" v-loading="sampleLoading">
            <el-table-column v-for="c in (sample?.columns || [])" :key="c" :prop="c" :label="c" min-width="130" show-overflow-tooltip />
          </el-table>
          <div class="muted" v-if="sample">{{ sample.rowsRead }} 行（LIMIT 10）</div>
        </el-tab-pane>
      </el-tabs>
    </el-dialog>

    <!-- 订阅申请 -->
    <el-dialog v-model="subDlg" title="提交订阅申请" width="520px">
      <div class="muted mb">将订阅 {{ subItems.length }} 个库表：{{ subNames }}</div>
      <el-form label-width="86px" size="small">
        <el-form-item label="开放方式"><el-radio-group v-model="subForm.open_type"><el-radio value="API">API</el-radio><el-radio value="TABLE">库表</el-radio></el-radio-group></el-form-item>
        <el-form-item label="用途说明"><el-input v-model="subForm.purpose" type="textarea" :rows="2" placeholder="申请用途 / 场景" /></el-form-item>
        <el-form-item label="限次"><el-input-number v-model="subForm.limit_count" :min="0" controls-position="right" /><span class="muted" style="margin-left:8px">0=不限</span></el-form-item>
        <el-form-item label="限流(QPS)"><el-input-number v-model="subForm.limit_qps" :min="0" controls-position="right" /><span class="muted" style="margin-left:8px">0=不限</span></el-form-item>
      </el-form>
      <template #footer><el-button @click="subDlg = false">取消</el-button><el-button type="primary" @click="submitSub">提交申请</el-button></template>
    </el-dialog>

    <!-- 购物车 -->
    <el-drawer v-model="cartDrawer" title="我的购物车" size="520px">
      <div style="margin-bottom:8px"><el-button size="small" type="primary" :disabled="!cart.length" @click="openSubscribeFromCart">批量提交订阅</el-button> <el-button size="small" type="danger" @click="clearCart">清空</el-button></div>
      <el-table :data="cart" size="small" border>
        <el-table-column prop="item_name" label="资源" min-width="180" show-overflow-tooltip />
        <el-table-column prop="item_type" label="类型" width="70"><template #default="{ row }"><el-tag size="small">{{ row.item_type }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="64"><template #default="{ row }"><el-button link size="small" type="danger" @click="removeCart(row)">移除</el-button></template></el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ShoppingCart, Files, Search, View } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'

const rows = ref<any[]>([])
const loading = ref(false)
const kw = ref('')
const tagId = ref<number | null>(null)
const tags = ref<any[]>([])
const catalogTree = ref<any[]>([])
const catalogId = ref<number | null>(null)

const cart = ref<any[]>([])
const cartDrawer = ref(false)

const schemaDlg = ref(false)
const schemaTab = ref('struct')
const curAsset = ref<any>(null)
const schemaCols = ref<any[]>([])
const sample = ref<any>(null)
const sampleLoading = ref(false)

const subDlg = ref(false)
const subItems = ref<any[]>([])
const subForm = reactive<any>({ open_type: 'API', purpose: '', limit_count: 0, limit_qps: 0 })
const subNames = computed(() => subItems.value.map((i: any) => i.table_name).join('、'))

function countCols(cj: string) { try { const a = JSON.parse(cj); return Array.isArray(a) ? a.length : 0 } catch { return 0 } }
function buildTree(flat: any[]): any[] {
  const m = new Map<number, any>()
  flat.forEach((f: any) => m.set(Number(f.id), { ...f, children: [] }))
  const roots: any[] = []
  m.forEach((n: any) => { const p = Number(n.parent_id); if (!p || !m.has(p)) roots.push(n); else m.get(p).children.push(n) })
  return roots
}

async function loadResources() {
  loading.value = true
  try {
    const [r, t, c] = await Promise.all([
      api.marketResources({ type: 'table', kw: kw.value || undefined, catalogId: catalogId.value || undefined, tagId: tagId.value || undefined }),
      api.marketTags(),
      api.marketCatalogTree()
    ])
    rows.value = r || []; tags.value = t || []; catalogTree.value = buildTree(c || [])
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
function toggleTag(id: number) { tagId.value = tagId.value === id ? null : id; loadResources() }
function clearFilter() { kw.value = ''; tagId.value = null; catalogId.value = null; loadResources() }

async function loadCart() { try { cart.value = await api.marketCart() } catch { /* */ } }
async function addCart(r: any) {
  try { await api.marketAddCart({ item_type: 'table', item_ref: String(r.asset_id), item_name: `${r.schema_name}.${r.table_name}` }); ElMessage.success('已加入购物车'); await loadCart() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}
async function removeCart(row: any) { try { await api.marketRemoveCart(row.id); await loadCart() } catch { /* */ } }
async function clearCart() { try { await api.marketClearCart(); ElMessage.success('已清空'); await loadCart() } catch (e: any) { ElMessage.error(errMsg(e)) } }

async function openSchema(r: any) {
  curAsset.value = r; schemaDlg.value = true; schemaTab.value = 'struct'; schemaCols.value = []; sample.value = null
  try {
    const s = await api.marketTableSchema(r.asset_id)
    schemaCols.value = s.columns || []
    sampleLoading.value = true
    sample.value = await api.marketPreviewTable({ dsId: s.dsId, table: s.fullTable })
  } catch (e: any) { ElMessage.error(errMsg(e)) } finally { sampleLoading.value = false }
}

function openSubscribe(items: any[]) { subItems.value = items; Object.assign(subForm, { open_type: 'API', purpose: '', limit_count: 0, limit_qps: 0 }); subDlg.value = true }
function openSubscribeFromCart() {
  const items = cart.value.map((c: any) => ({ asset_id: Number(c.item_ref), table_name: c.item_name, meta_id: 0 }))
  if (!items.length) return
  openSubscribe(items)
}
async function submitSub() {
  if (!subItems.value.length) return
  try { const r = await api.subApply({ items: subItems.value, ...subForm }); ElMessage.success(`已提交 ${r.count} 条订阅申请`); subDlg.value = false; await loadCart() }
  catch (e: any) { ElMessage.error(errMsg(e)) }
}

onMounted(() => { loadResources(); loadCart() })
</script>
<style scoped>
/* Hero（双主题适配：背景/文字均用主题变量，避免暗色下白底浅字撞色） */
.hero { text-align: center; padding: 40px 20px 28px; background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 16px; box-shadow: var(--tech-shadow); margin-bottom: 18px; }
.hero-title { font-size: 28px; font-weight: 700; color: var(--tech-primary); letter-spacing: 0.5px; text-shadow: var(--tech-glow); }
.hero-sub { color: var(--tech-text-muted); font-size: 13px; margin: 8px 0 22px; }
.hero-search { display: flex; justify-content: center; gap: 12px; max-width: 680px; margin: 0 auto 4px; align-items: center; }
.hero-search .big-input { flex: 1; }
.hero-search .big-input :deep(.el-input__wrapper) { border-radius: 24px; background: var(--el-bg-color); border: 1px solid var(--tech-panel-border); box-shadow: var(--tech-shadow); padding-left: 18px; }
.hero-search .big-input :deep(.el-input__wrapper:hover) { border-color: var(--tech-primary); }
.hero-row { display: flex; align-items: center; justify-content: center; gap: 8px; flex-wrap: wrap; margin-top: 16px; }
.ml { font-size: 12px; color: var(--tech-text-muted); }
.chip { font-size: 12px; padding: 4px 14px; border-radius: 16px; border: 1px solid var(--tech-panel-border); color: var(--tech-text); background: var(--el-fill-color-light); cursor: pointer; transition: all .15s; user-select: none; }
.chip:hover { color: var(--tech-primary); border-color: var(--tech-primary); }
.chip.on { color: #fff; background: var(--tech-primary); border-color: var(--tech-primary); }
.cat-sel { width: 220px; }
/* 结果条 */
.bar { display: flex; align-items: center; justify-content: space-between; margin-bottom: 10px; }
/* 卡片网格 */
.grid2 { display: grid; grid-template-columns: repeat(3, 1fr); gap: 14px; }
.rcard { background: var(--tech-panel); border: 1px solid var(--tech-panel-border); border-radius: 12px; padding: 18px; box-shadow: var(--tech-shadow); transition: all .2s; display: flex; flex-direction: column; }
.rcard:hover { transform: translateY(-2px); border-color: var(--tech-primary); box-shadow: 0 6px 20px rgba(0,0,0,0.22); }
.rhead { display: flex; align-items: center; gap: 8px; margin-bottom: 8px; }
.ricon { font-size: 20px; color: var(--tech-primary); }
.rtitle { font-size: 16px; font-weight: 600; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--tech-text); }
.rtable { font-size: 12px; color: var(--tech-text-muted); margin-bottom: 8px; }
.rdesc { font-size: 13px; color: var(--tech-text); flex: 1; display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; margin-bottom: 8px; }
.rmeta { font-size: 12px; color: var(--tech-text-muted); margin-bottom: 10px; }
.rops { display: flex; gap: 6px; flex-wrap: wrap; }
.empty { grid-column: 1 / -1; text-align: center; color: var(--tech-text-muted); padding: 50px; }
/* 通用 */
.muted { color: var(--tech-text-muted); font-size: 12px; }
.mb { margin-bottom: 6px; }
.mono { font-family: ui-monospace, Menlo, Consolas, monospace; }
@media (max-width: 1200px) { .grid2 { grid-template-columns: repeat(2, 1fr); } }
</style>
