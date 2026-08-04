import axios from 'axios'

const api = axios.create({
  baseURL: '',
  headers: { 'Content-Type': 'application/json' }
})

export function setTenantApiKey(key) {
  api.defaults.headers.common['X-API-Key'] = key
}

export async function submitJob(data) {
  const response = await api.post('/api/v1/jobs', data)
  return response.data
}

export async function getJobs(status, page = 0, size = 20) {
  const params = { page, size }
  if (status) params.status = status
  const response = await api.get('/api/v1/jobs', { params })
  return response.data
}

export async function cancelJob(id) {
  const response = await api.delete(`/api/v1/jobs/${id}`)
  return response.data
}

export async function retryJob(id) {
  const response = await api.post(`/api/v1/jobs/${id}/retry`)
  return response.data
}

export async function getDLQ() {
  const response = await api.get('/api/v1/dlq')
  return response.data
}

export async function retryDLQ(id) {
  const response = await api.post(`/api/v1/dlq/${id}/retry`)
  return response.data
}

export async function purgeDLQ(id) {
  const response = await api.delete(`/api/v1/dlq/${id}`)
  return response.data
}

export async function getTenantStats(tenantId) {
  const response = await api.get(`/api/v1/tenants/${tenantId}/stats`)
  return response.data
}

export default api
