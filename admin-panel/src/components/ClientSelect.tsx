import { useState, useRef, useEffect } from 'react'
import { SmppClient } from '../types'
import { Search } from 'lucide-react'

interface ClientSelectProps {
  clients: SmppClient[]
  value: string // systemId
  onChange: (systemId: string) => void
  placeholder?: string
}

export function ClientSelect({ clients, value, onChange, placeholder = "Search by Name or System ID..." }: ClientSelectProps) {
  const [isOpen, setIsOpen] = useState(false)
  const [query, setQuery] = useState('')
  const wrapperRef = useRef<HTMLDivElement>(null)

  useEffect(() => {
    const selected = clients.find(c => c.systemId === value)
    if (selected && !isOpen) {
      setQuery(`${selected.name} (${selected.systemId})`)
    } else if (!value && !isOpen) {
      setQuery('')
    }
  }, [value, clients, isOpen])

  useEffect(() => {
    function handleClickOutside(event: MouseEvent) {
      if (wrapperRef.current && !wrapperRef.current.contains(event.target as Node)) {
        setIsOpen(false)
        const selected = clients.find(c => c.systemId === value)
        if (selected) {
          setQuery(`${selected.name} (${selected.systemId})`)
        } else {
          setQuery('')
        }
      }
    }
    document.addEventListener("mousedown", handleClickOutside)
    return () => document.removeEventListener("mousedown", handleClickOutside)
  }, [wrapperRef, value, clients])

  const filtered = query === '' 
    ? clients 
    : clients.filter(c => 
        c.name.toLowerCase().includes(query.toLowerCase()) || 
        c.systemId.toLowerCase().includes(query.toLowerCase())
      )

  return (
    <div className="relative" ref={wrapperRef}>
      <div className="relative">
        <input
          type="text"
          className="w-full bg-[#12121f] border border-white/10 rounded px-3 py-2 pl-9 text-white focus:outline-none focus:border-brand-500/50"
          placeholder={placeholder}
          value={query}
          onChange={e => {
            setQuery(e.target.value)
            setIsOpen(true)
            if (e.target.value === '') {
              onChange('')
            }
          }}
          onFocus={() => setIsOpen(true)}
        />
        <Search size={16} className="absolute left-3 top-1/2 -translate-y-1/2 text-slate-500" />
      </div>
      
      {isOpen && (
        <div className="absolute z-10 w-full mt-1 bg-[#1a1a2e] border border-white/10 rounded-lg shadow-xl max-h-60 overflow-y-auto">
          {filtered.length === 0 ? (
            <div className="px-4 py-3 text-sm text-slate-400">No clients found.</div>
          ) : (
            filtered.map(c => (
              <div
                key={c.systemId}
                className="px-4 py-2 hover:bg-brand-500/10 cursor-pointer text-sm text-white flex justify-between items-center"
                onClick={() => {
                  onChange(c.systemId)
                  setQuery(`${c.name} (${c.systemId})`)
                  setIsOpen(false)
                }}
              >
                <span>{c.name}</span>
                <span className="font-mono text-xs text-slate-500">{c.systemId}</span>
              </div>
            ))
          )}
        </div>
      )}
    </div>
  )
}
