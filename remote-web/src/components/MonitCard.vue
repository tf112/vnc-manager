<template>
  <div
    class="monit-card"
    :class="{ active, 'is-viewonly': effectiveViewOnly }"
    @click="onActivate"
    title="点击切换为可控制"
  >
    <!-- noVNC 画布 -->
    <div ref="screenEl" class="monit-screen"></div>

    <!-- 底部信息栏 -->
    <div class="monit-overlay">
      <div class="monit-info">
        <span class="monit-dot" :class="connected ? 'online' : 'offline'" />
        <span class="monit-name">{{ name }}</span>
        <span class="monit-ip">{{ ip }}</span>
      </div>
      <div class="monit-actions">
        <span v-if="effectiveViewOnly" class="monit-tag view">只查看</span>
        <span v-else-if="!forceViewOnly" class="monit-tag control">控制中</span>
        <div class="monit-btn" @click.stop="$emit('close', clientId)" title="关闭">
          <el-icon><Close /></el-icon>
        </div>
      </div>
    </div>

    <!-- 连接中 -->
    <div v-if="!connected" class="monit-connecting">
      <el-icon :size="26" class="is-loading"><Loading /></el-icon>
      <span>连接中...</span>
    </div>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { getClient } from '../api'
import RFB from '@novnc/novnc'

const props = defineProps({
  clientId: { type: String, required: true },
  name: { type: String, default: '' },
  ip: { type: String, default: '' },
  active: { type: Boolean, default: false }
})
const emit = defineEmits(['activate', 'close'])

const screenEl = ref(null)
const connected = ref(false)
const forceViewOnly = ref(false) // 该资源在服务端配置为「只查看」
let rfb = null

// 是否只查看：服务端配置只查 或 不是当前控制端
const effectiveViewOnly = computed(() => forceViewOnly.value || !props.active)

function onActivate() {
  emit('activate', props.clientId)
}

function applyViewMode() {
  if (rfb && rfb.rfbState && (rfb.rfbState === 'normal' || rfb.rfbState === 'security_ok')) {
    rfb.viewOnly = effectiveViewOnly.value
  }
}

async function connect() {
  const host = window.location.hostname
  const port = window.location.port || '8080'
  const wsUrl = `ws://${host}:${port}/vnc/proxy/${props.clientId}`

  let password = ''
  try {
    const res = await getClient(props.clientId)
    if (res.data.code === 200 && res.data.data) {
      const c = res.data.data
      password = c.vncPassword || ''
      forceViewOnly.value = c.vncMode === 'view'
    }
  } catch (e) {
    console.error('获取客户端设置失败:', e)
  }

  try {
    rfb = new RFB(screenEl.value, wsUrl, { credentials: { password } })
    rfb.addEventListener('connect', () => {
      connected.value = true
      applyViewMode()
    })
    rfb.addEventListener('disconnect', () => {
      connected.value = false
    })
    rfb.showDotCursor = true
    rfb.scaleViewport = true
    rfb.resizeSession = true
    applyViewMode()
  } catch (e) {
    console.error('VNC 连接失败:', props.clientId, e)
  }
}

watch(effectiveViewOnly, () => {
    if (rfb && rfb.rfbState && (rfb.rfbState === 'normal' || rfb.rfbState === 'security_ok')) {
      rfb.viewOnly = effectiveViewOnly.value
    }
  })

onMounted(connect)

onBeforeUnmount(() => {
  if (rfb) {
    rfb.disconnect()
    rfb = null
  }
})
</script>

<style scoped>
.monit-card {
  width: 100%;
  height: 100%;
  position: relative;
  background: #000;
  border-radius: 10px;
  overflow: hidden;
  cursor: pointer;
  border: 1px solid rgba(255, 255, 255, 0.06);
  box-sizing: border-box;
  transition: border-color 0.2s;
}

.monit-card.is-viewonly {
  border-color: rgba(139, 147, 158, 0.35);
}

.monit-card.active {
  border-color: #409eff;
}

.monit-screen {
  width: 100%;
  height: 100%;
}

.monit-connecting {
  position: absolute;
  inset: 0;
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.4);
  font-size: 12px;
  background: #0c0c16;
}

.monit-overlay {
  position: absolute;
  left: 0;
  right: 0;
  bottom: 0;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  padding: 4px 8px;
  background: rgba(0, 0, 0, 0.55);
  backdrop-filter: blur(4px);
  font-size: 12px;
}

.monit-info {
  display: flex;
  align-items: center;
  gap: 6px;
  min-width: 0;
}

.monit-dot {
  width: 7px;
  height: 7px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #888;
}

.monit-dot.online {
  background: #34c759;
  box-shadow: 0 0 5px rgba(52, 199, 89, 0.8);
}

.monit-dot.offline {
  background: #ff5b5b;
  box-shadow: 0 0 5px rgba(255, 91, 91, 0.7);
}

.monit-name {
  color: rgba(255, 255, 255, 0.9);
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.monit-ip {
  color: rgba(255, 255, 255, 0.4);
  font-size: 10px;
}

.monit-actions {
  display: flex;
  align-items: center;
  gap: 6px;
  flex-shrink: 0;
}

.monit-tag {
  font-size: 10px;
  padding: 1px 7px;
  border-radius: 9px;
  line-height: 16px;
}

.monit-tag.control {
  background: rgba(64, 158, 255, 0.25);
  color: #6cb6ff;
}

.monit-tag.view {
  background: rgba(139, 147, 158, 0.25);
  color: #b0b6c0;
}

.monit-btn {
  width: 20px;
  height: 20px;
  border-radius: 50%;
  background: rgba(0, 0, 0, 0.5);
  display: flex;
  align-items: center;
  justify-content: center;
  cursor: pointer;
  color: rgba(255, 255, 255, 0.65);
}

.monit-btn:hover {
  background: rgba(245, 108, 108, 0.8);
  color: #fff;
}
</style>