import React, { useRef } from 'react'
import StatusBadge from './StatusBadge'

const TABS = [
  { key: 'PENDING',   label: 'Pending',   color: 'yellow' },
  { key: 'RUNNING',   label: 'Running',   color: 'blue' },
  { key: 'COMPLETED', label: 'Completed', color: 'green' },
  { key: 'FAILED',    label: 'Failed',    color: 'red' },
  { key: 'CANCELLED', label: 'Cancelled', color: 'gray' },
]

const TAB_BADGE_COLORS = {
  PENDING:   'bg-yellow-100 text-yellow-700',
  RUNNING:   'bg-blue-100 text-blue-700',
  COMPLETED: 'bg-green-100 text-green-700',
  FAILED:    'bg-red-100 text-red-700',
  CANCELLED: 'bg-gray-100 text-gray-600',
}

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

function EmptyState({ tab }) {
  const icons = { PENDING: '⏳', RUNNING: '⚙️', COMPLETED: '✅', FAILED: '❌', CANCELLED: '🚫' }
  return (
    <div className="text-center py-12 px-4">
      <div className="text-4xl mb-3">{icons[tab] || '📭'}</div>
      <p className="text-gray-500 text-sm">No jobs in this queue</p>
    </div>
  )
}

export default function JobTable({ jobs, loading, onCancel, onRetry, activeTab, onTabChange, onJobClick, updatedJobsRef }) {
  const currentJobs = jobs?.[activeTab] || []

  function isUpdated(jobId) {
    return updatedJobsRef?.current?.has(jobId)
  }

  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-sm overflow-hidden">
      {/* Tabs */}
      <div className="border-b border-gray-200 overflow-x-auto">
        <div className="flex min-w-max sm:min-w-0">
          {TABS.map((tab) => {
            const count = jobs?.[tab.key]?.length ?? 0
            const isActive = activeTab === tab.key
            return (
              <button
                key={tab.key}
                onClick={() => onTabChange(tab.key)}
                className={`flex items-center gap-2 px-4 sm:px-5 py-3.5 text-sm font-medium transition-colors whitespace-nowrap min-h-[48px] border-b-2 ${
                  isActive
                    ? 'border-blue-500 text-blue-600 bg-blue-50/50'
                    : 'border-transparent text-gray-500 hover:text-gray-700 hover:bg-gray-50'
                }`}
              >
                {tab.label}
                <span className={`text-xs px-1.5 py-0.5 rounded-full font-semibold ${
                  isActive ? TAB_BADGE_COLORS[tab.key] : 'bg-gray-100 text-gray-500'
                }`}>
                  {count}
                </span>
              </button>
            )
          })}
        </div>
      </div>

      {/* Loading state */}
      {loading && (
        <div className="text-center py-8">
          <div className="inline-block w-6 h-6 border-2 border-blue-500 border-t-transparent rounded-full animate-spin" />
          <p className="text-gray-500 text-sm mt-2">Loading jobs...</p>
        </div>
      )}

      {/* Empty state */}
      {!loading && currentJobs.length === 0 && <EmptyState tab={activeTab} />}

      {/* Desktop table */}
      {!loading && currentJobs.length > 0 && (
        <>
          <div className="hidden md:block overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="bg-gray-50 border-b border-gray-200">
                <tr>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Job ID</th>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Type</th>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Status</th>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Attempt</th>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Created</th>
                  <th className="text-left px-4 py-3 font-medium text-gray-600 text-xs uppercase tracking-wide">Actions</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-gray-100">
                {currentJobs.map((job) => (
                  <tr
                    key={job.id}
                    onClick={() => onJobClick?.(job)}
                    className={`hover:bg-gray-50 cursor-pointer transition-colors ${isUpdated(job.id) ? 'job-updated' : ''}`}
                  >
                    <td className="px-4 py-3">
                      <code className="text-xs font-mono text-gray-600 bg-gray-100 px-1.5 py-0.5 rounded">
                        {truncateId(job.id)}
                      </code>
                    </td>
                    <td className="px-4 py-3 text-gray-700 font-medium max-w-[150px] truncate">{job.type || '—'}</td>
                    <td className="px-4 py-3"><StatusBadge status={job.status} /></td>
                    <td className="px-4 py-3 text-gray-500 text-center">{job.attemptCount ?? job.attempts ?? 0}</td>
                    <td className="px-4 py-3 text-gray-500 text-xs whitespace-nowrap">{formatDate(job.createdAt || job.created_at)}</td>
                    <td className="px-4 py-3" onClick={(e) => e.stopPropagation()}>
                      <div className="flex items-center gap-2">
                        {(job.status === 'PENDING') && (
                          <button
                            onClick={() => onCancel?.(job.id)}
                            className="px-2.5 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors min-h-[32px]"
                          >
                            Cancel
                          </button>
                        )}
                        {(job.status === 'FAILED' || job.status === 'CANCELLED') && (
                          <button
                            onClick={() => onRetry?.(job.id)}
                            className="px-2.5 py-1.5 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors min-h-[32px]"
                          >
                            Retry
                          </button>
                        )}
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          {/* Mobile cards */}
          <div className="md:hidden divide-y divide-gray-100">
            {currentJobs.map((job) => (
              <div
                key={job.id}
                onClick={() => onJobClick?.(job)}
                className={`p-4 hover:bg-gray-50 cursor-pointer transition-colors ${isUpdated(job.id) ? 'job-updated' : ''}`}
              >
                <div className="flex items-start justify-between gap-3 mb-2">
                  <div className="flex items-center gap-2 min-w-0 flex-1">
                    <StatusBadge status={job.status} />
                    <span className="text-sm font-medium text-gray-800 truncate">{job.type || '—'}</span>
                  </div>
                  <div className="flex items-center gap-1.5 flex-shrink-0" onClick={(e) => e.stopPropagation()}>
                    {job.status === 'PENDING' && (
                      <button
                        onClick={() => onCancel?.(job.id)}
                        className="px-3 py-1.5 text-xs font-medium text-gray-600 bg-gray-100 hover:bg-gray-200 rounded-md transition-colors min-h-[36px]"
                      >
                        Cancel
                      </button>
                    )}
                    {(job.status === 'FAILED' || job.status === 'CANCELLED') && (
                      <button
                        onClick={() => onRetry?.(job.id)}
                        className="px-3 py-1.5 text-xs font-medium text-blue-600 bg-blue-50 hover:bg-blue-100 rounded-md transition-colors min-h-[36px]"
                      >
                        Retry
                      </button>
                    )}
                  </div>
                </div>
                <code className="text-xs font-mono text-gray-400 bg-gray-100 px-1.5 py-0.5 rounded block w-fit mb-2">
                  {truncateId(job.id)}
                </code>
                <div className="flex items-center gap-3 text-xs text-gray-500">
                  <span>Attempt {job.attemptCount ?? job.attempts ?? 0}</span>
                  <span>•</span>
                  <span>{formatDate(job.createdAt || job.created_at)}</span>
                </div>
              </div>
            ))}
          </div>
        </>
      )}
    </div>
  )
}
