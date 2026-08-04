import React, { useEffect } from 'react'
import StatusBadge from './StatusBadge'

function formatDate(dateStr) {
  if (!dateStr) return '—'
  try {
    return new Date(dateStr).toLocaleString()
  } catch {
    return dateStr
  }
}

function prettyJSON(val) {
  if (!val) return 'null'
  if (typeof val === 'string') {
    try {
      return JSON.stringify(JSON.parse(val), null, 2)
    } catch {
      return val
    }
  }
  try {
    return JSON.stringify(val, null, 2)
  } catch {
    return String(val)
  }
}

function DetailRow({ label, value, mono }) {
  return (
    <div className="flex flex-col sm:flex-row sm:items-start gap-1 sm:gap-3 py-2 border-b border-gray-100 last:border-0">
      <dt className="text-xs font-medium text-gray-500 uppercase tracking-wide sm:w-32 flex-shrink-0">{label}</dt>
      <dd className={`text-sm text-gray-800 break-all ${mono ? 'font-mono' : ''}`}>
        {value || '—'}
      </dd>
    </div>
  )
}

export default function JobDetailModal({ job, onClose }) {
  // Close on Escape key
  useEffect(() => {
    function handleKeyDown(e) {
      if (e.key === 'Escape') onClose()
    }
    document.addEventListener('keydown', handleKeyDown)
    // Prevent body scroll
    document.body.style.overflow = 'hidden'
    return () => {
      document.removeEventListener('keydown', handleKeyDown)
      document.body.style.overflow = ''
    }
  }, [onClose])

  if (!job) return null

  const payload = prettyJSON(job.payload)
  const statusHistory = job.statusHistory || job.status_history || []

  return (
    <div
      className="fixed inset-0 z-50 flex items-end sm:items-center justify-center"
      onClick={onClose}
    >
      {/* Backdrop */}
      <div className="absolute inset-0 bg-black/50 backdrop-blur-sm" />

      {/* Modal */}
      <div
        className="relative bg-white w-full sm:w-auto sm:min-w-[560px] sm:max-w-2xl sm:mx-4 sm:rounded-xl shadow-2xl max-h-screen sm:max-h-[90vh] overflow-hidden flex flex-col rounded-t-2xl"
        onClick={(e) => e.stopPropagation()}
      >
        {/* Header */}
        <div className="flex items-center justify-between px-5 py-4 border-b border-gray-200 flex-shrink-0">
          <div className="flex items-center gap-3 min-w-0">
            <h2 className="text-base font-semibold text-gray-900">Job Details</h2>
            <StatusBadge status={job.status} />
          </div>
          <button
            onClick={onClose}
            className="p-2 hover:bg-gray-100 rounded-lg transition-colors flex-shrink-0 min-h-[36px] min-w-[36px] flex items-center justify-center"
            aria-label="Close"
          >
            <svg className="w-5 h-5 text-gray-500" fill="none" viewBox="0 0 24 24" stroke="currentColor">
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M6 18L18 6M6 6l12 12" />
            </svg>
          </button>
        </div>

        {/* Scrollable content */}
        <div className="overflow-y-auto flex-1 px-5 py-4">
          <dl className="space-y-0">
            <DetailRow label="Job ID" value={job.id} mono />
            <DetailRow label="Type" value={job.type} />
            <DetailRow label="Status" value={job.status} />
            <DetailRow label="Tenant" value={job.tenantId || job.tenant_id} />
            <DetailRow label="Attempt" value={String(job.attemptCount ?? job.attempts ?? 0)} />
            <DetailRow label="Max Retries" value={String(job.maxRetries ?? job.max_retries ?? '—')} />
            <DetailRow label="Idempotency Key" value={job.idempotencyKey || job.idempotency_key} mono />
            <DetailRow label="Delay (ms)" value={String(job.delayMs ?? job.delay_ms ?? 0)} />
            <DetailRow label="Created" value={formatDate(job.createdAt || job.created_at)} />
            <DetailRow label="Updated" value={formatDate(job.updatedAt || job.updated_at)} />
            {(job.startedAt || job.started_at) && (
              <DetailRow label="Started" value={formatDate(job.startedAt || job.started_at)} />
            )}
            {(job.completedAt || job.completed_at) && (
              <DetailRow label="Completed" value={formatDate(job.completedAt || job.completed_at)} />
            )}
          </dl>

          {/* Payload */}
          <div className="mt-4">
            <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Payload</h3>
            <pre className="bg-gray-900 text-green-400 text-xs font-mono p-4 rounded-lg overflow-auto max-h-48 whitespace-pre-wrap break-all">
              {payload}
            </pre>
          </div>

          {/* Last Error */}
          {(job.lastError || job.error || job.errorMessage) && (
            <div className="mt-4">
              <h3 className="text-xs font-medium text-red-500 uppercase tracking-wide mb-2">Last Error</h3>
              <div className="bg-red-50 border border-red-200 rounded-lg p-3">
                <p className="text-sm text-red-700 font-mono break-all">{job.lastError || job.error || job.errorMessage}</p>
              </div>
            </div>
          )}

          {/* Status History */}
          {statusHistory.length > 0 && (
            <div className="mt-4">
              <h3 className="text-xs font-medium text-gray-500 uppercase tracking-wide mb-2">Status History</h3>
              <div className="space-y-2">
                {statusHistory.map((entry, i) => (
                  <div key={i} className="flex items-center gap-3 text-xs">
                    <span className="w-2 h-2 rounded-full bg-gray-400 flex-shrink-0" />
                    <StatusBadge status={entry.status} />
                    <span className="text-gray-500">{formatDate(entry.timestamp || entry.at)}</span>
                    {entry.message && <span className="text-gray-600 truncate">{entry.message}</span>}
                  </div>
                ))}
              </div>
            </div>
          )}
        </div>

        {/* Footer */}
        <div className="px-5 py-3 border-t border-gray-100 flex-shrink-0">
          <button
            onClick={onClose}
            className="w-full sm:w-auto px-4 py-2.5 bg-gray-100 hover:bg-gray-200 text-gray-700 text-sm font-medium rounded-lg transition-colors min-h-[44px]"
          >
            Close
          </button>
        </div>
      </div>
    </div>
  )
}
