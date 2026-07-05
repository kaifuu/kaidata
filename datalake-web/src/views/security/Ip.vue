<template>
  <div class="dl-card">
    <div class="card-title"><span>黑白名单</span><span class="role-tag">系统管理员</span></div>
    <el-button size="small" type="primary" @click="open()" style="margin-bottom:10px"><el-icon><Plus /></el-icon> 新增</el-button>
    <el-table :data="rows" size="small" stripe border v-loading="loading">
      <el-table-column prop="ip" label="IP/CIDR" min-width="160" />
      <el-table-column label="类型" width="80"><template #default="{ row }"><el-tag size="small" :type="row.list_type === '黑' ? 'danger' : 'success'">{{ row.list_type }}名单</el-tag></template></el-table-column>
      <el-table-column prop="scope" label="范围" width="120" />
      <el-table-column prop="comment" label="备注" min-width="180" />
      <el-table-column label="操作" width="140"><template #default="{ row }"><el-button link size="small" type="primary" @click="open(row)">编辑</el-button><el-button link size="small" type="danger" @click="del(row)">删除</el-button></template></el-table-column>
    </el-table>
    <el-dialog v-model="dlg" :title="form.id ? '编辑' : '新增'" width="440px">
      <el-form :model="form" label-width="70px" size="small">
        <el-form-item label="IP"><el-input v-model="form.ip" placeholder="如 192.168.1.1 或 10.0.0.0/24" /></el-form-item>
        <el-form-item label="类型"><el-radio-group v-model="form.list_type"><el-radio value="黑">黑名单</el-radio><el-radio value="白">白名单</el-radio></el-radio-group></el-form-item>
        <el-form-item label="范围"><el-input v-model="form.scope" placeholder="如 登录/查询" /></el-form-item>
        <el-form-item label="备注"><el-input v-model="form.comment" /></el-form-item>
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
const rows = ref<any[]>([]); const loading = ref(false); const dlg = ref(false)
const form = reactive<any>({ id: null, ip: '', list_type: '黑', scope: '', comment: '' })
async function load() { loading.value = true; try { rows.value = await api.secIpList() } catch (e:any) { ElMessage.error(errMsg(e)) } finally { loading.value = false } }
function open(row?: any) { Object.assign(form, { id: null, ip: '', list_type: '黑', scope: '', comment: '' }, row || {}); dlg.value = true }
async function save() { if (!form.ip) return ElMessage.warning('填 IP'); try { await api.secSaveIp({ ...form }); ElMessage.success('保存成功'); dlg.value = false; await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
async function del(row: any) { await ElMessageBox.confirm(`删除 ${row.ip}？`, '提示', { type: 'warning' }); try { await api.secDeleteIp(row.id); ElMessage.success('已删除'); await load() } catch (e:any) { ElMessage.error(errMsg(e)) } }
onMounted(load)
</script>
<style scoped>.card-title{display:flex;align-items:center;justify-content:space-between;font-weight:600;margin-bottom:12px}.role-tag{font-size:12px;color:var(--tech-text-muted);border:1px solid var(--tech-panel-border);padding:2px 8px;border-radius:4px}</style>
