<template>
  <div class="file-manager">
    <!-- 顶部搜索栏 -->
    <div class="search-bar">
      <el-input
        v-model="searchText"
        placeholder="搜索文件名..."
        clearable
        prefix-icon="Search"
        style="width: 280px;"
        @clear="filterFiles()"
        @keydown.enter="filterFiles()"
      />
      <el-button type="primary" @click="filterFiles" :loading="loading">查询</el-button>
      <el-button @click="resetFilter">重置</el-button>
    </div>

    <!-- 数据表格 (外层容器控制左右间距 + 暗色主题) -->
    <div class="table-wrap">
      <el-table
        :data="fileList"
        class="dark-table"
        v-loading="loading"
      >
      <el-table-column prop="fileName" label="文件名" min-width="200">
        <template #default="{ row }">
          <span class="file-name">{{ row.fileName }}</span>
        </template>
      </el-table-column>

      <el-table-column prop="clientId" label="客户端" width="140">
        <template #default="{ row }">
          <span class="text-muted">{{ row.clientId || '-' }}</span>
        </template>
      </el-table-column>

      <el-table-column prop="fileType" label="类型" width="90">
        <template #default="{ row }">
          <el-tag
            size="small"
            effect="dark"
            style="background: rgba(96,165,250,0.15); color: #60a5fa; border-color: rgba(96,165,250,0.3);"
          >
            {{ row.fileType === 1 ? '图片' : '视频' }}
          </el-tag>
        </template>
      </el-table-column>

      <el-table-column prop="fileSize" label="大小" width="100">
        <template #default="{ row }">
          {{ formatFileSize(row.fileSize) }}
        </template>
      </el-table-column>

      <el-table-column prop="createdAt" label="上传时间" width="170">
        <template #default="{ row }">
          <span class="text-muted">{{ formatTime(row.createdAt) }}</span>
        </template>
      </el-table-column>

      <el-table-column label="操作" width="200" fixed="right">
        <template #default="{ row }">
          <el-button type="primary" link size="small" @click="previewFile(row)">
            <el-icon><View /></el-icon> 查看
          </el-button>
          <el-button type="success" link size="small" @click="downloadFile(row)">
            <el-icon><Download /></el-icon> 下载
          </el-button>
          <el-button type="danger" link size="small" @click="handleDelete(row)">
            <el-icon><Delete /></el-icon> 删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>

    <el-empty v-if="!loading && fileList.length === 0" description="暂无文件" />
    </div>

    <!-- 分页 -->
    <div class="pagination-bar">
      <el-pagination
        v-model:current-page="currentPage"
        size="small"
        background
        layout="total, prev, pager, next"
        :total="totalFiles"
        :page-size="pageSize"
      />
    </div>

    <!-- 预览对话框 -->
    <el-dialog
      v-model="previewVisible"
      :title="previewFileItem?.fileName"
      width="70vw"
      top="5vh"
      class="preview-dialog"
    >
      <div class="preview-body">
        <img
          v-if="previewFileItem?.fileType === 1"
          :src="previewSrc()"
          class="preview-image"
          alt="预览"
        />
        <video v-else controls class="preview-video" preload="metadata">
          <source :src="previewSrc()" />
        </video>
      </div>
      <template #footer>
        <el-button @click="previewVisible = false">关闭</el-button>
        <el-button type="primary" @click="downloadFile(previewFileItem)">
          <el-icon><Download /></el-icon> 下载
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { ref, watch, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getFiles, deleteFile, fileDownloadUrl } from '../api'

const loading = ref(false)
const searchText = ref('')
const currentPage = ref(1)
const pageSize = ref(10)
const fileList = ref([])
const totalFiles = ref(0)

const previewVisible = ref(false)
const previewFileItem = ref(null)

function filterFiles() {
  if (currentPage.value !== 1) {
    currentPage.value = 1
  } else {
    loadFiles()
  }
}

function resetFilter() {
  searchText.value = ''
  currentPage.value = 1
  loadFiles()
}

async function loadFiles() {
  loading.value = true
  try {
    const res = await getFiles({
      keyword: searchText.value || undefined,
      page: currentPage.value,
      size: pageSize.value
    })
    if (res.data.code === 200) {
      const data = res.data.data
      fileList.value = data.list || []
      totalFiles.value = data.total || 0
    }
  } catch (e) {
    fileList.value = []
    totalFiles.value = 0
  } finally {
    loading.value = false
  }
}

// 翻页时重新加载
watch(currentPage, loadFiles)

function previewFile(row) {
  previewFileItem.value = row
  previewVisible.value = true
}

function previewSrc() {
  if (!previewFileItem.value) return ''
  return fileDownloadUrl(previewFileItem.value.id)
}

function downloadFile(row) {
  if (!row || !row.id) return
  window.open(fileDownloadUrl(row.id), '_blank')
}

async function handleDelete(row) {
  try {
    await ElMessageBox.confirm(`确定删除文件 "${row.fileName}" 吗？`, '确认', { type: 'warning' })
    await deleteFile(row.id)
    ElMessage.success('删除成功')
    if (fileList.value.length === 1 && currentPage.value > 1) {
      currentPage.value -= 1
    } else {
      loadFiles()
    }
  } catch (e) {
    // 取消则忽略
  }
}

function formatFileSize(bytes) {
  if (!bytes) return '0 B'
  const units = ['B', 'KB', 'MB', 'GB']
  let i = 0, size = bytes
  while (size >= 1024 && i < units.length - 1) { size /= 1024; i++ }
  return size.toFixed(1) + ' ' + units[i]
}

function pad(n) {
  return String(n).padStart(2, '0')
}

function formatTime(t) {
  if (!t) return '-'
  if (Array.isArray(t)) {
    const [y, m, d, h, min, s] = t
    return `${y}-${pad(m)}-${pad(d)} ${pad(h)}:${pad(min)}:${pad(s || 0)}`
  }
  return String(t).replace('T', ' ').substring(0, 19)
}

onMounted(loadFiles)
</script>

<style scoped>
.file-manager {
  display: flex;
  flex-direction: column;
  height: 100vh;
  background: #0f0f1a;
  color: rgba(255, 255, 255, 0.85);
  overflow: hidden;
}

.search-bar {
  display: flex;
  align-items: center;
  gap: 12px;
  padding: 16px 24px;
  border-bottom: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.text-muted {
  color: rgba(255, 255, 255, 0.35);
  font-size: 13px;
}

.file-name {
  font-size: 13px;
  color: rgba(255, 255, 255, 0.82);
}

.pagination-bar {
  display: flex;
  justify-content: flex-end;
  padding: 12px 24px;
  border-top: 1px solid rgba(255, 255, 255, 0.06);
  flex-shrink: 0;
}

.table-wrap {
  flex: 1;
  overflow: hidden;
  padding: 0 24px;
  display: flex;
}

/* 暗色表格：通过 Element Plus CSS 变量整体生效，覆盖表头/行/固定列/文字 */
:deep(.el-table.dark-table) {
  --el-table-bg-color: transparent;
  --el-table-tr-bg-color: transparent;
  --el-table-header-bg-color: #1e293b;
  --el-table-header-text-color: #a7b0be;
  --el-table-text-color: rgba(255, 255, 255, 0.82);
  --el-table-border-color: rgba(255, 255, 255, 0.08);
  --el-table-row-hover-bg-color: rgba(255, 255, 255, 0.06);
  --el-table-current-row-bg-color: rgba(64, 158, 255, 0.12);
  --el-table-fixed-column-bg-color: #16161f;
  background: transparent;
}

:deep(.el-table.dark-table .el-table__header th.el-table__cell) {
  background: #1e293b;
  color: #a7b0be;
  font-weight: 600;
}

:deep(.el-table.dark-table .el-table__body td.el-table__cell) {
  border-bottom: 1px solid rgba(255, 255, 255, 0.05);
}

:deep(.el-table.dark-table .el-table-fixed-column--right) {
  background: #16161f;
}

.pagination-bar :deep(.el-pagination.is-background .el-pager li),
.pagination-bar :deep(.el-pagination.is-background .el-pagination__total),
.pagination-bar :deep(.el-pagination button) {
  background: rgba(255, 255, 255, 0.08);
  color: rgba(255, 255, 255, 0.6);
}

.pagination-bar :deep(.el-pagination.is-background .el-pager li.is-active) {
  background: #409eff;
  color: #fff;
}

/* 预览对话框 */
.preview-body {
  display: flex;
  justify-content: center;
  align-items: center;
  min-height: 300px;
  background: #000;
  border-radius: 8px;
  overflow: hidden;
}

.preview-image {
  max-width: 100%;
  max-height: 70vh;
  object-fit: contain;
}

.preview-video {
  max-width: 100%;
  max-height: 70vh;
}

.preview-dialog :deep(.el-dialog) {
  background: #1a1a2e !important;
  border: 1px solid rgba(255,255,255,0.08) !important;
}

.preview-dialog :deep(.el-dialog__header) {
  border-bottom: 1px solid rgba(255,255,255,0.06);
  color: rgba(255,255,255,0.85);
}

.preview-dialog :deep(.el-dialog__title) {
  color: rgba(255,255,255,0.85);
}
</style>