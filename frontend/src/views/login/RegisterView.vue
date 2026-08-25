<script setup lang="ts">
/**
 * 注册页 —— P5 交付
 *
 * 设计要点（对接 P3 认证模块）：
 * 1. 邮箱/手机至少填一项（后端校验 + 唯一性），对应通道发验证码
 * 2. 前端密码规则预校验（8-64 含字母数字），但后端 @Valid 才是真相
 * 3. 发码 60s 冷却：后端 setIfAbsent 冷却 key，前端叠倒计时 UX
 * 4. 注册成功不返 token → 用刚填的密码自动 login，一次往返进入工作台
 */
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { useRouter } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import * as authApi from '@/api/auth'
import type { RegisterRequest } from '@/types'
import AfCard from '@/components/card/AfCard.vue'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfInput from '@/components/input/AfInput.vue'

const router = useRouter()
const themeStore = useThemeStore()
const userStore = useUserStore()

const username = ref('')
const password = ref('')
const confirmPwd = ref('')
const email = ref('')
const phone = ref('')
const code = ref('')
const showPwd = ref(false)
const showConfirm = ref(false)

const loading = ref(false)
const sending = ref(false)
const cooldown = ref(0)
const errorMsg = ref('')
let timer: number | null = null

const pwdRule = /^(?=.*[A-Za-z])(?=.*\d).{8,64}$/
const emailRule = /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/
const phoneRule = /^1\d{10}$/

const usernameError = computed(() =>
  username.value && (username.value.length < 3 || username.value.length > 32)
    ? '用户名需 3-32 个字符'
    : '',
)
const pwdError = computed(() =>
  password.value && !pwdRule.test(password.value) ? '密码需 8-64 位，且包含字母和数字' : '',
)
const confirmError = computed(() =>
  confirmPwd.value && confirmPwd.value !== password.value ? '两次密码不一致' : '',
)
const emailError = computed(() =>
  email.value && !emailRule.test(email.value) ? '邮箱格式不正确' : '',
)
const phoneError = computed(() =>
  phone.value && !phoneRule.test(phone.value) ? '手机号格式不正确' : '',
)

const canSend = computed(() => {
  if (sending.value || cooldown.value > 0) return false
  return emailRule.test(email.value) || phoneRule.test(phone.value)
})
const canSubmit = computed(
  () =>
    !!username.value.trim() &&
    pwdRule.test(password.value) &&
    password.value === confirmPwd.value &&
    (emailRule.test(email.value) || phoneRule.test(phone.value)) &&
    !!code.value.trim() &&
    !usernameError.value &&
    !emailError.value &&
    !phoneError.value,
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
    if (emailRule.test(email.value)) {
      await authApi.sendEmailCode({ scene: 'register', email: email.value.trim() })
    } else {
      await authApi.sendSmsCode({ scene: 'register', phone: phone.value.trim() })
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
  const payload: RegisterRequest = {
    username: username.value.trim(),
    password: password.value,
  }
  if (emailRule.test(email.value)) {
    payload.email = email.value.trim()
    payload.emailCode = code.value.trim()
  } else {
    payload.phone = phone.value.trim()
    payload.phoneCode = code.value.trim()
  }
  try {
    await authApi.register(payload)
    // 注册成功不返 token → 自动登录，一次往返进入工作台
    const resp = await authApi.login({
      identifier: username.value.trim(),
      password: password.value,
    })
    await userStore.setLogin(resp)
    router.replace('/workspace')
  } catch (e) {
    errorMsg.value = (e as ApiError).message
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (userStore.isLoggedIn) router.replace('/workspace')
})
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

      <h2 class="auth-page__heading">注册账号</h2>

      <form class="auth-form" @submit.prevent="onSubmit">
        <AfInput
          v-model="username"
          label="用户名"
          placeholder="3-32 个字符"
          autocomplete="username"
          :error="usernameError"
          :disabled="loading"
        />

        <AfInput
          v-model="password"
          label="密码"
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
          label="确认密码"
          :type="showConfirm ? 'text' : 'password'"
          placeholder="再次输入密码"
          autocomplete="new-password"
          :error="confirmError"
          :disabled="loading"
          :icon="showConfirm ? 'eye-off' : 'eye'"
          @iconClick="showConfirm = !showConfirm"
        />

        <AfInput
          v-model="email"
          label="邮箱（用于找回密码，选填）"
          placeholder="you@example.com"
          :error="emailError"
          :disabled="loading"
        />

        <AfInput
          v-model="phone"
          label="手机号（用于找回密码，选填）"
          placeholder="11 位手机号"
          :error="phoneError"
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

        <p v-if="errorMsg" class="auth-form__error">{{ errorMsg }}</p>

        <AfButton type="submit" block :loading="loading" :disabled="!canSubmit">注册</AfButton>
      </form>

      <div class="auth-page__foot">
        <span class="auth-page__hint">已有账号？</span>
        <router-link to="/login" class="auth-link">去登录</router-link>
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
  gap: var(--space-2);
  margin-top: var(--space-5);
  font-size: var(--text-sm);
}
.auth-page__hint {
  color: var(--color-text-tertiary);
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
</style>
