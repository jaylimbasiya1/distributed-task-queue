import React, { useState, useEffect, useCallback } from 'react'
import { getDLQ, retryDLQ, purgeDLQ } from '../api/jobApi'
import StatusBadge from './StatusBadge'
import toast from 'react-hot-toast'

function formatDate(dateStr) {
  if (!dateStr) return '—'
  try {
    const d = new Date(dateStr)
    return d.toLocaleString(undefined, {
      month: 'short', day: 'numeric',
      hour: '2-digit', minute: '2-digit'
    })
  } catch {
    return dateStr
  }
}

function truncateId(id) {
  if (!id) return '—'
  return id.length > 16 ? id.substring(0, 8) + '...' + id.substring(id.length - 4) : id
}

function truncateError(err) {
  if (!err) return '—'
  return err.length > 60 ? err.substring(0, 60) + '...' : err
}

export default function DLQPanel({ tenantId, expanded, onToggle, refreshKey }) {
  const [dlqJobs, setDlqJobs] = useState([])
  const [loading, setLoading] = useState(false)
  const [error, setError] = useState(false)

  const fetchDLQ = useCallback(async () => {
    if (!tenantId) return
    setLoading(true)
    setError(false)
    try {
      const data = await getDLQ()
      const all = Array.isArray(data) ? data : data.content || data.jobs || data.data || []
      // Filter to current tenant only
      setDlqJobs(all.filter((j) => (j.tenantId || j.tenant_id) === tenantId))
    } catch (err) {
      console.warn('Failed to fetch DLQ:', err)
      setError(true)
    } finally {
      setLoading(false)
    }
  }, [tenantId])

  useEffect(() => {
    fetchDLQ()
  }, [fetchDLQ, refreshKey])

  async function handleRetry(id) {
    try {
      await retryDLQ(id)
      toast.success('Job requeued from DLQ')
      fetchDLQ()
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to retry DLQ job')
    }
  }

  async function handlePurge(id) {
    if (!window.confirm('Permanently delete this job from DLQ?')) return
    try {
      await purgeDLQ(id)
      toast.success('Job purged from DLQ')
      setDlqJobs((prev) => prev.filter((j) => j.id !== id))
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to purge DLQ job')
    }
  }

  const count = dlqJobs.length

  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
      {/* Header - clickable to expand/collapse */}
      <button
        onClick={onToggle}
        className="w-full flex items-center justify-between px-4 sm:px-6 py-4 hover:bg-gray-50 transition-colors"
      >
        <div className="flex items-center gap-3">
          <span className="text-base font-semibold text-gray-900">Dead Letter Queue</span>
          {count > 0 ? (
            <span className="text-xs px-2 py-0.5 rounded-full bg-red-100 text-red-700 font-semibold">{count}</span>
          ) : (
            <span className="text-xs px-2 py-0.5 rounded-full bg-gray-100 text-gray-500 font-semibold">{count}</span>
          )}
        </div>
        <svg
          className={`w-5 h-5 text-gray-400 transition-transform flex-shrink-0 ${expanded ? 'rotate-180' : ''}`}
          fill="none" viewBox="0 0 24 24" stroke="currentColor"
        >
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {/* Content */}
      {expanded && (
        <div className="border-t border-gray-100">
          {loading && (
            <div className="text-center py-8">
              <div className="inline-block w-5 h-5 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
            </div>
          )}

          {!loading && error && (
            <div className="text-center py-10 text-red-500 text-sm">
              Failed to load DLQ. <button onClick={fetchDLQ} className="underline">Retry</button>
            </div>
          )}

          {!loading && !error && count === 0 && (
            <div className="text-center py-10 text-gray-500 text-sm">
              No jobs in DLQ 🎉
            </div>
          )}

          {!loading && count > 0 && (
            <>
              {/* Desktop table */}
              <div className="hidden md:block overflow-x-auto">
                <table className="w-full text-sm">
                  <thead className="bg-gray-50 border-b border-gray-100">
                    <tr>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Job ID</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Type</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Tenant</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Attempts</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Last Error</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Failed At</th>
                      <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Actions</th>
                    </tr>
                  </thead>
                  <tbody className="divide-y divide-gray-100">
                    {dlqJobs.map((job) => (
                      <tr key={job.id} className="hover:bg-gray-50 transition-colors">
                        <td className="px-4 py-3">
                          <code className="text-xs font-mono text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded">
                            {truncateId(job.id)}
                          </code>
                        </td>
                        <td className="px-4 py-3 text-gray-700 font-medium max-w-[120px] truncate">{job.type || '—'}</td>
                        <td className="px-4 py-3 text-gray-500 text-xs">{job.tenantId || job.tenant_id || '—'}</td>
                        <td className="px-4 py-3 text-center text-gray-500">{job.totalAttempts ?? job.attemptCount ?? '—'}</td>
                        <td className="px-4 py-3 text-gray-500 text-xs max-w-[200px]">
                          <span title={job.lastError || job.error || ''}>{truncateError(job.lastError || job.error)}</span>
                        </td>
                        <td className="px-4 py-3 text-gray-500 text-xs whitespace-nowrap">{formatDate(job.failedAt || job.failed_at || job.updatedAt)}</td>
                        <td className="px-4 py-3">
                          <div className="flex items-center gap-2">
                            <button
                              onClick={() => handleRetry(job.id)}
                              className="px-2.5 py-1.5 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors min-h-[32px]"
                            >
                              Retry
                            </button>
                            <button
                              onClick={() => handlePurge(job.id)}
                              className="px-2.5 py-1.5 text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 rounded-md transition-colors min-h-[32px]"
                            >
                              Delete
                            </button>
                          </div>
                        </td>
                      </tr>
                    ))}
                  </tbody>
                </table>
              </div>

              {/* Mobile cards */}
              <div className="md:hidden divide-y divide-gray-100">
                {dlqJobs.map((job) => (
                  <div key={job.id} className="p-4">
                    <div className="flex items-start justify-between gap-3 mb-2">
                      <div className="min-w-0 flex-1">
                        <div className="flex items-center gap-2 mb-1">
                          <StatusBadge status="DLQ" />
                          <span className="text-sm font-medium text-gray-800 truncate">{job.type || '—'}</span>
                        </div>
                        <code className="text-xs font-mono text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded block w-fit">
                          {truncateId(job.id)}
                        </code>
                      </div>
                      <div className="flex items-center gap-1.5 flex-shrink-0">
                        <button
                          onClick={() => handleRetry(job.id)}
                          className="px-3 py-1.5 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors min-h-[36px]"
                        >
                          Retry
                        </button>
                        <button
                          onClick={() => handlePurge(job.id)}
                          className="px-3 py-1.5 text-xs font-medium text-red-600 bg-red-50 hover:bg-red-100 rounded-md transition-colors min-h-[36px]"
                        >
                          Del
                        </button>
                      </div>
                    </div>
                    {(job.lastError || job.error) && (
                      <p className="text-xs text-red-600 mt-1 line-clamp-2">{job.lastError || job.error}</p>
                    )}
                    <div className="flex items-center gap-3 text-xs text-gray-500 mt-1">
                      <span>{job.totalAttempts ?? job.attemptCount ?? 0} attempts</span>
                      <span>•</span>
                      <span>{formatDate(job.failedAt || job.failed_at || job.updatedAt)}</span>
                    </div>
                  </div>
                ))}
              </div>
            </>
          )}
        </div>
      )}
    </div>
  )
}
