<template>
  <div class="dl-card">
    <div class="card-title"><span>数据仓库 · 分层管理</span><span class="role-tag">系统管理员</span></div>
    <el-table :data="layers" size="small" stripe border v-loading="loading">
      <el-table-column prop="code" label="层级编码" width="120" />
      <el-table-column prop="name" label="名称" min-width="140" />
      <el-table-column prop="sort" label="排序" width="80" />
      <el-table-column label="绑定数据源" min-width="200">
        <template #default="{ row }">
          <el-tag v-for="d in (bindMap[row.code]||[])" :key="d.id" size="small" closable @close="unbind(d.id, row.code)" style="margin-right:4px">ds{{ d.datasource_id }}</el-tag>
          <el-button link size="small" type="primary" @click="openBind(row)">+ 绑定</el-button>
        </template>
      </el-table-column>
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openLayer(row)">编辑</el-button><el-button link size="small" type="danger" @click="delLayer(row)">删除</el-button></template></el-table-column>
    </el-table>
    <div class="hint">分层（ODS/DWD/DWS/ADS/DIM）绑定数据源后，数据探查/接入的"所属层级"即从此选取目标数据源。</div>

    <el-dialog v-model="layerDlg" :title="layerForm.code ? '编辑层级' : '新增层级'" width="420px">
      <el-form :model="layerForm" label-width="80px" size="small">
        <el-form-item label="编码"><el-input v-model="layerForm.code" :disabled="!!layerForm.code" placeholder="如 dwd" /></el-form-item>
        <el-form-item label="名称"><el-input v-model="layerForm.name" /></el-form-item>
        <el-form-item label="排序"><el-input-number v-model="layerForm.sort" :min="0" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="layerDlg = false">取消</el-button><el-button type="primary" @click="saveLayer">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="bindDlg" title="绑定数据源" width="420px">
      <el-select v-model="bindDs" placeholder="选择数据源" style="width:100%">
        <el-option v-for="d in dsList" :key="d.id" :label="`${d.name} (${d.type})`" :value="d.id" />
      </el-select>
      <template #footer><el-button @click="bindDlg = false">取消</el-button><el-button type="primary" @click="doBind">绑定</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const layers = ref<any[]>([]); const loading = ref(false)
const bindMap = ref<Record<string, any[]>>({})
const dsList = ref<any[]>([])
const layerDlg = ref(false); const layerForm = reactive<any>({ code: '', name: '', sort: 1 })
const bindDlg = ref(false); const bindLayer = ref(''); const bindDs = ref<number | null>(null)

async function load() {
  loading.value = true
  try { layers.value = await api.govLayers(); dsList.value = await api.daSources(); await loadBinds() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false }
}
async function loadBinds() { for (const l of layers.value) { try { bindMap.value[l.code] = await api.govLayerDs(l.code) } catch { bindMap.value[l.code] = [] } } }
function openLayer(row?: any) { Object.assign(layerForm, { code: '', name: '', sort: 1 }, row || {}); layerDlg.value = true }
async function saveLayer() { try { await api.govSaveLayer({ ...layerForm }); ElMessage.success('保存成功'); layerDlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delLayer(row: any) { await ElMessageBox.confirm(`删除层级 ${row.code}？`, '提示', { type: 'warning' }); try { await api.govDeleteLayer(row.code); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function openBind(row: any) { bindLayer.value = row.code; bindDs.value = null; bindDlg.value = true }
async function doBind() { if (!bindDs.value) return ElMessage.warning('选数据源'); try { await api.govBindLayerDs({ layer_code: bindLayer.value, datasource_id: bindDs.value }); ElMessage.success('已绑定'); bindDlg.value = false; await loadBinds() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unbind(id: number, code: string) { try { await api.govUnbindLayerDs(id); bindMap.value[code] = await api.govLayerDs(code) } catch (e:any) { ElMessage.error(errMsg(e)) } }

onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.hint { margin-top: 12px; color: var(--tech-text-muted); font-size: 13px; }
</style>
