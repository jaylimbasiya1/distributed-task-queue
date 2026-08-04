import React from 'react'

const STATUS_CONFIG = {
  PENDING: { bg: 'bg-yellow-100', text: 'text-yellow-800', dot: 'bg-yellow-400', label: 'Pending' },
  RUNNING: { bg: 'bg-blue-100', text: 'text-blue-800', dot: 'bg-blue-400', label: 'Running', pulse: true },
  COMPLETED: { bg: 'bg-green-100', text: 'text-green-800', dot: 'bg-green-400', label: 'Completed' },
  FAILED: { bg: 'bg-red-100', text: 'text-red-800', dot: 'bg-red-400', label: 'Failed' },
  DLQ: { bg: 'bg-purple-100', text: 'text-purple-800', dot: 'bg-purple-400', label: 'DLQ' },
  CANCELLED: { bg: 'bg-gray-100', text: 'text-gray-600', dot: 'bg-gray-400', label: 'Cancelled' },
  DEAD: { bg: 'bg-purple-100', text: 'text-purple-800', dot: 'bg-purple-400', label: 'Dead' }
}

export default function StatusBadge({ status }) {
  const config = STATUS_CONFIG[status] || STATUS_CONFIG.PENDING

  return (
    <span className={`inline-flex items-center gap-1 px-2 py-0.5 rounded-full text-xs font-medium ${config.bg} ${config.text}`}>
      <span className={`w-1.5 h-1.5 rounded-full ${config.dot} ${config.pulse ? 'pulse-dot' : ''}`} />
      {config.label}
    </span>
  )
}
