<template>
  <div class="dl-card">
    <div class="card-title"><span>脚本开发</span><span class="role-tag">系统管理员</span></div>
    <el-row :gutter="12">
      <el-col :span="5">
        <div class="tree-head"><span>分类</span><el-button size="small" @click="openCat()">+ 分类</el-button></div>
        <el-tree :data="tree" :props="{ label: 'name' }" node-key="id" highlight-current default-expand-all @node-click="onCat">
          <template #default="{ data: n }"><span>{{ n.name }} <el-link style="font-size: 11px" type="danger" @click.stop="delCat(n)">删</el-link></span></template>
        </el-tree>
        <div v-if="curCat" style="margin-top:10px">
          <el-button size="small" type="primary" @click="openScript()">+ 新建脚本</el-button>
          <div v-for="s in scripts" :key="s.id" class="script-item" :class="{ active: cur && cur.id === s.id }" @click="pick(s)">
            {{ s.name }} <span class="muted">({{ s.script_type }})</span>
          </div>
        </div>
      </el-col>
      <el-col :span="19">
        <div v-if="!cur" class="empty">选中分类后新建或选择脚本。</div>
        <div v-else>
          <div class="bar">
            <el-input v-model="cur.name" size="small" style="width:200px" />
            <el-select v-model="cur.script_type" size="small" style="width:120px">
              <el-option label="SQL" value="SQL" /><el-option label="Python" value="PYTHON" /><el-option label="Java" value="JAVA" /><el-option label="Shell" value="SHELL" /><el-option label="Scala" value="SCALA" />
            </el-select>
            <el-select v-if="cur.script_type === 'SQL'" v-model="cur.datasource_id" size="small" filterable style="width:200px"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select>
            <el-button size="small" @click="saveScript">保存</el-button>
            <el-button size="small" type="success" :loading="running" @click="run">执行</el-button>
            <el-button size="small" type="danger" @click="delScript">删除</el-button>
          </div>
          <el-input v-model="cur.content" type="textarea" :rows="12" style="font-family:monospace" />
          <div v-if="result" class="result">
            <div>状态: {{ result.status }} <span v-if="result.msg" class="muted">{{ result.msg }}</span></div>
            <pre v-if="cur.script_type !== 'SQL'" class="log-box">{{ result.log }}</pre>
            <template v-else>
              <el-table :data="result.rows || []" size="small" border max-height="240">
                <el-table-column v-for="c in (result.columns || [])" :key="c" :prop="c" :label="c" min-width="100" />
              </el-table>
              <div class="muted">{{ result.rowsRead }} 行</div>
            </template>
          </div>
        </div>
      </el-col>
    </el-row>

    <el-dialog v-model="catDlg" title="新建分类" width="360px">
      <el-input v-model="catName" size="small" />
      <template #footer><el-button @click="catDlg = false">取消</el-button><el-button type="primary" @click="saveCat">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const MODULE = 'SCRIPT'
const tree = ref<any[]>([])
const curCat = ref<any>(null)
const scripts = ref<any[]>([])
const cur = ref<any>(null)
const catDlg = ref(false)
const catName = ref('')
const dsList = ref<any[]>([])
const running = ref(false)
const result = ref<any>(null)

async function loadTree() { try { tree.value = await api.devCatalogTree(MODULE) } catch (e: any) { ElMessage.error(errMsg(e)) } }
function onCat(c: any) { curCat.value = c; cur.value = null; loadScripts() }
async function loadScripts() { if (!curCat.value) { scripts.value = []; return } try { const all = await api.devScripts(); scripts.value = all.filter((s: any) => s.catalog_id === curCat.value.id) } catch (e: any) { ElMessage.error(errMsg(e)) } }
function openCat() { catName.value = ''; catDlg.value = true }
async function saveCat() { if (!catName.value) return; try { await api.devSaveCatalog({ name: catName.value, parent_id: curCat.value?.id || 0, module_type: MODULE, sort: 0 }); catDlg.value = false; await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delCat(c: any) { try { await ElMessageBox.confirm(`删除分类 ${c.name}?`) } catch { return } try { await api.devDeleteCatalog(c.id); await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }
function openScript() { cur.value = { name: '新脚本', script_type: 'SQL', catalog_id: curCat.value.id, datasource_id: dsList.value[0]?.id, content: '', id: undefined }; result.value = null }
function pick(s: any) { cur.value = { ...s }; result.value = null }
async function saveScript() { if (!cur.value) return; try { await api.devSaveScript(cur.value); ElMessage.success('已保存'); await loadScripts() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delScript() { if (!cur.value || !cur.value.id) return; try { await ElMessageBox.confirm('删除脚本?') } catch { return } try { await api.devDeleteScript(cur.value.id); cur.value = null; await loadScripts() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function run() { if (!cur.value) return; running.value = true; try { result.value = await api.devRunScript({ id: cur.value.id, datasource_id: cur.value.datasource_id, content: cur.value.content }) } catch (e: any) { ElMessage.error(errMsg(e)) } finally { running.value = false } }

onMounted(async () => { try { dsList.value = await api.daSources() } catch { /* */ } await loadTree() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.tree-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 13px; color: var(--tech-text-muted); }
.bar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; flex-wrap: wrap; }
.empty { color: var(--tech-text-muted); font-size: 13px; padding: 40px 0; text-align: center; }
.script-item { padding: 4px 8px; cursor: pointer; border-radius: 4px; font-size: 13px; }
.script-item:hover { background: rgba(0, 224, 255, 0.08); }
.script-item.active { background: rgba(0, 224, 255, 0.15); }
.muted { color: var(--tech-text-muted); font-size: 12px; }
.result { margin-top: 10px; }
.log-box { background: rgba(0, 0, 0, 0.3); color: #9fe; padding: 10px; font-size: 12px; white-space: pre-wrap; max-height: 240px; overflow: auto; border-radius: 4px; }
</style>
