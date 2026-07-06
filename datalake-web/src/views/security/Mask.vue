<template>
  <div class="dl-card">
    <div class="card-title"><span>数据脱敏</span><span class="role-tag">系统管理员</span></div>
    <el-tabs v-model="tab">
      <el-tab-pane label="脱敏规则" name="rule">
        <el-button size="small" type="primary" @click="openRule()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增规则</el-button>
        <el-table :data="rules" size="small" stripe border v-loading="loading">
          <el-table-column prop="name" label="名称" width="120" />
          <el-table-column prop="mask_type" label="类型" width="90"><template #default="{ row }"><el-tag size="small">{{ row.mask_type }}</el-tag></template></el-table-column>
          <el-table-column prop="pattern" label="正则" min-width="180" />
          <el-table-column prop="replacement" label="替换" width="120" />
          <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="openRule(row)">编辑</el-button><el-button link size="small" type="danger" @click="delRule(row)">删除</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
      <el-tab-pane label="字段绑定" name="rel">
        <el-button size="small" type="primary" @click="relDlg = true" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 绑定字段</el-button>
        <el-table :data="rels" size="small" stripe border>
          <el-table-column prop="rule_name" label="规则" width="120" />
          <el-table-column label="字段" min-width="240"><template #default="{ row }">{{ row.source_table }}.{{ row.source_column }}</template></el-table-column>
          <el-table-column label="操作" width="80"><template #default="{ row }"><el-button link size="small" type="danger" @click="unbind(row)">解绑</el-button></template></el-table-column>
        </el-table>
      </el-tab-pane>
    </el-tabs>

    <el-dialog v-model="ruleDlg" :title="ruleForm.id ? '编辑规则' : '新增规则'" width="520px">
      <el-form :model="ruleForm" label-width="70px" size="small">
        <el-form-item label="名称"><el-input v-model="ruleForm.name" /></el-form-item>
        <el-form-item label="类型"><el-select v-model="ruleForm.mask_type" style="width:100%"><el-option v-for="t in ['PHONE','IDCARD','EMAIL','CUSTOM']" :key="t" :label="t" :value="t" /></el-select></el-form-item>
        <el-form-item label="正则"><el-input v-model="ruleForm.pattern" placeholder='(\d{3})\d{4}(\d{4})' /></el-form-item>
        <el-form-item label="替换"><el-input v-model="ruleForm.replacement" placeholder="$1****$2" /></el-form-item>
        <el-form-item label="说明"><el-input v-model="ruleForm.description" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="ruleDlg = false">取消</el-button><el-button type="primary" @click="saveRule">保存</el-button></template>
    </el-dialog>

    <el-dialog v-model="relDlg" title="绑定字段" width="460px">
      <el-form :model="relForm" label-width="70px" size="small">
        <el-form-item label="规则"><el-select v-model="relForm.rule_id" style="width:100%"><el-option v-for="r in rules" :key="r.id" :label="r.name" :value="r.id" /></el-select></el-form-item>
        <el-form-item label="表"><el-input v-model="relForm.source_table" placeholder="ods.your_table" /></el-form-item>
        <el-form-item label="字段"><el-input v-model="relForm.source_column" placeholder="phone" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="relDlg = false">取消</el-button><el-button type="primary" @click="bind">绑定</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Plus } from '@element-plus/icons-vue'
import { api, errMsg } from '@/api'
const tab = ref('rule')
const rules = ref<any[]>([]); const rels = ref<any[]>([]); const loading = ref(false)
const ruleDlg = ref(false); const ruleForm = reactive<any>({ id: null, name: '', mask_type: 'PHONE', pattern: '', replacement: '', description: '' })
const relDlg = ref(false); const relForm = reactive<any>({ rule_id: null, source_table: '', source_column: '' })
async function load() { loading.value = true; try { rules.value = await api.secMaskRules(); rels.value = await api.secMaskRels() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function openRule(row?: any) { Object.assign(ruleForm, { id: null, name: '', mask_type: 'PHONE', pattern: '', replacement: '', description: '' }, row || {}); ruleDlg.value = true }
async function saveRule() { try { await api.secSaveMaskRule({ ...ruleForm }); ElMessage.success('保存成功'); ruleDlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function delRule(row: any) { await ElMessageBox.confirm(`删除规则 ${row.name}？`, '提示', { type: 'warning' }); try { await api.secDeleteMaskRule(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function bind() { if (!relForm.rule_id || !relForm.source_table) return ElMessage.warning('填规则与字段'); try { await api.secBindMask({ ...relForm }); ElMessage.success('已绑定'); relDlg.value = false; Object.assign(relForm, { rule_id: null, source_table: '', source_column: '' }); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function unbind(row: any) { try { await api.secUnbindMask(row.id); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
