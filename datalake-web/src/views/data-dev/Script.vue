<template>
  <div class="dl-card">
    <div class="card-title"><span>数据开发 · SQL 工作台</span><span class="role-tag">系统管理员</span></div>
    <div class="ide">
      <div class="left">
        <div style="margin-bottom:8px;display:flex;gap:6px"><el-button size="small" type="primary" @click="open()">新建</el-button><el-button size="small" @click="load">刷新</el-button></div>
        <el-table :data="scripts" size="small" border highlight-current-row @current-change="pick" height="380">
          <el-table-column prop="name" label="脚本" min-width="120" />
          <el-table-column label="类型" width="60"><template #default="{ row }"><el-tag size="small">{{ row.script_type }}</el-tag></template></el-table-column>
        </el-table>
      </div>
      <div class="right">
        <div class="bar">
          <el-input v-model="form.name" size="small" placeholder="脚本名" style="width:160px" />
          <el-select v-model="form.datasource_id" size="small" placeholder="数据源" style="width:200px">
            <el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" />
          </el-select>
          <el-button size="small" type="success" :loading="running" @click="run">执行</el-button>
          <el-button size="small" type="primary" @click="save">保存</el-button>
          <el-button size="small" @click="del" :disabled="!form.id">删除</el-button>
        </div>
        <el-input v-model="form.content" type="textarea" :rows="8" placeholder="SELECT * FROM ods.your_table LIMIT 10" style="font-family:monospace" />
        <div v-if="result" class="result">
          <div class="muted">结果：{{ result.status }} · {{ result.rowsRead }} 行 · {{ result.msg || 'OK' }}</div>
          <el-table :data="result.rows" size="small" border max-height="220" v-if="result.rows && result.rows.length">
            <el-table-column v-for="c in result.columns" :key="c" :prop="c" :label="c" min-width="120" show-overflow-tooltip />
          </el-table>
          <div v-else-if="result.columns && !result.columns.length" class="muted">(DDL/DML 执行完成)</div>
        </div>
      </div>
    </div>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'
const scripts = ref<any[]>([]); const dsList = ref<any[]>([])
const running = ref(false); const result = ref<any>(null)
const form = reactive<any>({ id: null, name: '', script_type: 'SQL', datasource_id: null as any, content: 'SELECT COUNT(*) total FROM ods.your_table;', description: '' })
async function load() { try { scripts.value = await api.devScripts() } catch (e:any) { ElMessage.error(errMsg(e)) } }
function pick(row: any) { if (!row) return; Object.assign(form, { id: row.id, name: row.name, script_type: row.script_type, datasource_id: row.datasource_id, content: row.content, description: row.description }) }
function open() { Object.assign(form, { id: null, name: '', script_type: 'SQL', datasource_id: dsList.value[0]?.id || null, content: '', description: '' }); result.value = null }
async function run() { if (!form.datasource_id || !form.content) return ElMessage.warning('选数据源并写 SQL'); running.value = true; try { result.value = await api.devRunScript({ id: form.id, datasource_id: form.datasource_id, content: form.content }) } catch (e:any) { ElMessage.error(errMsg(e)) } finally { running.value = false } }
async function save() { if (!form.name || !form.datasource_id) return ElMessage.warning('填名称与数据源'); try { await api.devSaveScript({ ...form }); ElMessage.success('已保存'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del() { if (!form.id) return; await ElMessageBox.confirm('删除该脚本？', '提示', { type: 'warning' }); try { await api.devDeleteScript(form.id); ElMessage.success('已删除'); open(); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(async () => { await load(); try { dsList.value = await api.daSources(); if (!form.datasource_id && dsList.value[0]) form.datasource_id = dsList.value[0].id } catch { /* */ } })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.ide { display: grid; grid-template-columns: 260px 1fr; gap: 12px; }
.right .bar { display: flex; gap: 8px; margin-bottom: 8px; flex-wrap: wrap; }
.result { margin-top: 10px; }
.muted { color: var(--tech-text-muted); font-size: 12px; margin-bottom: 6px; display:block; }
</style>
