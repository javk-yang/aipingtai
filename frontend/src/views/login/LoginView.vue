<script setup lang="ts">
/**
 * 登录页 —— P5 交付
 *
 * 设计要点（与后端 P3 认证模块对接）：
 * 1. 单字段 identifier 透传，后端智能识别 邮箱/手机/用户名（LoginRequest.identifier）
 * 2. 图形验证码"靠错误码触发"：连续失败 ≥3 次后端回 2008(CAPTCHA_ERROR)，
 *    前端才渲染图形码输入框并拉取。正常用户几乎不触发，无感的人机校验。
 * 3. 登录成功 → userStore.setLogin 存双 token + 拉 /me → 跳工作台（或 redirect）
 */
import { ref, computed, onMounted } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useThemeStore } from '@/stores/theme'
import { useUserStore } from '@/stores/user'
import { ApiError } from '@/utils/request'
import * as authApi from '@/api/auth'
import type { LoginRequest } from '@/types'
import AfCard from '@/components/card/AfCard.vue'
import AfButton from '@/components/button/AfButton.vue'
import AfIcon from '@/components/icon/AfIcon.vue'
import AfInput from '@/components/input/AfInput.vue'

const router = useRouter()
const route = useRoute()
const themeStore = useThemeStore()
const userStore = useUserStore()

const identifier = ref('')
const password = ref('')
const showPwd = ref(false)

/* 图形验证码（失败超限后由后端要求） */
const showCaptcha = ref(false)
const captchaId = ref('')
const captchaCode = ref('')
const captchaBase64 = ref('')
const captchaLoading = ref(false)

const loading = ref(false)
const errorMsg = ref('')

const canSubmit = computed(
  () =>
    !!identifier.value.trim() &&
    !!password.value &&
    (!showCaptcha.value || !!captchaCode.value.trim()),
)

/** 拉取一张图形验证码（captchaId 提交时带回，答案只在 Redis） */
async function loadCaptcha() {
  captchaLoading.value = true
  try {
    const resp = await authApi.getCaptchaImage()
    captchaId.value = resp.captchaId
    captchaBase64.value = resp.imageBase64
    captchaCode.value = ''
  } finally {
    captchaLoading.value = false
  }
}

async function onSubmit() {
  if (!canSubmit.value || loading.value) return
  loading.value = true
  errorMsg.value = ''
  const payload: LoginRequest = {
    identifier: identifier.value.trim(),
    password: password.value,
  }
  if (showCaptcha.value) {
    payload.captchaId = captchaId.value
    payload.captchaCode = captchaCode.value.trim()
  }
  try {
    const resp = await authApi.login(payload)
    await userStore.setLogin(resp)
    const redirect = (route.query.redirect as string) || '/workspace'
    router.replace(redirect)
  } catch (e) {
    const err = e as ApiError
    if (err.code === 2008) {
      // 后端要求图形验证码（连续失败 ≥3 次触发）
      if (!showCaptcha.value) showCaptcha.value = true
      await loadCaptcha()
      errorMsg.value = '请输入图形验证码'
    } else {
      errorMsg.value = err.message
    }
  } finally {
    loading.value = false
  }
}

onMounted(() => {
  if (userStore.isLoggedIn) router.replace('/workspace')
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

      <h2 class="auth-page__heading">登录</h2>

      <form class="auth-form" @submit.prevent="onSubmit">
        <AfInput
          v-model="identifier"
          label="账号"
          placeholder="用户名 / 邮箱 / 手机号"
          autocomplete="username"
          :disabled="loading"
          @enter="onSubmit"
        />

        <AfInput
          v-model="password"
          label="密码"
          :type="showPwd ? 'text' : 'password'"
          placeholder="请输入密码"
          autocomplete="current-password"
          :disabled="loading"
          :icon="showPwd ? 'eye-off' : 'eye'"
          @iconClick="showPwd = !showPwd"
          @enter="onSubmit"
        />

        <div v-if="showCaptcha" class="auth-form__captcha">
          <AfInput
            v-model="captchaCode"
            label="图形验证码"
            placeholder="请输入右侧字符"
            :disabled="loading"
            :maxlength="4"
            @enter="onSubmit"
          />
          <button
            type="button"
            class="auth-form__captcha-img"
            :disabled="captchaLoading"
            title="点击刷新"
            @click="loadCaptcha"
          >
            <img v-if="captchaBase64" :src="captchaBase64" alt="图形验证码" />
            <AfIcon v-else name="refresh" :size="18" />
          </button>
        </div>

        <p v-if="errorMsg" class="auth-form__error">{{ errorMsg }}</p>

        <AfButton type="submit" block :loading="loading" :disabled="!canSubmit">登录</AfButton>
      </form>

      <div class="auth-page__foot">
        <router-link to="/forgot-password" class="auth-link">忘记密码？</router-link>
        <span class="auth-page__sep">·</span>
        <router-link to="/register" class="auth-link">注册账号</router-link>
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
.auth-form__captcha {
  display: flex;
  gap: var(--space-2);
  align-items: flex-end;
}
.auth-form__captcha > :first-child {
  flex: 1;
}
.auth-form__captcha-img {
  width: 100px;
  height: var(--control-height);
  flex-shrink: 0;
  border: 1px solid var(--color-border);
  border-radius: var(--radius-sm);
  background-color: var(--color-surface-2);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  overflow: hidden;
  padding: 0;
}
.auth-form__captcha-img img {
  width: 100%;
  height: 100%;
  object-fit: cover;
  display: block;
}
.auth-form__captcha-img:disabled {
  opacity: 0.6;
  cursor: not-allowed;
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
  gap: var(--space-3);
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
.auth-page__sep {
  color: var(--color-text-tertiary);
}
.auth-page__copyright {
  margin-top: var(--space-6);
  font-size: var(--text-xs);
  color: var(--color-text-tertiary);
}
</style>
