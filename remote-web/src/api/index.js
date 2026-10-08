import axios from 'axios'

const api = axios.create({
  baseURL: '/api/v1',
  timeout: 10000
})

// 客户端相关
export const getClients = (params) => api.get('/client/list', { params })
export const getClient = (clientId) => api.get(`/client/${clientId}`)
export const addClient = (data) => api.post('/client/add', data)
export const deleteClient = (id) => api.delete(`/client/${id}`)
export const updateClientSettings = (clientId, data) => api.put(`/client/${clientId}/settings`, data)

// 指令下发
export const sendCommand = (data) => api.post('/command/send', data)

// 文件管理
export const getFiles = (params) => api.get('/file/list', { params })
export const getFilesByClient = (clientId) => api.get(`/file/list/${clientId}`)
export const deleteFile = (id) => api.delete(`/file/${id}`)
export const fileDownloadUrl = (id) => `/api/v1/file/download/${id}`

export default api
