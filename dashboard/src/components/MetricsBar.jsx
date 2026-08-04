import React, { useState, useEffect, useCallback } from 'react'
import { getTenantStats } from '../api/jobApi'

function MetricCard({ icon, value, label, color }) {
  return (
    <div className="bg-white rounded-xl border border-gray-200 p-3 sm:p-4 flex items-center gap-3 shadow-sm hover:shadow-md transition-shadow">
      <div className={`flex-shrink-0 w-10 h-10 sm:w-12 sm:h-12 rounded-lg flex items-center justify-center text-lg sm:text-xl ${color}`}>
        {icon}
      </div>
      <div className="min-w-0">
        <div className="text-xl sm:text-2xl font-bold text-gray-900 tabular-nums leading-tight">
          {value !== null && value !== undefined ? Number(value).toLocaleString() : '—'}
        </div>
        <div className="text-xs sm:text-sm text-gray-500 truncate">{label}</div>
      </div>
    </div>
  )
}

export default function MetricsBar({ tenantId, jobs }) {
  const [stats, setStats] = useState(null)

  const fetchStats = useCallback(async () => {
    if (!tenantId) return
    try {
      const data = await getTenantStats(tenantId)
      setStats(data)
    } catch (err) {
      console.warn('Stats endpoint unavailable, falling back to job counts')
      setStats(null)
    }
  }, [tenantId])

  // Refresh on mount and every 10s
  useEffect(() => {
    fetchStats()
    const interval = setInterval(fetchStats, 10000)
    return () => clearInterval(interval)
  }, [fetchStats])

  // Also refresh whenever a WebSocket update lands (jobs prop changes)
  useEffect(() => {
    fetchStats()
  }, [jobs])

  // Stats API returns total counts (not paginated) — always prefer over local array lengths
  const pending   = stats?.pending   ?? jobs?.PENDING?.length   ?? 0
  const running   = stats?.running   ?? jobs?.RUNNING?.length   ?? 0
  const completed = stats?.completed ?? jobs?.COMPLETED?.length ?? 0
  const failed    = stats?.failed    ?? jobs?.FAILED?.length    ?? 0
  const dlq       = stats?.dlq       ?? 0
  const cancelled = stats?.cancelled ?? jobs?.CANCELLED?.length ?? 0

  return (
    <div className="grid grid-cols-2 md:grid-cols-3 lg:grid-cols-6 gap-3 sm:gap-4">
      <MetricCard icon="📋" value={pending}   label="Pending"   color="bg-yellow-50 text-yellow-600" />
      <MetricCard icon="⚙️" value={running}   label="Running"   color="bg-blue-50 text-blue-600" />
      <MetricCard icon="✅" value={completed} label="Completed" color="bg-green-50 text-green-600" />
      <MetricCard icon="❌" value={failed}    label="Failed"    color="bg-red-50 text-red-600" />
      <MetricCard icon="☠️" value={dlq}       label="Dead Letter Queue" color="bg-purple-50 text-purple-600" />
      <MetricCard icon="🚫" value={cancelled} label="Cancelled" color="bg-gray-50 text-gray-500" />
    </div>
  )
}
