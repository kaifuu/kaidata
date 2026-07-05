<template>
  <div class="dl-card">
    <div class="card-title">
      <span>数据集 · 开放资源</span>
      <el-badge :value="cart.length" :hidden="!cart.length"><el-button size="small" @click="cartDrawer = true"><el-icon><ShoppingCart /></el-icon> 购物车</el-button></el-badge>
    </div>
    <el-tabs v-model="tab" @change="load">
      <el-tab-pane label="接口资源（数据服务）" name="service" />
      <el-tab-pane label="库表资源（元数据）" name="table" />
    </el-tabs>
    <el-input v-model="kw" placeholder="搜索名称" size="small" clearable style="width:240px;margin-bottom:12px" />
    <div class="grid" v-loading="loading">
      <div v-for="r in filtered" :key="r.type + r.ref" class="card">
        <div class="ct"><el-icon><Connection v-if="r.type === 'service'" /><Files v-else /></el-icon> {{ r.name }}</div>
        <div class="cd"><span v-if="r.type === 'service'">接口 · {{ r.method }} · <code>{{ r.path }}</code></span><span v-else>库表 · ds{{ r.ds_id }} · {{ r.full }}</span></div>
        <div class="cd muted">{{ r.comment || (r.params ? '参数: ' + r.params : '') }}</div>
        <div class="ops">
          <el-button size="small" type="warning" @click="addCart(r)">加入购物车</el-button>
          <el-button v-if="r.type === 'service'" size="small" type="success" @click="testApi(r)">试调</el-button>
          <el-button v-else size="small" type="primary" @click="preview(r)">预览数据</el-button>
        </div>
      </div>
      <div v-if="!filtered.length" class="muted" style="grid-column:1/-1">暂无开放资源（需在数据服务发布服务 / 元数据同步表）</div>
    </div>

    <el-dialog v-model="apiDlg" :title="`接口对接 - ${cur?.name || ''}`" width="660px">
      <div class="muted" style="margin-bottom:4px">公开调用 URL（免鉴权，可给第三方）：</div>
      <el-input :model-value="curUrl" readonly />
      <div style="margin:8px 0"><span class="muted">参数(key=value 每行一个)：</span></div>
      <el-input v-model="apiParams" type="textarea" :rows="2" placeholder="limit=10" />
      <el-button size="small" type="success" :loading="apiLoading" @click="runApi" style="margin-top:8px">调用</el-button>
      <div v-if="apiResult" style="margin-top:8px">
        <span class="muted">{{ apiResult.status }} · {{ apiResult.rowsRead }} 行 · {{ apiResult.cost_ms }}ms</span>
        <el-table :data="apiResult.rows" size="small" border max-height="260" v-if="apiResult.rows && apiResult.rows.length" style="margin-top:6px">
          <el-table-column v-for="c in apiResult.columns" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
        </el-table>
      </div>
    </el-dialog>

    <el-dialog v-model="prevDlg" :title="`数据预览 - ${cur?.full || cur?.name || ''}`" width="760px">
      <el-table :data="prevResult?.rows || []" size="small" border max-height="420" v-loading="prevLoading">
        <el-table-column v-for="c in (prevResult?.columns || [])" :key="c" :prop="c" :label="c" min-width="130" show-overflow-tooltip />
      </el-table>
      <div class="muted" v-if="prevResult">{{ prevResult.rowsRead }} 行（LIMIT 10）</div>
    </el-dialog>

    <el-drawer v-model="cartDrawer" title="我的购物车" size="500px">
      <div style="margin-bottom:8px"><el-button size="small" type="danger" @click="clearCart">清空</el-button></div>
      <el-table :data="cart" size="small" border>
        <el-table-column prop="item_name" label="资源" min-width="160" />
        <el-table-column prop="item_type" label="类型" width="80"><template #default="{ row }"><el-tag size="small">{{ row.item_type }}</el-tag></template></el-table-column>
        <el-table-column label="操作" width="70"><template #default="{ row }"><el-button link size="small" type="danger" @click="removeCart(row)">移除</el-button></template></el-table-column>
      </el-table>
    </el-drawer>
  </div>
</template>
<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { ShoppingCart, Connection, Files } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const tab = ref('service'); const kw = ref(''); const rows = ref<any[]>([]); const loading = ref(false); const cart = ref<any[]>([])
const cartDrawer = ref(false)
const apiDlg = ref(false); const cur = ref<any>(null); const apiParams = ref(''); const apiResult = ref<any>(null); const apiLoading = ref(false)
const curUrl = computed(() => cur.value ? 'http://localhost:8090' + cur.value.path : '')
const prevDlg = ref(false); const prevResult = ref<any>(null); const prevLoading = ref(false)
const filtered = computed(() => kw.value ? rows.value.filter((r:any) => (r.name || '').toLowerCase().includes(kw.value.toLowerCase())) : rows.value)
async function load() { loading.value = true; try { rows.value = await api.marketResources(tab.value) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
async function loadCart() { try { cart.value = await api.marketCart() } catch { /* */ } }
async function addCart(r: any) { try { await api.marketAddCart({ item_type: r.type, item_ref: String(r.ref), item_name: r.name }); ElMessage.success('已加入购物车'); await loadCart() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function removeCart(row: any) { try { await api.marketRemoveCart(row.id); await loadCart() } catch { /* */ } }
async function clearCart() { try { await api.marketClearCart(); ElMessage.success('已清空'); await loadCart() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function testApi(r: any) { cur.value = r; apiParams.value = ''; apiResult.value = null; apiDlg.value = true }
async function runApi() { const params: Record<string, any> = {}; apiParams.value.split('\n').forEach((line) => { const i = line.indexOf('='); if (i > 0) params[line.slice(0, i).trim()] = line.slice(i + 1).trim() }); apiLoading.value = true; try { apiResult.value = await api.dsInvoke(cur.value.ref, params) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { apiLoading.value = false } }
async function preview(r: any) { cur.value = r; prevDlg.value = true; prevLoading.value = true; prevResult.value = null; try { prevResult.value = await api.marketPreviewTable({ dsId: r.ds_id, table: r.full }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { prevLoading.value = false } }
onMounted(() => { load(); loadCart() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.grid { display: grid; grid-template-columns: repeat(3, 1fr); gap: 12px; }
.card { border: 1px solid var(--tech-panel-border); border-radius: 8px; padding: 12px; background: var(--el-fill-color-light); }
.ct { font-weight: 600; margin-bottom: 6px; display: flex; align-items: center; gap: 6px; }
.cd { font-size: 12px; margin-bottom: 4px; }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.ops { margin-top: 8px; display: flex; gap: 6px; }
</style>
