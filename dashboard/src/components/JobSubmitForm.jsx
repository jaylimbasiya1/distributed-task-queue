import React, { useState } from 'react'
import toast from 'react-hot-toast'
import { submitJob } from '../api/jobApi'

function generateUUID() {
  return 'xxxxxxxx-xxxx-4xxx-yxxx-xxxxxxxxxxxx'.replace(/[xy]/g, function (c) {
    const r = (Math.random() * 16) | 0
    const v = c === 'x' ? r : (r & 0x3) | 0x8
    return v.toString(16)
  })
}

export default function JobSubmitForm({ tenant, onSuccess }) {
  const [form, setForm] = useState({
    type: '',
    payload: '{"orderId": "ORD-001"}',
    idempotencyKey: '',
    delayMs: 0,
    maxRetries: 3
  })
  const [payloadError, setPayloadError] = useState('')
  const [submitting, setSubmitting] = useState(false)

  function handleChange(e) {
    const { name, value } = e.target
    setForm((prev) => ({ ...prev, [name]: value }))
  }

  function validatePayload() {
    if (!form.payload.trim()) {
      setPayloadError('')
      return
    }
    try {
      JSON.parse(form.payload)
      setPayloadError('')
    } catch (e) {
      setPayloadError('Invalid JSON: ' + e.message)
    }
  }

  async function handleSubmit(e) {
    e.preventDefault()

    // Validate JSON payload
    let parsedPayload = {}
    if (form.payload.trim()) {
      try {
        parsedPayload = JSON.parse(form.payload)
      } catch (e) {
        setPayloadError('Invalid JSON payload')
        return
      }
    }

    if (!form.type.trim()) {
      toast.error('Job type is required')
      return
    }

    setSubmitting(true)
    try {
      const jobData = {
        type: form.type.trim(),
        payload: parsedPayload,
        delayMs: parseInt(form.delayMs) || 0,
        maxRetries: parseInt(form.maxRetries) || 3
      }
      if (form.idempotencyKey.trim()) {
        jobData.idempotencyKey = form.idempotencyKey.trim()
      }

      await submitJob(jobData)
      toast.success('Job submitted successfully!')
      setForm((prev) => ({ ...prev, type: '', idempotencyKey: '' }))
      onSuccess?.()
    } catch (err) {
      const status = err?.response?.status
      const serverMsg = err?.response?.data?.error || err?.response?.data?.message
      if (status === 429) {
        toast.error('Rate limit exceeded. Please wait before submitting again.')
      } else if (status === 409) {
        toast.error('Duplicate job: idempotency key already used.')
      } else if (status === 401 || status === 403) {
        toast.error(serverMsg || 'Unauthorized. Check your API key.')
      } else {
        toast.error(serverMsg || 'Failed to submit job')
      }
    } finally {
      setSubmitting(false)
    }
  }

  return (
    <div className="bg-white rounded-xl border border-gray-200 shadow-sm">
      <div className="px-4 sm:px-6 py-4 border-b border-gray-100">
        <h2 className="text-base font-semibold text-gray-900">Submit New Job</h2>
        <p className="text-xs text-gray-500 mt-0.5">
          Tenant: <span className="font-medium" style={{ color: tenant?.color }}>{tenant?.name}</span>
        </p>
      </div>

      <form onSubmit={handleSubmit} className="px-4 sm:px-6 py-4">
        <div className="grid grid-cols-1 sm:grid-cols-2 gap-4">
          {/* Job Type */}
          <div className="sm:col-span-2">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Job Type <span className="text-red-500">*</span>
            </label>
            <input
              type="text"
              name="type"
              value={form.type}
              onChange={handleChange}
              placeholder="e.g. order-processing"
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[44px]"
              required
            />
          </div>

          {/* JSON Payload */}
          <div className="sm:col-span-2">
            <label className="block text-sm font-medium text-gray-700 mb-1">JSON Payload</label>
            <textarea
              name="payload"
              value={form.payload}
              onChange={handleChange}
              onBlur={validatePayload}
              rows={4}
              className={`w-full px-3 py-2.5 border rounded-lg text-sm font-mono focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent resize-y ${
                payloadError ? 'border-red-400 bg-red-50' : 'border-gray-300'
              }`}
              placeholder='{"key": "value"}'
            />
            {payloadError && (
              <p className="mt-1 text-xs text-red-600">{payloadError}</p>
            )}
          </div>

          {/* Idempotency Key */}
          <div className="sm:col-span-2">
            <label className="block text-sm font-medium text-gray-700 mb-1">
              Idempotency Key <span className="text-gray-400 font-normal">(optional)</span>
            </label>
            <div className="flex gap-2">
              <input
                type="text"
                name="idempotencyKey"
                value={form.idempotencyKey}
                onChange={handleChange}
                placeholder="Leave empty to auto-generate"
                className="flex-1 px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[44px]"
              />
              <button
                type="button"
                onClick={() => setForm((prev) => ({ ...prev, idempotencyKey: generateUUID() }))}
                className="px-3 py-2 bg-gray-100 hover:bg-gray-200 text-gray-700 text-sm font-medium rounded-lg transition-colors min-h-[44px] whitespace-nowrap flex-shrink-0"
              >
                Generate
              </button>
            </div>
          </div>

          {/* Delay */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Delay (ms)</label>
            <input
              type="number"
              name="delayMs"
              value={form.delayMs}
              onChange={handleChange}
              min={0}
              step={100}
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[44px]"
            />
          </div>

          {/* Max Retries */}
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-1">Max Retries</label>
            <input
              type="number"
              name="maxRetries"
              value={form.maxRetries}
              onChange={handleChange}
              min={0}
              max={10}
              className="w-full px-3 py-2.5 border border-gray-300 rounded-lg text-sm focus:outline-none focus:ring-2 focus:ring-blue-500 focus:border-transparent min-h-[44px]"
            />
          </div>
        </div>

        <div className="mt-4">
          <button
            type="submit"
            disabled={submitting || !!payloadError}
            className="w-full sm:w-auto px-6 py-2.5 bg-blue-600 hover:bg-blue-700 disabled:bg-blue-300 text-white text-sm font-medium rounded-lg transition-colors min-h-[44px] flex items-center justify-center gap-2"
          >
            {submitting ? (
              <>
                <svg className="animate-spin w-4 h-4" fill="none" viewBox="0 0 24 24">
                  <circle className="opacity-25" cx="12" cy="12" r="10" stroke="currentColor" strokeWidth="4" />
                  <path className="opacity-75" fill="currentColor" d="M4 12a8 8 0 018-8V0C5.373 0 0 5.373 0 12h4z" />
                </svg>
                Submitting...
              </>
            ) : (
              'Submit Job'
            )}
          </button>
        </div>
      </form>
    </div>
  )
}
