<template>
  <div class="floating-menu" @mouseenter="showMenu = true" @mouseleave="showMenu = false">
    <!-- 透明向下小图标触发器 -->
    <div class="floating-trigger">
      <el-icon :size="18"><ArrowDown /></el-icon>
    </div>

    <transition name="menu-fade">
      <div v-show="showMenu" class="floating-dropdown" @mouseenter="showMenu = true" @mouseleave="showMenu = false">
        <div
          class="menu-item"
          :class="{ active: activeMenu === 'resource' }"
          @click="navigate('resource')"
        >
          <el-icon><Monitor /></el-icon>
          <span>远程资源管理</span>
        </div>
        <div
          class="menu-item"
          :class="{ active: activeMenu === 'file' }"
          @click="navigate('file')"
        >
          <el-icon><FolderOpened /></el-icon>
          <span>文件管理</span>
        </div>
      </div>
    </transition>
  </div>
</template>

<script setup>
import { ref, watch } from 'vue'
import { useRouter, useRoute } from 'vue-router'

const router = useRouter()
const route = useRoute()

const showMenu = ref(false)
const activeMenu = ref('resource')

watch(() => route.path, (path) => {
  if (path === '/files') {
    activeMenu.value = 'file'
  } else {
    activeMenu.value = 'resource'
  }
}, { immediate: true })

const navigate = (menu) => {
  showMenu.value = false
  if (menu === 'resource') {
    router.push('/')
  } else {
    router.push('/files')
  }
}
</script>

<style scoped>
/* 触发器: 固定在页面顶部居中, 透明向下小图标 */
.floating-menu {
  position: fixed;
  top: 0;
  left: 50%;
  transform: translateX(-50%);
  z-index: 9999;
  padding-top: 14px;
}

.floating-trigger {
  width: 44px;
  height: 22px;
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.5);
  background: transparent;
  border-radius: 0 0 12px 12px;
  transition: all 0.25s ease;
}

.floating-trigger:hover {
  color: rgba(255, 255, 255, 0.95);
  background: rgba(255, 255, 255, 0.06);
}

.floating-trigger:hover .el-icon {
  animation: bounce 0.9s infinite;
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(4px); }
}

/* 下拉菜单 */
.floating-dropdown {
  position: absolute;
  top: 100%;
  left: 50%;
  transform: translateX(-50%);
  min-width: 180px;
  background: rgba(22, 22, 34, 0.92);
  backdrop-filter: blur(18px);
  -webkit-backdrop-filter: blur(18px);
  border: 1px solid rgba(255, 255, 255, 0.1);
  border-radius: 12px;
  padding: 8px;
  box-shadow: 0 12px 40px rgba(0, 0, 0, 0.5);
}

.menu-item {
  display: flex;
  align-items: center;
  gap: 10px;
  padding: 10px 14px;
  border-radius: 8px;
  color: rgba(255, 255, 255, 0.75);
  font-size: 14px;
  cursor: pointer;
  transition: all 0.15s ease;
  white-space: nowrap;
}

.menu-item:hover {
  background: rgba(255, 255, 255, 0.1);
  color: #fff;
}

.menu-item.active {
  background: rgba(64, 158, 255, 0.2);
  color: #409eff;
}

.menu-item .el-icon {
  font-size: 16px;
}

.menu-fade-enter-active,
.menu-fade-leave-active {
  transition: opacity 0.2s ease, transform 0.2s ease;
}

.menu-fade-enter-from,
.menu-fade-leave-to {
  opacity: 0;
  transform: translateX(-50%) translateY(-6px);
}
</style>