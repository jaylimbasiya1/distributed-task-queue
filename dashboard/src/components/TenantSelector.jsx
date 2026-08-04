import React, { useState, useRef, useEffect } from 'react'
import { setTenantApiKey } from '../api/jobApi'

export default function TenantSelector({ tenants, currentTenant, onTenantChange }) {
  const [open, setOpen] = useState(false)
  const dropdownRef = useRef(null)

  useEffect(() => {
    function handleClickOutside(e) {
      if (dropdownRef.current && !dropdownRef.current.contains(e.target)) {
        setOpen(false)
      }
    }
    document.addEventListener('mousedown', handleClickOutside)
    return () => document.removeEventListener('mousedown', handleClickOutside)
  }, [])

  function handleSelect(tenant) {
    setTenantApiKey(tenant.apiKey)
    onTenantChange(tenant)
    setOpen(false)
  }

  return (
    <div className="relative" ref={dropdownRef}>
      <button
        onClick={() => setOpen(!open)}
        className="flex items-center gap-2 px-3 py-2 bg-white border border-gray-200 rounded-lg shadow-sm hover:bg-gray-50 transition-colors min-h-[44px] w-full sm:w-auto"
        aria-haspopup="listbox"
        aria-expanded={open}
      >
        <span
          className="w-3 h-3 rounded-full flex-shrink-0"
          style={{ backgroundColor: currentTenant.color }}
        />
        <span className="text-sm font-medium text-gray-700 truncate">{currentTenant.name}</span>
        <svg className={`w-4 h-4 text-gray-400 flex-shrink-0 transition-transform ${open ? 'rotate-180' : ''}`} fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M19 9l-7 7-7-7" />
        </svg>
      </button>

      {open && (
        <div className="absolute right-0 mt-1 w-48 bg-white border border-gray-200 rounded-lg shadow-lg z-50" role="listbox">
          {tenants.map((tenant) => (
            <button
              key={tenant.id}
              onClick={() => handleSelect(tenant)}
              className={`flex items-center gap-2 w-full px-3 py-2.5 text-left hover:bg-gray-50 transition-colors first:rounded-t-lg last:rounded-b-lg min-h-[44px] ${
                currentTenant.id === tenant.id ? 'bg-blue-50' : ''
              }`}
              role="option"
              aria-selected={currentTenant.id === tenant.id}
            >
              <span className="w-3 h-3 rounded-full flex-shrink-0" style={{ backgroundColor: tenant.color }} />
              <span className="text-sm font-medium text-gray-700">{tenant.name}</span>
              {currentTenant.id === tenant.id && (
                <svg className="w-4 h-4 text-blue-500 ml-auto" fill="none" viewBox="0 0 24 24" stroke="currentColor">
                  <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={2} d="M5 13l4 4L19 7" />
                </svg>
              )}
            </button>
          ))}
        </div>
      )}
    </div>
  )
}
