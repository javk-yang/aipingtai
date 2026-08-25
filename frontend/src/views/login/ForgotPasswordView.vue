<script setup lang="ts">
/**
 * 找回密码页 —— P5 交付
 *
 * 设计要点（对接 P3.2 密码重置流程）：
 * 1. 两步流：发码(scene=reset) → 验证码 + 新密码同提交重置
 * 2. 账号类型后端同规则识别（含 @ → 邮箱，11 位 1 开头 → 手机）
 * 3. 重置成功 → 后端吊销该用户全部 refresh token（PasswordService.revokeAllSessions）
 *    → 前端 clearTokens 同步清本地 → 跳登录页重新登录（更安全明确的 UX）
 */
import { ref, computed, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import { ApiError, clearTokens } from '@/utils/request'
import * as authApi from '@/api/auth'
import type { ResetPasswordRequest } from '@/types'
import AfCard from '@/components/card/AfCard.vue'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfInput from '@/components/input/AfInput.vue'

const router = useRouter()
const themeStore = useThemeStore()

const account = ref('')
const code = ref('')
const newPwd = ref('')
const confirmPwd = ref('')
const showPwd = ref(false)
const showConfirm = ref(false)

const loading = ref(false)
const sending = ref(false)
const cooldown = ref(0)
const errorMsg = ref('')
const done = ref(false)
let timer: number | null = null

const emailRule = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const phoneRule = /^1\d{10}$/
const pwdRule = /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/

const isEmail = computed(() => emailRule.test(account.value.trim()))
const accountError = computed(() =>
  account.value && !(emailRule.test(account.value.trim()) || phoneRule.test(account.value.trim()))
    ? '请输入有效的邮箱或手机号'
    : '',
)
const pwdError = computed(() =>
  newPwd.value && !pwdRule.test(newPwd.value) ? '密码需 8-64 位，且包含字母和数字' : '',
)
const confirmError = computed(() =>
  confirmPwd.value && confirmPwd.value !== newPwd.value ? '两次密码不一致' : '',
)

const canSend = computed(() => {
  if (sending.value || cooldown.value > 0) return false
  return emailRule.test(account.value.trim()) || phoneRule.test(account.value.trim())
})
const canSubmit = computed(
  () =>
    !!account.value.trim() &&
    !!code.value.trim() &&
    pwdRule.test(newPwd.value) &&
    newPwd.value === confirmPwd.value &&
    !accountError.value,
)

function startCooldown() {
  cooldown.value = 60
  timer = window.setInterval(() => {
    cooldown.value -= 1
    if (cooldown.value <= 0 && timer) {
      clearInterval(timer)
      timer = null
    }
  }, 1000)
}

async function sendCode() {
  if (!canSend.value) return
  sending.value = true
  errorMsg.value = ''
  try {
    if (isEmail.value) {
      await authApi.sendEmailCode({ scene: 'reset', email: account.value.trim() })
    } else {
      await authApi.sendSmsCode({ scene: 'reset', phone: account.value.trim() })
    }
    startCooldown()
  } catch (e) {
    errorMsg.value = (e as ApiError).message
  } finally {
    sending.value = false
  }
}

async function onSubmit() {
  if (!canSubmit.value || loading.value) return
  loading.value = true
  errorMsg.value = ''
  const payload: ResetPasswordRequest = {
    account: account.value.trim(),
    code: code.value.trim(),
    newPassword: newPwd.value,
  }
  try {
    await authApi.resetPassword(payload)
    // 后端已吊销该用户全部 refresh token → 前端同步清本地
    clearTokens()
    done.value = true
  } catch (e) {
    errorMsg.value = (e as ApiError).message
  } finally {
    loading.value = false
  }
}

onUnmounted(() => {
  if (timer) clearInterval(timer)
})
</script>

<template>
  <div class="auth-page">
    <div class="auth-page__theme">
      <AfButton variant="ghost" size="sm" @click="themeStore.toggle()">
        <AfIcon :name="themeStore.theme === 'light' ? 'moon' : 'sun'" :size="14" />
        {{ themeStore.theme === 'light' ? '深色' : '浅色' }}
      </AfButton>
    </div>

    <AfCard class="auth-page__card" padding="lg">
      <div class="auth-page__brand">
        <span class="auth-page__logo"><AfIcon name="spark" :size="20" :stroke-width="1.5" /></span>
        <h1 class="auth-page__title">AgentForge</h1>
        <p class="auth-page__sub">企业级 AI Agent 平台</p>
      </div>

      <h2 class="auth-page__heading">找回密码</h2>

      <div v-if="done" class="auth-done">
        <span class="auth-done__icon"><AfIcon name="check" :size="28" :stroke-width="1.5" /></span>
        <p class="auth-done__text">密码已重置成功，请使用新密码重新登录。</p>
        <AfButton block @click="router.push('/login')">前往登录</AfButton>
      </div>

      <form v-else class="auth-form" @submit.prevent="onSubmit">
        <AfInput
          v-model="account"
          label="账号"
          placeholder="注册时使用的邮箱或手机号"
          autocomplete="username"
          :error="accountError"
          :disabled="loading"
        />

        <div class="auth-form__row">
          <AfInput
            v-model="code"
            label="验证码"
            placeholder="6 位验证码"
            :disabled="loading"
            :maxlength="6"
          />
          <AfButton
            class="auth-form__code-btn"
            :disabled="!canSend"
            :loading="sending"
            @click="sendCode"
          >
            {{ cooldown > 0 ? `${cooldown}s 后重发` : '获取验证码' }}
          </AfButton>
        </div>

        <AfInput
          v-model="newPwd"
          label="新密码"
          :type="showPwd ? 'text' : 'password'"
          placeholder="8-64 位，含字母和数字"
          autocomplete="new-password"
          :error="pwdError"
          :disabled="loading"
          :icon="showPwd ? 'eye-off' : 'eye'"
          @iconClick="showPwd = !showPwd"
        />

        <AfInput
          v-model="confirmPwd"
          label="确认新密码"
          :type="showConfirm ? 'text' : 'password'"
          placeholder="再次输入"
          autocomplete="new-password"
          :error="confirmError"
          :disabled="loading"
          :icon="showConfirm ? 'eye-off' : 'eye'"
          @iconClick="showConfirm = !showConfirm"
        />

        <p v-if="errorMsg" class="auth-form__error">{{ errorMsg }}</p>

        <AfButton type="submit" block :loading="loading" :disabled="!canSubmit">重置密码</AfButton>
      </form>

      <div class="auth-page__foot">
        <router-link to="/login" class="auth-link">返回登录</router-link>
      </div>
    </AfCard>

    <p class="auth-page__copyright">© 2026 AgentForge · 企业级 AI Agent 平台</p>
  </div>
</template>

<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  position: relative;
  padding: var(--space-6) var(--space-4);
}
.auth-page__theme {
  position: absolute;
  top: var(--space-4);
  right: var(--space-4);
}
.auth-page__card {
  width: 400px;
  max-width: 100%;
}
.auth-page__brand {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-2);
  margin-bottom: var(--space-6);
}
.auth-page__logo {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 40px;
  height: 40px;
  border-radius: var(--radius-md);
  background-color: var(--color-primary);
  color: var(--color-on-primary);
}
.auth-page__title {
  font-size: var(--text-xl);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
}
.auth-page__sub {
  font-size: var(--text-sm);
  color: var(--color-text-tertiary);
}
.auth-page__heading {
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
  letter-spacing: var(--tracking-tight);
  margin-bottom: var(--space-5);
}
.auth-form {
  display: flex;
  flex-direction: column;
  gap: var(--space-4);
}
.auth-form__row {
  display: flex;
  gap: var(--space-2);
  align-items: flex-end;
}
.auth-form__row > :first-child {
  flex: 1;
}
.auth-form__code-btn {
  flex-shrink: 0;
  height: var(--control-height);
}
.auth-form__error {
  font-size: var(--text-sm);
  color: var(--color-danger);
  margin: calc(-1 * var(--space-1)) 0 0;
}
.auth-page__foot {
  display: flex;
  align-items: center;
  justify-content: center;
  margin-top: var(--space-5);
  font-size: var(--text-sm);
}
.auth-link {
  color: var(--color-text-secondary);
  text-decoration: none;
  transition: color var(--transition-fast);
}
.auth-link:hover {
  color: var(--color-text);
}
.auth-page__copyright {
  margin-top: var(--space-6);
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
.auth-done {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: var(--space-4);
  text-align: center;
}
.auth-done__icon {
  display: flex;
  align-items: center;
  justify-content: center;
  width: 48px;
  height: 48px;
  border-radius: 50%;
  background-color: var(--color-surface-2);
  color: var(--color-text);
}
.auth-done__text {
  font-size: var(--text-sm);
  color: var(--color-text-secondary);
  margin: 0;
}
</style>
