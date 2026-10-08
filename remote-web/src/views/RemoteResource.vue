<template>
  <div class="resource-page">
    <!-- 左侧面板 -->
    <aside class="left-panel">
      <div class="panel-header">
        <span class="panel-title">资源列表</span>
      </div>

      <!-- 搜索栏 -->
      <div class="panel-search">
        <el-input
          v-model="searchText"
          placeholder="搜索名称 / IP..."
          clearable
          size="small"
          prefix-icon="Search"
          @clear="resetPage"
          @input="resetPage"
        />
      </div>

      <div class="resource-list" v-loading="loading" element-loading-background="rgba(15,15,26,0.6)">
        <div
          v-for="res in pagedResources"
          :key="res.id"
          class="resource-item"
          :class="{ selected: isSelected(res.clientId) }"
          draggable="true"
          @dragstart="onDragStart($event, res)"
          @click="toggleCheck(res)"
        >
          <el-checkbox
            :model-value="isSelected(res.clientId)"
            @change="toggleCheck(res)"
            @click.stop
          />
          <div class="resource-info">
            <div class="resource-name">
              <span class="status-dot" :class="res.status" />
              <span class="res-name-text">{{ res.name }}</span>
            </div>
            <div class="resource-ip">{{ res.ip }}</div>
          </div>
          <el-button
            class="res-settings"
            circle
            size="small"
            type="info"
            plain
            @click.stop="openSettings(res)"
            title="VNC 设置"
          >
            <el-icon><Setting /></el-icon>
          </el-button>
        </div>

        <el-empty
          v-if="pagedResources.length === 0"
          :image-size="60"
          description="无匹配资源"
        />
      </div>

      <!-- 分页 -->
      <div class="panel-pagination">
        <el-pagination
          v-model:current-page="currentPage"
          size="small"
          background
          layout="prev, pager, next"
          :total="totalResources"
          :page-size="pageSize"
          hide-on-single-page
        />
      </div>
    </aside>

    <!-- 右侧主区域 -->
    <main class="right-area">
      <!-- 右上角操作栏 -->
      <div class="top-bar">
        <div class="layout-buttons">
          <span class="layout-label">布局：</span>
          <el-button-group>
            <el-button
              v-for="n in [1, 4, 9, 16]"
              :key="n"
              :type="gridSize === n ? 'primary' : ''"
              size="small"
              @click="setLayout(n)"
            >{{ n }}屏</el-button>
          </el-button-group>
        </div>
        <el-tag size="small" type="info" effect="plain">
          已连接 {{ selected.length }} / {{ gridSize }}
        </el-tag>
      </div>

      <!-- Grid 网格：内嵌实时远程画面 -->
      <div class="grid-container" :class="`grid-size-${gridSize}`">
        <template v-for="(view, idx) in views" :key="view ? view.clientId : 'empty-' + idx">
          <MonitCard
            v-if="view"
            :client-id="view.clientId"
            :name="view.name"
            :ip="view.ip"
            :active="view.clientId === activeClientId"
            class="grid-slot-cell"
            @activate="onActivate"
            @close="closeCell"
          />
          <div
            v-else
            class="grid-slot-cell empty-cell"
            @dragover.prevent="hoverSlotIdx = idx"
            @drop.prevent="onDropCell($event, idx)"
          >
            <el-icon :size="22"><Plus /></el-icon>
            <span>拖拽资源至此</span>
          </div>
        </template>
      </div>
    </main>

    <!-- VNC 设置对话框 -->
    <el-dialog
      v-model="settingsVisible"
      title="VNC 设置"
      width="460px"
      class="settings-dialog"
    >
      <el-form label-width="90px" label-position="left">
        <el-form-item label="客户端">
          <span class="settings-client">{{ settingsClientId }}</span>
        </el-form-item>
        <el-form-item label="VNC 密码">
          <el-input
            v-model="settingsForm.vncPassword"
            type="password"
            show-password
            placeholder="远程桌面 VNC 密码（留空表示无密码）"
            clearable
          />
        </el-form-item>
        <el-form-item label="连接模式">
          <el-radio-group v-model="settingsForm.vncMode">
            <el-radio value="control">远程控制</el-radio>
            <el-radio value="view">只查看（不操作）</el-radio>
          </el-radio-group>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="settingsVisible = false">取消</el-button>
        <el-button type="primary" :loading="settingsLoading" @click="saveSettings">
          保存
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, computed, watch, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import { getClients, getClient, updateClientSettings } from '../api'
import MonitCard from '../components/MonitCard.vue'

// ─── 搜索 + 分页 ───
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(5)
const loading = ref(false)
const resources = ref([])
const totalResources = ref(0)

const pagedResources = computed(() => resources.value)

async function loadResources() {
  loading.value = true
  try {
    const res = await getClients({
      keyword: searchText.value || undefined,
      page: currentPage.value,
      size: pageSize.value
    })
    if (res.data.code === 200) {
      const data = res.data.data
      resources.value = (data.list || []).map(c => ({
        id: c.id,
        name: c.hostname || c.clientId,
        ip: c.ipAddress,
        status: c.status === 1 ? 'online' : 'offline',
        clientId: c.clientId
      }))
      totalResources.value = data.total || 0
    }
  } catch (e) {
    resources.value = []
    totalResources.value = 0
  } finally {
    loading.value = false
  }
}

function resetPage() { currentPage.value = 1 }

watch(searchText, resetPage)
watch([searchText, currentPage], loadResources)
onMounted(loadResources)

// ─── 多资源监测墙 ───
const gridSize = ref(4)
const selected = ref([]) // 有序资源对象数组
const activeClientId = ref(null) // 唯一的控制端
const hoverSlotIdx = ref(null)

// 保证任意时刻只有一个控制端
watch(selected, (list) => {
  if (!activeClientId.value || !list.some(x => x.clientId === activeClientId.value)) {
    activeClientId.value = list.length ? list[0].clientId : null
  }
}, { immediate: true })

// 当前布局对应的格子内容
const views = computed(() => {
  const arr = Array.from({ length: gridSize.value }, () => null)
  selected.value.slice(0, gridSize.value).forEach((r, i) => { arr[i] = r })
  return arr
})

function setLayout(n) {
  gridSize.value = n
}

function isSelected(clientId) {
  return selected.value.some(x => x.clientId === clientId)
}

function toggleCheck(res) {
  const i = selected.value.findIndex(x => x.clientId === res.clientId)
  if (i >= 0) {
    selected.value.splice(i, 1)
  } else {
    selected.value.push({ id: res.id, clientId: res.clientId, name: res.name, ip: res.ip })
  }
}

// 点击某卡片 → 切换为该资源为控制端，其余自动变为只查看
function onActivate(clientId) {
  if (selected.value.some(x => x.clientId === clientId)) {
    activeClientId.value = clientId
  }
}

// 关闭某卡片 → 移出监测墙
function closeCell(clientId) {
  const i = selected.value.findIndex(x => x.clientId === clientId)
  if (i >= 0) selected.value.splice(i, 1)
}

// ─── 拖拽到指定格子 ───
let dragSourceId = ref(null)

function onDragStart(e, res) {
  dragSourceId.value = res.clientId
  e.dataTransfer.effectAllowed = 'move'
}

function onDropCell(e, index) {
  e.preventDefault()
  hoverSlotIdx.value = null
  const res = resources.value.find(r => r.clientId === dragSourceId.value)
  if (!res) return

  const obj = { id: res.id, clientId: res.clientId, name: res.name, ip: res.ip }
  const cur = selected.value.findIndex(x => x.clientId === res.clientId)
  if (cur >= 0) selected.value.splice(cur, 1)
  const i = Math.min(index, selected.value.length)
  selected.value.splice(i, 0, obj)
  activeClientId.value = obj.clientId
  dragSourceId.value = null
}

// ─── VNC 设置 ───
const settingsVisible = ref(false)
const settingsClientId = ref('')
const settingsLoading = ref(false)
const settingsForm = ref({ vncPassword: '', vncMode: 'control' })

async function openSettings(res) {
  settingsClientId.value = res.clientId
  settingsForm.value = { vncPassword: '', vncMode: 'control' }
  settingsVisible.value = true
  try {
    const r = await getClient(res.clientId)
    if (r.data.code === 200 && r.data.data) {
      const c = r.data.data
      settingsForm.value = {
        vncPassword: c.vncPassword || '',
        vncMode: c.vncMode || 'control'
      }
    }
  } catch (e) { /* 忽略 */ }
}

async function saveSettings() {
  settingsLoading.value = true
  try {
    await updateClientSettings(settingsClientId.value, {
      vncPassword: settingsForm.value.vncPassword,
      vncMode: settingsForm.value.vncMode
    })
    settingsVisible.value = false
    ElMessage.success('VNC 设置已保存')
  } catch (e) {
    ElMessage.error('保存失败')
  } finally {
    settingsLoading.value = false
  }
}
</script>

<style scoped>
.resource-page {
  display: flex;
  height: 100vh;
  overflow: hidden;
  background: #0f0f1a;
}

/* ─── 左侧面板 ─── */
.left-panel {
  width: 240px;
  flex-shrink: 0;
  background: rgba(255, 255, 255, 0.04);
  border-right: 1px solid rgba(255, 255, 255, 0.08);
  display: flex;
  flex-direction: column;
  overflow: hidden;
}

.panel-header {
  padding: 16px 16px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.08);
  flex-shrink: 0;
}

.panel-title {
  font-size: 14px;
  font-weight: 600;
  color: rgba(255, 255, 255, 0.85);
  letter-spacing: 0.5px;
}

.panel-search {
  padding: 10px 12px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.panel-search :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: none;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.panel-search :deep(.el-input__wrapper.is-focus) {
  border-color: rgba(64, 158, 255, 0.5);
}

.panel-search :deep(.el-input__inner) {
  color: rgba(255, 255, 255, 0.85);
}

.panel-search :deep(.el-input__inner::placeholder) {
  color: rgba(255, 255, 255, 0.3);
}

.resource-list {
  flex: 1;
  overflow-y: auto;
  padding: 8px;
}

.resource-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 10px 12px;
  border-radius: 8px;
  cursor: grab;
  transition: background 0.15s;
  margin-bottom: 4px;
}

.resource-item:hover {
  background: rgba(255, 255, 255, 0.08);
}

.resource-item.selected {
  background: rgba(64, 158, 255, 0.15);
}

.resource-item:active {
  cursor: grabbing;
}

.res-settings {
  flex-shrink: 0;
}

.res-settings:hover {
  color: #409eff;
  border-color: rgba(64, 158, 255, 0.6);
}

.resource-info {
  flex: 1;
  min-width: 0;
}

.resource-name {
  display: flex;
  align-items: center;
  gap: 6px;
  font-size: 13px;
  font-weight: 500;
  color: rgba(255, 255, 255, 0.9);
  overflow: hidden;
}

.res-name-text {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
  flex: 1;
}

.status-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
  flex-shrink: 0;
  background: #909399;
}

.status-dot.online {
  background: #34c759;
  box-shadow: 0 0 6px rgba(52, 199, 89, 0.8);
}

.status-dot.offline {
  background: #ff4d4f;
  box-shadow: 0 0 6px rgba(255, 77, 79, 0.7);
}

.resource-ip {
  font-size: 11px;
  color: rgba(255, 255, 255, 0.4);
  margin-top: 2px;
}

.panel-pagination {
  padding: 8px 10px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  display: flex;
  justify-content: center;
  flex-shrink: 0;
}

.panel-pagination :deep(.el-pagination.is-background .el-pager li) {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
}

.panel-pagination :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: #409eff;
  color: #fff;
}

.panel-pagination :deep(.el-pagination button) {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
}

/* ─── 右侧区域 ─── */
.right-area {
  flex: 1;
  display: flex;
  flex-direction: column;
  overflow: hidden;
  background: #0f0f1a;
}

.top-bar {
  display: flex;
  align-items: center;
  justify-content: flex-end;
  gap: 12px;
  padding: 14px 20px;
  flex-shrink: 0;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
}

.layout-buttons {
  display: flex;
  align-items: center;
  gap: 8px;
}

.layout-label {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.5);
}

/* ─── Grid 容器 ─── */
.grid-container {
  flex: 1;
  padding: 16px;
  display: grid;
  gap: 12px;
  overflow: hidden;
}

.grid-size-1 {
  grid-template-columns: 1fr;
  grid-template-rows: 1fr;
}

.grid-size-4 {
  grid-template-columns: 1fr 1fr;
  grid-template-rows: 1fr 1fr;
}

.grid-size-9 {
  grid-template-columns: 1fr 1fr 1fr;
  grid-template-rows: 1fr 1fr 1fr;
}

.grid-size-16 {
  grid-template-columns: repeat(4, 1fr);
  grid-template-rows: repeat(4, 1fr);
}

.grid-slot-cell {
  min-height: 0;
  min-width: 0;
}

.empty-cell {
  border-radius: 10px;
  border: 1px dashed rgba(255, 255, 255, 0.12);
  background: rgba(255, 255, 255, 0.03);
  display: flex;
  flex-direction: column;
  align-items: center;
  justify-content: center;
  gap: 8px;
  color: rgba(255, 255, 255, 0.25);
  font-size: 12px;
  transition: all 0.2s;
}

.empty-cell:hover {
  border-color: #409eff;
  background: rgba(64, 158, 255, 0.08);
  color: rgba(64, 158, 255, 0.7);
}

/* ─── VNC 设置对话框 ─── */
.settings-dialog :deep(.el-dialog) {
  background: #1a1a2e;
  border: 1px solid rgba(255, 255, 255, 0.08);
  border-radius: 12px;
}

.settings-dialog :deep(.el-dialog__title) {
  color: rgba(255, 255, 255, 0.85);
}

.settings-dialog :deep(.el-form-item__label) {
  color: rgba(255, 255, 255, 0.6);
}

.settings-dialog :deep(.el-radio__label) {
  color: rgba(255, 255, 255, 0.8);
}

.settings-dialog :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.06);
  box-shadow: none;
  border: 1px solid rgba(255, 255, 255, 0.08);
}

.settings-dialog :deep(.el-input__inner) {
  color: rgba(255, 255, 255, 0.85);
}

.settings-client {
  color: rgba(255, 255, 255, 0.85);
  font-weight: 500;
}
</style>