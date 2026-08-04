import { useState, useEffect, useCallback, useRef } from 'react'
import { getJobs, setTenantApiKey } from '../api/jobApi'

const STATUSES = ['PENDING', 'RUNNING', 'COMPLETED', 'FAILED', 'CANCELLED']

export default function useJobs(currentTenant) {
  const [jobs, setJobs] = useState({
    PENDING: [], RUNNING: [], COMPLETED: [], FAILED: [], CANCELLED: []
  })
  const [loading, setLoading] = useState(false)
  const updatedJobsRef = useRef(new Set())
  const timeoutIds = useRef([])

  const refresh = useCallback(async () => {
    if (!currentTenant) return
    setTenantApiKey(currentTenant.apiKey)
    setLoading(true)
    try {
      const results = await Promise.allSettled(
        STATUSES.map((status) => getJobs(status))
      )
      const newJobs = {}
      STATUSES.forEach((status, i) => {
        if (results[i].status === 'fulfilled') {
          const data = results[i].value
          newJobs[status] = Array.isArray(data) ? data : data.content || data.jobs || data.data || []
        } else {
          newJobs[status] = []
        }
      })
      setJobs(newJobs)
    } catch (err) {
      console.error('Failed to fetch jobs:', err)
    } finally {
      setLoading(false)
    }
  }, [currentTenant])

  const updateJob = useCallback((updatedJob) => {
    if (!updatedJob?.id) return

    // Ignore jobs from a different tenant
    if (updatedJob.tenantId && updatedJob.tenantId !== updatedJob._tenantId) {
      // tenantId filtering done in WebSocket hook — just update if present
    }

    const tid = setTimeout(() => updatedJobsRef.current.delete(updatedJob.id), 2000)
    timeoutIds.current.push(tid)
    updatedJobsRef.current.add(updatedJob.id)

    setJobs((prev) => {
      const next = {}
      STATUSES.forEach((s) => { next[s] = prev[s] ? [...prev[s]] : [] })

      // Remove this job from every bucket
      STATUSES.forEach((s) => {
        next[s] = next[s].filter((j) => j.id !== updatedJob.id)
      })

      // Place into the right bucket — DLQ jobs are tracked by DLQPanel separately
      const target = updatedJob.status
      if (next[target] !== undefined) {
        next[target] = [updatedJob, ...next[target]]
      }

      return next
    })
  }, [])

  // Cleanup timeouts on unmount
  useEffect(() => {
    return () => {
      timeoutIds.current.forEach(clearTimeout)
    }
  }, [])

  useEffect(() => {
    setJobs({ PENDING: [], RUNNING: [], COMPLETED: [], FAILED: [], CANCELLED: [] })
    refresh()
  }, [currentTenant?.id])

  return { jobs, loading, refresh, updateJob, updatedJobsRef }
}
