<template>
  <div class="dl-card">
    <div class="card-title"><span>函数管理</span><span class="role-tag">系统管理员</span></div>
    <el-button type="primary" size="small" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增函数</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="name" label="函数名" min-width="140" />
      <el-table-column label="类型" width="90"><template #default="{ row }"><el-tag size="small" :type="row.func_type === 'UDF' ? 'warning' : ''">{{ row.func_type }}</el-tag></template></el-table-column>
      <el-table-column prop="language" label="语言" width="80" />
      <el-table-column prop="return_type" label="返回类型" width="110" />
      <el-table-column prop="description" label="说明" min-width="180" show-overflow-tooltip />
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>

    <el-dialog v-model="dlg" :title="form.id ? '编辑函数' : '新增函数'" width="600px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="函数名"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="form.func_type"><el-radio value="BUILTIN">内置</el-radio><el-radio value="UDF">自定义</el-radio></el-radio-group></el-form-item>
        <el-form-item label="语言"><el-select v-model="form.language" style="width:100%"><el-option v-for="l in ['SQL','Python','Java']" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="返回类型"><el-input v-model="form.return_type" placeholder="如 VARCHAR / DOUBLE" /></el-form-item>
        <el-form-item label="函数体"><el-input v-model="form.body" type="textarea" :rows="5" placeholder="SQL/脚本内容" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="form.description" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="dlg = false">取消</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const rows = ref<any[]>([]); const loading = ref(false)
const dlg = ref(false); const form = reactive<any>({ id: null, name: '', func_type: 'UDF', language: 'SQL', body: '', return_type: 'VARCHAR', description: '' })
async function load() { loading.value = true; try { rows.value = await api.devFunctions() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, name: '', func_type: 'UDF', language: 'SQL', body: '', return_type: 'VARCHAR', description: '' }, row || {}); dlg.value = true }
async function save() { if (!form.name) return ElMessage.warning('填函数名'); try { await api.devSaveFunction({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除函数 ${row.name}？`, '提示', { type: 'warning' }); try { await api.devDeleteFunction(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>
.card-title { display: flex; align-items: center; justify-content: space-between; font-weight: 600; margin-bottom: 12px; }
.role-tag { font-size: 12px; color: var(--tech-text-muted); border: 1px solid var(--tech-panel-border); padding: 2px 8px; border-radius: 4px; }
</style>
