<template>
  <el-dialog v-model="visible" :title="$t('pwd.title')" width="420px" :close-on-click-modal="false">
    <el-form ref="formRef" :model="form" :rules="rules" label-width="82px">
      <el-form-item :label="$t('pwd.old')" prop="oldPassword">
        <el-input v-model="form.oldPassword" type="password" show-password :placeholder="$t('pwd.oldPh')" />
      </el-form-item>
      <el-form-item :label="$t('pwd.new')" prop="newPassword">
        <el-input v-model="form.newPassword" type="password" show-password :placeholder="$t('pwd.newPh')" />
      </el-form-item>
      <el-form-item :label="$t('pwd.confirm')" prop="confirm">
        <el-input v-model="form.confirm" type="password" show-password :placeholder="$t('pwd.confirmPh')" />
      </el-form-item>
    </el-form>
    <template #footer>
      <el-button @click="visible = false">{{ $t('common.cancel') }}</el-button>
      <el-button type="primary" :loading="saving" @click="submit">{{ $t('pwd.submit') }}</el-button>
    </template>
  </el-dialog>
</template>

<script setup lang="ts">
import { computed, reactive, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage, type FormInstance } from 'element-plus'
import { api, errMsg } from '@/api'
import { auth } from '@/auth'

const { t } = useI18n()
const router = useRouter()
const visible = ref(false)
const saving = ref(false)
const formRef = ref<FormInstance>()
const form = reactive({ oldPassword: '', newPassword: '', confirm: '' })

const rules = computed(() => ({
  oldPassword: [{ required: true, message: t('pwd.oldRequired'), trigger: 'blur' }],
  newPassword: [
    { required: true, message: t('pwd.newRequired'), trigger: 'blur' },
    { min: 6, message: t('pwd.newMin'), trigger: 'blur' }
  ],
  confirm: [
    { required: true, message: t('pwd.confirmRequired'), trigger: 'blur' },
    { validator: (_r: any, v: string, cb: any) => (v === form.newPassword ? cb() : cb(new Error(t('pwd.mismatch')))), trigger: 'blur' }
  ]
}))

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
      ElMessage.success(t('pwd.changed'))
      visible.value = false
      auth.clear()
      router.replace('/login')
    } catch (e) { ElMessage.error(errMsg(e, t('pwd.failed'))) } finally { saving.value = false }
  })
}
</script>
