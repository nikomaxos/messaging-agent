import React from 'react'

export default function SmppProxyPage() {
  return (
    <div className="w-full h-full min-h-[calc(100vh-64px)] bg-white dark:bg-[#0f172a]">
      <iframe 
        src="/smpp-proxy/" 
        className="w-full h-full min-h-[calc(100vh-64px)] border-0"
        title="SMPP Proxy"
      />
    </div>
  )
}
