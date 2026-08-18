import React from 'react'
import TenantSelector from './TenantSelector'

const TENANTS = [
  { id: 'tenant-shopify', name: 'Shopify', apiKey: 'demo-shopify-key', color: '#96bf48' },
  { id: 'tenant-uber', name: 'Uber', apiKey: 'demo-uber-key', color: '#000000' },
  { id: 'tenant-netflix', name: 'Netflix', apiKey: 'demo-netflix-key', color: '#e50914' },
  { id: 'tenant-badactor', name: 'BadActor', apiKey: 'demo-badactor-key', color: '#6b7280' },
]

export default function Header({ currentTenant, onTenantChange, connected }) {
  return (
    <header className="bg-white border-b border-gray-200 sticky top-0 z-40 shadow-sm">
      <div className="max-w-7xl mx-auto px-3 sm:px-4 lg:px-8">
        {/* Desktop layout */}
        <div className="hidden sm:flex items-center justify-between h-16">
          <div className="flex items-center gap-3">
            <span className="text-xl font-bold text-gray-900">⚡ TaskQueue</span>
            <span className="text-xs text-gray-400 bg-gray-100 px-2 py-0.5 rounded-full">Dashboard</span>
          </div>
          <div className="flex items-center gap-4">
            {/* WebSocket status indicator */}
            <div className="flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400 pulse-dot' : 'bg-red-400'}`} />
              <span className="text-xs text-gray-500">{connected ? 'Live' : 'Disconnected'}</span>
            </div>
            <TenantSelector
              tenants={TENANTS}
              currentTenant={currentTenant}
              onTenantChange={onTenantChange}
            />
          </div>
        </div>

        {/* Mobile layout */}
        <div className="flex sm:hidden flex-col py-3 gap-3">
          <div className="flex items-center justify-between">
            <span className="text-lg font-bold text-gray-900">⚡ TaskQueue</span>
            <div className="flex items-center gap-1.5">
              <span className={`w-2 h-2 rounded-full ${connected ? 'bg-green-400 pulse-dot' : 'bg-red-400'}`} />
              <span className="text-xs text-gray-500">{connected ? 'Live' : 'Offline'}</span>
            </div>
          </div>
          <TenantSelector
            tenants={TENANTS}
            currentTenant={currentTenant}
            onTenantChange={onTenantChange}
          />
        </div>
      </div>
    </header>
  )
}

export { TENANTS }
