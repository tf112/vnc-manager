<template>
  <div class="app">
    <!-- 顶部浮动式主菜单触发器 -->
    <FloatingMenu />

    <!-- 主内容区：直接渲染路由, 不使用 transition 包装 (修复 hydration 报错) -->
    <main class="main-content">
      <router-view :key="$route.fullPath" />
    </main>
  </div>
</template>

<script setup>
import FloatingMenu from './components/FloatingMenu.vue'
</script>

<style scoped>
.app {
  height: 100vh;
  overflow: hidden;
  background: #0f0f1a;
}

.main-content {
  height: 100vh;
  overflow: hidden;
}
</style>