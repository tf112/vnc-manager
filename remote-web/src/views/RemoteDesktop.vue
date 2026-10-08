<template>
  <div class="desktop-page">
    <!-- 顶部工具栏 -->
    <div class="toolbar">
      <div class="toolbar-left">
        <el-button @click="$router.back()" size="small">
          <el-icon><ArrowLeft /></el-icon> 返回
        </el-button>
        <h3 class="title">远程桌面 - {{ clientId }}</h3>
        <el-tag :type="connected ? 'success' : 'danger'" size="small" effect="dark">
          {{ connected ? '已连接' : '未连接' }}
        </el-tag>
      </div>
      <div class="toolbar-right">
        <el-button type="success" size="small" @click="sendScreenshot">截图</el-button>
        <el-button type="warning" size="small" @click="sendRecord">录屏</el-button>
        <el-button size="small" @click="reconnect">重新连接</el-button>
      </div>
    </div>

    <!-- noVNC 画布区域 -->
    <div ref="vncContainer" class="vnc-container">
      <div v-if="!connected" class="connecting-overlay">
        <el-icon :size="48"><Monitor /></el-icon>
        <p>正在连接远程桌面...</p>
        <p class="hint">请确保客户端在线且 VNC Server 已启动</p>
      </div>
      <div id="vnc-screen" class="vnc-screen"></div>
    </div>
  </div>
</template>

<script setup>
import { ref, onMounted, onUnmounted } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import { sendCommand, getClient } from '../api'
import RFB from '@novnc/novnc'

const route = useRoute()
const clientId = route.params.clientId
const connected = ref(false)
const vncContainer = ref(null)

let rfb = null

const connectVnc = async () => {
  const host = window.location.hostname
  const port = window.location.port || '8080'
  const wsUrl = `ws://${host}:${port}/vnc/proxy/${clientId}`

  // 读取该机器的 VNC 设置（密码 + 控制/浏览模式）
  let password = ''
  let viewOnly = false
  try {
    const res = await getClient(clientId)
    if (res.data.code === 200 && res.data.data) {
      const c = res.data.data
      password = c.vncPassword || ''
      viewOnly = c.vncMode === 'view'
    }
  } catch (e) {
    console.error('获取客户端设置失败，使用默认配置连接:', e)
  }

  try {
    const vncScreen = document.getElementById('vnc-screen')
    rfb = new RFB(vncScreen, wsUrl, {
      credentials: { password }
    })
    rfb.addEventListener('connect', () => {
      connected.value = true
      ElMessage.success(viewOnly ? '远程桌面已连接（浏览模式）' : '远程桌面已连接')
    })
    rfb.addEventListener('disconnect', () => {
      connected.value = false
      ElMessage.warning('远程桌面已断开')
    })
    rfb.viewOnly = viewOnly
    rfb.showDotCursor = true
    rfb.scaleViewport = true
    rfb.resizeSession = true
  } catch (e) {
    console.error('VNC 连接失败:', e)
    ElMessage.error('连接远程桌面失败')
  }
}

const reconnect = () => {
  if (rfb) {
    rfb.disconnect()
    rfb = null
  }
  connected.value = false
  setTimeout(connectVnc, 500)
}

const sendScreenshot = async () => {
  try {
    const res = await sendCommand({ clientId, cmd: 'screenshot', params: {} })
    if (res.data.code === 200) {
      ElMessage.success('截图指令已发送')
    } else {
      ElMessage.error(res.data.msg || '发送失败')
    }
  } catch (e) {
    ElMessage.error('发送失败')
  }
}

const sendRecord = async () => {
  try {
    const res = await sendCommand({
      clientId,
      cmd: 'start_record',
      params: { duration: 30, fps: 15 }
    })
    if (res.data.code === 200) {
      ElMessage.success('录屏指令已发送')
    } else {
      ElMessage.error(res.data.msg || '发送失败')
    }
  } catch (e) {
    ElMessage.error('发送失败')
  }
}

onMounted(connectVnc)

onUnmounted(() => {
  if (rfb) {
    rfb.disconnect()
    rfb = null
  }
})
</script>

<style scoped>
.desktop-page {
  height: 100vh;
  display: flex;
  flex-direction: column;
  background: #0f0f1a;
  padding: 16px 20px;
  box-sizing: border-box;
  color: rgba(255, 255, 255, 0.85);
}

.toolbar {
  display: flex;
  justify-content: space-between;
  align-items: center;
  margin-bottom: 12px;
  flex-shrink: 0;
}

.toolbar-left,
.toolbar-right {
  display: flex;
  align-items: center;
  gap: 10px;
}

.title {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}

.vnc-container {
  flex: 1;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
  position: relative;
}

.vnc-screen {
  width: 100%;
  height: 100%;
}

.connecting-overlay {
  position: absolute;
  top: 50%;
  left: 50%;
  transform: translate(-50%, -50%);
  color: #888;
  text-align: center;
  z-index: 1;
}

.connecting-overlay p {
  margin: 8px 0;
}

.hint {
  font-size: 12px;
}
</style>