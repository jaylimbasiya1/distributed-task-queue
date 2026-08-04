import React, { useState, useCallback } from 'react'
import { Toaster } from 'react-hot-toast'
import Header, { TENANTS } from './components/Header'
import MetricsBar from './components/MetricsBar'
import JobSubmitForm from './components/JobSubmitForm'
import JobTable from './components/JobTable'
import DLQPanel from './components/DLQPanel'
import JobDetailModal from './components/JobDetailModal'
import useJobs from './hooks/useJobs'
import useWebSocket from './hooks/useWebSocket'
import { cancelJob, retryJob, setTenantApiKey } from './api/jobApi'
import toast from 'react-hot-toast'

// Initialize API key for default tenant
setTenantApiKey(TENANTS[0].apiKey)

export default function App() {
  const [currentTenant, setCurrentTenant] = useState(TENANTS[0])
  const [selectedJob, setSelectedJob] = useState(null)
  const [activeTab, setActiveTab] = useState('PENDING')
  const [showSubmitForm, setShowSubmitForm] = useState(false)
  const [dlqExpanded, setDlqExpanded] = useState(false)
  const [dlqRefreshKey, setDlqRefreshKey] = useState(0)

  const { jobs, loading, refresh, updateJob, updatedJobsRef } = useJobs(currentTenant)
  const { connected } = useWebSocket(
    currentTenant.id,
    updateJob,
    () => setDlqRefreshKey((k) => k + 1)
  )

  function handleTenantChange(tenant) {
    setTenantApiKey(tenant.apiKey)
    setCurrentTenant(tenant)
  }

  async function handleCancel(jobId) {
    try {
      await cancelJob(jobId)
      toast.success('Job cancelled')
      refresh()
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to cancel job')
    }
  }

  async function handleRetry(jobId) {
    try {
      await retryJob(jobId)
      toast.success('Job requeued for retry')
      refresh()
    } catch (err) {
      toast.error(err?.response?.data?.message || 'Failed to retry job')
    }
  }

  return (
    <div className="min-h-screen bg-gray-50">
      <Toaster
        position="top-right"
        toastOptions={{
          duration: 4000,
          style: { fontSize: '14px', maxWidth: '380px' }
        }}
      />

      <Header
        currentTenant={currentTenant}
        onTenantChange={handleTenantChange}
        connected={connected}
      />

      <main className="max-w-7xl mx-auto px-3 sm:px-4 lg:px-8 py-4 sm:py-6 space-y-4">
        {/* Metrics */}
        <MetricsBar tenantId={currentTenant.id} jobs={jobs} />

        {/* Submit Job — collapsible on mobile, always visible md+ */}
        <div>
          {/* Mobile toggle button */}
          <button
            onClick={() => setShowSubmitForm(!showSubmitForm)}
            className="md:hidden w-full flex items-center justify-between px-4 py-3 bg-white border border-gray-200 rounded-xl shadow-sm text-sm font-medium text-gray-700 hover:bg-gray-50 transition-colors min-h-[44px]"
          >
            <span>{showSubmitForm ? 'Hide Submit Form' : '+ Submit New Job'}</span>
            <svg
              className={`w-4 h-4 text-gray-400 transition-transform ${showSubmitForm ? 'rotate-180' : ''}`}
              fill="none" viewBox="0 0 24 24" stroke="currentColor"
            >
              <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
            </svg>
          </button>

          {/* Form — visible on mobile only when toggled, always on md+ */}
          <div className={`${showSubmitForm ? 'block mt-3' : 'hidden'} md:block md:mt-0`}>
            <JobSubmitForm tenant={currentTenant} onSuccess={refresh} />
          </div>
        </div>

        {/* Job Table */}
        <JobTable
          jobs={jobs}
          loading={loading}
          onCancel={handleCancel}
          onRetry={handleRetry}
          activeTab={activeTab}
          onTabChange={setActiveTab}
          onJobClick={setSelectedJob}
          updatedJobsRef={updatedJobsRef}
        />

        {/* DLQ Panel */}
        <DLQPanel
          tenantId={currentTenant.id}
          expanded={dlqExpanded}
          onToggle={() => setDlqExpanded(!dlqExpanded)}
          refreshKey={dlqRefreshKey}
        />
      </main>

      {/* Job Detail Modal */}
      {selectedJob && (
        <JobDetailModal
          job={selectedJob}
          onClose={() => setSelectedJob(null)}
        />
      )}
    </div>
  )
}
