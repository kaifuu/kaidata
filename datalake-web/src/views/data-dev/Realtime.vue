<template>
  <div class="dl-card">
    <div class="card-title"><span>实时开发</span><span class="role-tag">系统管理员</span></div>
    <el-row :gutter="12">
      <el-col :span="5">
        <div class="tree-head"><span>分类</span><el-button size="small" @click="openCat()">+ 分类</el-button></div>
        <el-tree :data="tree" :props="{ label: 'name' }" node-key="id" highlight-current default-expand-all @node-click="onCat">
          <template #default="{ data: n }"><span>{{ n.name }} <el-link style="font-size: 11px" type="danger" @click.stop="delCat(n)">删</el-link></span></template>
        </el-tree>
      </el-col>
      <el-col :span="19">
        <div class="bar">
          <el-button size="small" type="primary" :disabled="!curCat" @click="openJob()">新建管道</el-button>
          <el-button size="small" @click="loadJobs">刷新</el-button>
        </div>
        <el-table :data="jobs" size="small" stripe border>
          <el-table-column prop="name" label="管道" min-width="120" />
          <el-table-column prop="type" label="类型" width="130" />
          <el-table-column prop="kafka_topic" label="topic" width="110" />
          <el-table-column prop="target_table" label="目标表" width="110" />
          <el-table-column prop="status" label="状态" width="80" />
          <el-table-column label="操作" width="230">
            <template #default="{ row }">
              <el-button link size="small" type="success" @click="start(row)">启动</el-button>
              <el-button link size="small" type="warning" @click="stop(row)">停止</el-button>
              <el-button link size="small" @click="openJob(row)">编辑</el-button>
              <el-button link size="small" type="danger" @click="del(row)">删除</el-button>
            </template>
          </el-table-column>
        </el-table>
        <div class="hint" style="margin-top:8px">复用「数据接入 - 实时数据接入」的 Kafka 管道（KAFKA_TO_SR / JDBC_TO_KAFKA），按分类组织，与数据接入模块操作同一 ing_stream_job。</div>
      </el-col>
    </el-row>

    <el-dialog v-model="catDlg" title="新建分类" width="360px">
      <el-input v-model="catName" placeholder="分类名称" size="small" />
      <template #footer><el-button @click="catDlg = false">取消</el-button><el-button type="primary" @click="saveCat">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="jobDlg" :title="jobForm.id ? '编辑管道' : '新建管道'" width="620px">
      <el-form :model="jobForm" label-width="100px" size="small">
        <el-form-item label="名称"><el-input v-model="jobForm.name" /></el-form-item>
        <el-form-item label="分类"><el-select v-model="jobForm.catalog_id" style="width:100%"><el-option v-for="c in flatCats" :key="c.id" :label="c.name" :value="c.id" /></el-select></el-form-item>
        <el-form-item label="类型"><el-select v-model="jobForm.type"><el-option label="Kafka→StarRocks" value="KAFKA_TO_SR" /><el-option label="JDBC→Kafka" value="JDBC_TO_KAFKA" /></el-select></el-form-item>
        <el-form-item label="源数据源"><el-select v-model="jobForm.source_ds_id" filterable style="width:100%"><el-option v-for="d in dsList" :key="d.id" :label="`${d.name}(${d.type})`" :value="d.id" /></el-select></el-form-item>
        <el-form-item label="源查询(SQL)"><el-input v-model="jobForm.source_query" type="textarea" :rows="2" /></el-form-item>
        <el-form-item label="Kafka topic"><el-input v-model="jobForm.kafka_topic" /></el-form-item>
        <el-form-item label="目标库"><el-input v-model="jobForm.target_db" /></el-form-item>
        <el-form-item label="目标表"><el-input v-model="jobForm.target_table" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="jobDlg = false">取消</el-button><el-button type="primary" @click="saveJob">保存</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { api, errMsg } from '@/api'

const MODULE = 'STREAM'
const tree = ref<any[]>([])
const curCat = ref<any>(null)
const jobs = ref<any[]>([])
const catDlg = ref(false)
const catName = ref('')
const flatCats = ref<any[]>([])
const jobDlg = ref(false)
const jobForm = ref<any>({})
const dsList = ref<any[]>([])

function flatten(n: any[]): any[] { const o: any[] = []; for (const x of n) { o.push(x); if (x.children) o.push(...flatten(x.children)) } return o }
async function loadTree() { try { tree.value = await api.devCatalogTree(MODULE); flatCats.value = flatten(tree.value) } catch (e: any) { ElMessage.error(errMsg(e)) } }
function onCat(c: any) { curCat.value = c; loadJobs() }
async function loadJobs() { try { jobs.value = await api.daStreamJobs(curCat.value?.id) } catch (e: any) { ElMessage.error(errMsg(e)) } }
function openCat() { catName.value = ''; catDlg.value = true }
async function saveCat() { if (!catName.value) return; try { await api.devSaveCatalog({ name: catName.value, parent_id: curCat.value?.id || 0, module_type: MODULE, sort: 0 }); ElMessage.success('已建'); catDlg.value = false; await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function delCat(c: any) { try { await ElMessageBox.confirm(`删除分类 ${c.name}?`) } catch { return } try { await api.devDeleteCatalog(c.id); await loadTree() } catch (e: any) { ElMessage.error(errMsg(e)) } }
function openJob(row?: any) { jobForm.value = row ? { ...row } : { catalog_id: curCat.value?.id, type: 'KAFKA_TO_SR', source_ds_id: dsList.value[0]?.id }; jobDlg.value = true }
async function saveJob() { if (!jobForm.value.name) return; try { await api.daSaveStreamJob(jobForm.value); ElMessage.success('已保存'); jobDlg.value = false; await loadJobs() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function start(row: any) { try { await api.daStreamStart(row.id); ElMessage.success('已启动'); await loadJobs() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function stop(row: any) { try { await api.daStreamStop(row.id); ElMessage.success('已停止'); await loadJobs() } catch (e: any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { try { await ElMessageBox.confirm(`删除 ${row.name}?`) } catch { return } try { await api.daDeleteStreamJob(row.id); await loadJobs() } catch (e: any) { ElMessage.error(errMsg(e)) } }

onMounted(async () => { try { dsList.value = await api.daSources() } catch { /* */ } await loadTree() })
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 10px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
.tree-head { display: flex; justify-content: space-between; align-items: center; margin-bottom: 6px; font-size: 13px; color: var(--tech-text-muted); }
.bar { display: flex; gap: 8px; margin-bottom: 8px; align-items: center; }
.hint { color: var(--tech-text-muted); font-size: 13px; }
</style>
