<script setup lang="ts">
/** 403 —— 无权限访问（路由守卫 meta.perm 拦截后跳转） */
import { useRouter } from 'vue-router'
import AfButton from '@/components/button/AfButton.vue'

const router = useRouter()
</script>

<template>
  <div class="forbidden">
    <p class="forbidden__code">403</p>
    <h1 class="forbidden__title">无权访问该页面</h1>
    <p class="forbidden__desc">你的账号缺少访问此功能所需的权限，请联系管理员开通。</p>
    <AfButton @click="router.push('/workspace')">返回工作台</AfButton>
  </div>
</template>

<style scoped>
.forbidden {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: var(--space-3);
  background-color: var(--color-bg);
}
.forbidden__code {
  font-family: var(--font-mono);
  font-size: 56px;
  font-weight: var(--weight-semibold);
  color: var(--color-text-tertiary);
  letter-spacing: var(--tracking-tight);
}
.forbidden__title {
  font-size: var(--text-lg);
  font-weight: var(--weight-semibold);
}
.forbidden__desc {
  font-size: var(--text-base);
  color: var(--color-text-secondary);
  margin-bottom: var(--space-4);
}
</style>
