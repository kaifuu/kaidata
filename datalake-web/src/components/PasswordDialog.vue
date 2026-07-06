<template>
  <el-dialog v-model="visible" title="修改密码" width="420px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="82px">
      <el-form-item label="原密码" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password placeholder="请输入原密码" />
      </el-form-item>
      <el-form-item label="新密码" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password placeholder="至少 6 位" />
      </el-form-item>
      <el-form-item label="确认密码" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password placeholder="再次输入新密码" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">取消</el-button>
      <el-button type="primary" :loading="saving" @click="submit">确认修改</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { ElMessage, type FormInstance } from 'element-plus'
import { api, errMsg } from '@/api'
import { auth } from '@/auth'

const router = useRouter()
const visible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const rules = {
  oldPassword: [{ required: true, message: '请输入原密码', trigger: 'blur' }],
  newPassword: [
    { required: true, message: '请输入新密码', trigger: 'blur' },
    { min: 6, message: '至少 6 位', trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: '请再次输入', trigger: 'blur' },
    { validator: (_r: any, v: string, cb: any) => (v === form.newPassword ? cb() : cb(new Error('两次密码不一致'))), trigger: 'blur' }
  ]
}

function open() {
  form.oldPassword = ''; form.newPassword = ''; form.confirm = ''
  visible.value = true
}
defineExpose({ open })

async function submit() {
  if (!formRef.value) return
  await formRef.value.validate(async (ok) => {
    if (!ok) return
    saving.value = true
    try {
      await api.authChangePassword(form.oldPassword, form.newPassword)
      ElMessage.success('密码已修改，请重新登录')
      visible.value = false
      auth.clear()
      router.replace('/login')
    } catch (e) { ElMessage.error(errMsg(e, '修改失败')) } finally { saving.value = false }
  })
}
</script>
