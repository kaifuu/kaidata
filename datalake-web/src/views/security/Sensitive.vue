<template>
  <div class="dl-card">
    <div class="card-title"><span>敏感数据管理</span><span class="role-tag">系统管理员</span></div>
    <el-button size="small" type="primary" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 登记敏感字段</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column label="字段" min-width="220"><template #default="{ row }">{{ row.source_table }}.{{ row.source_column }}</template></el-table-column>
      <el-table-column prop="sensitive_type" label="敏感类型" width="110"><template #default="{ row }"><el-tag size="small" type="warning">{{ row.sensitive_type }}</el-tag></template></el-table-column>
      <el-table-column prop="level" label="级别" width="80" />
      <el-table-column prop="mask_rule_name" label="脱敏规则" width="120" />
      <el-table-column prop="description" label="说明" min-width="160" />
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dlg" :title="form.id ? '编辑敏感字段' : '登记敏感字段'" width="500px">
      <el-form :model="form" label-width="80px" size="small">
        <el-form-item label="表名"><el-input v-model="form.source_table" placeholder="如 ods.ods_batch" /></el-form-item>
        <el-form-item label="字段名"><el-input v-model="form.source_column" placeholder="如 phone" /></el-form-item>
        <el-form-item label="敏感类型"><el-select v-model="form.sensitive_type" style="width:100%"><el-option v-for="t in ['个人信息','财务','医疗','证件','其他']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="级别"><el-select v-model="form.level" style="width:100%"><el-option v-for="l in ['敏感','机密']" :key="l" :label="l" :value="l" /></el-select></el-form-item>
        <el-form-item label="脱敏规则"><el-select v-model="form.mask_rule_id" clearable placeholder="关联脱敏规则" style="width:100%"><el-option v-for="r in maskRules" :key="r.id" :label="r.name" :value="r.id" /></el-select></el-form-item>
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
const rows = ref<any[]>([]); const loading = ref(false); const dlg = ref(false); const maskRules = ref<any[]>([])
const form = reactive<any>({ id: null, source_table: '', source_column: '', sensitive_type: '个人信息', level: '敏感', mask_rule_id: null, description: '' })
async function load() { loading.value = true; try { rows.value = await api.secSensitives(); maskRules.value = await api.secMaskRules() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, source_table: '', source_column: '', sensitive_type: '个人信息', level: '敏感', mask_rule_id: null, description: '' }, row || {}); dlg.value = true }
async function save() { if (!form.source_table || !form.source_column) return ElMessage.warning('填表与字段'); try { await api.secSaveSensitive({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm('删除该敏感字段登记？', '提示', { type: 'warning' }); try { await api.secDeleteSensitive(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
