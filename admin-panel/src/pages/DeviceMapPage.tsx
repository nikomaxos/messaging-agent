import { useQuery } from '@tanstack/react-query'
import { getDevices, getGroups } from '../api/client'
import { MapPin, Filter } from 'lucide-react'
import { useEffect, useRef, useState } from 'react'

const STATUS_COLORS: Record<string, string> = {
  ONLINE: '#34d399', OFFLINE: '#f87171', BUSY: '#fbbf24', MAINTENANCE: '#94a3b8',
}

export default function DeviceMapPage() {
  const mapRef = useRef<HTMLDivElement>(null)
  const mapInstanceRef = useRef<any>(null)
  const hasFitBoundsRef = useRef(false)
  const [selectedGroupId, setSelectedGroupId] = useState<number | 'ALL'>('ALL')

  const { data: allDevices } = useQuery({
    queryKey: ['devices'],
    queryFn: getDevices,
    refetchInterval: 30000,
  })

  const { data: groups } = useQuery({
    queryKey: ['groups'],
    queryFn: getGroups,
  })

  // Filter devices by group
  const devices = selectedGroupId === 'ALL' 
    ? allDevices 
    : allDevices?.filter((d: any) => d.group?.id === selectedGroupId)


  // Load Leaflet CSS + JS dynamically
  useEffect(() => {
    if (!document.getElementById('leaflet-css')) {
      const link = document.createElement('link')
      link.id = 'leaflet-css'
      link.rel = 'stylesheet'
      link.href = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.css'
      document.head.appendChild(link)
    }

    const L = (window as any).L;
    if (L) {
      initMap();
      setTimeout(() => { if (mapInstanceRef.current) mapInstanceRef.current.invalidateSize() }, 200);
    } else {
      if (!document.getElementById('leaflet-js')) {
        const script = document.createElement('script')
        script.id = 'leaflet-js'
        script.src = 'https://unpkg.com/leaflet@1.9.4/dist/leaflet.js'
        script.onload = () => {
          initMap();
        }
        document.head.appendChild(script)
      }
    }

    return () => {
      if (mapInstanceRef.current) {
        mapInstanceRef.current.remove()
        mapInstanceRef.current = null
      }
    }
  }, [])

  // Update markers when devices change
  useEffect(() => {
    if (!mapInstanceRef.current || !devices) return
    updateMarkers()
  }, [devices])

  // Global function for Leaflet popup buttons
  useEffect(() => {
    (window as any).refreshDeviceLocation = (id: number) => {
      fetch(`/api/devices/${id}/command`, {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ command: 'REFRESH_LOCATION' })
      }).then(() => {
        alert('Location refresh requested. Map will update within 20 seconds.')
      }).catch(err => console.error(err))
    }
    return () => {
      delete (window as any).refreshDeviceLocation
    }
  }, [])

  const initMap = () => {
    if (!mapRef.current || mapInstanceRef.current) return
    const L = (window as any).L
    if (!L) return

    const map = L.map(mapRef.current).setView([37.5, 23.7], 6) // Default: Greece
    L.tileLayer('https://{s}.basemaps.cartocdn.com/dark_all/{z}/{x}/{y}{r}.png', {
      attribution: '© CartoDB',
      maxZoom: 19,
    }).addTo(map)

    mapInstanceRef.current = map
    if (devices) updateMarkers()
  }

  const updateMarkers = () => {
    const L = (window as any).L
    const map = mapInstanceRef.current
    if (!L || !map || !devices) return

    // Clear existing markers
    map.eachLayer((layer: any) => {
      if (layer._isDeviceMarker) map.removeLayer(layer)
    })

    const withCoords = devices.filter((d: any) => d.latitude && d.longitude)
    withCoords.forEach((d: any) => {
      const color = STATUS_COLORS[d.status] || '#94a3b8'
      const icon = L.divIcon({
        className: '',
        html: `<div style="width:16px;height:16px;border-radius:50%;background:${color};border:2px solid rgba(255,255,255,0.3);box-shadow:0 0 8px ${color}50"></div>`,
        iconSize: [16, 16],
        iconAnchor: [8, 8],
      })

      const marker = L.marker([d.latitude, d.longitude], { icon }).addTo(map)
      marker._isDeviceMarker = true
      marker.bindPopup(`
        <div style="font-family:system-ui;font-size:12px;min-width:160px">
          <div style="font-weight:700;margin-bottom:4px">${d.name}</div>
          <div style="color:#888">Status: <span style="color:${color}">${d.status}</span></div>
          ${d.group?.name ? `<div style="color:#888">Group: ${d.group.name}</div>` : ''}
          ${d.batteryPercent != null ? `<div style="color:#888">Battery: ${d.batteryPercent}%</div>` : ''}
          ${d.phoneNumber ? `<div style="color:#888">Phone: ${d.phoneNumber}</div>` : ''}
          ${d.apkVersion ? `<div style="color:#888">APK: v${d.apkVersion}</div>` : ''}
          <button onclick="window.refreshDeviceLocation(${d.id})" style="margin-top:8px;padding:6px 10px;background:#0d9488;color:white;border:none;border-radius:4px;cursor:pointer;font-size:11px;width:100%;text-align:center;font-weight:600 transition:background 0.2s" onmouseover="this.style.background='#0f766e'" onmouseout="this.style.background='#0d9488'">Refresh Location</button>
        </div>
      `)
    })

    // Auto-fit bounds ONLY on the first load to prevent stealing user's pan/zoom
    if (withCoords.length > 0 && !hasFitBoundsRef.current) {
      const bounds = L.latLngBounds(withCoords.map((d: any) => [d.latitude, d.longitude]))
      map.fitBounds(bounds, { padding: [50, 50], maxZoom: 12 })
      hasFitBoundsRef.current = true
    }
  }

  const devicesWithCoords = (devices ?? []).filter((d: any) => d.latitude && d.longitude)
  const devicesWithoutCoords = (devices ?? []).filter((d: any) => !d.latitude || !d.longitude)

  return (
    <div className="space-y-4 h-[calc(100vh-220px)] flex flex-col">
      <div className="flex items-center justify-between">
        <div>
          <h1 className="text-xl font-bold text-white flex items-center gap-2">
            <MapPin size={22} className="text-teal-400" /> Device Map
          </h1>
          <p className="text-slate-500 text-xs mt-0.5">
            {devicesWithCoords.length} devices with GPS • {devicesWithoutCoords.length} without GPS
          </p>
        </div>
        
        {/* Group Filter */}
        <div className="flex items-center gap-2">
          <Filter size={16} className="text-slate-400" />
          <select
            value={selectedGroupId}
            onChange={(e) => setSelectedGroupId(e.target.value === 'ALL' ? 'ALL' : Number(e.target.value))}
            className="bg-slate-800 border-slate-700 text-sm rounded-md px-3 py-1.5 min-w-[150px] outline-none focus:ring-1 focus:ring-teal-500"
          >
            <option value="ALL">All Groups</option>
            {groups?.map((g: any) => (
              <option key={g.id} value={g.id}>{g.name}</option>
            ))}
          </select>
        </div>
      </div>

      {/* Legend */}
      <div className="flex gap-4 text-[10px]">
        {Object.entries(STATUS_COLORS).map(([status, color]) => (
          <div key={status} className="flex items-center gap-1.5">
            <div className="w-2.5 h-2.5 rounded-full" style={{ background: color }} />
            <span className="text-slate-400">{status}</span>
          </div>
        ))}
      </div>

      {/* Map */}
      <div className="flex-1 rounded-lg overflow-hidden border border-white/10 min-h-[400px]" ref={mapRef}>
        {devicesWithCoords.length === 0 && (
          <div className="flex items-center justify-center h-full bg-slate-900/50">
            <div className="text-center">
              <MapPin size={32} className="text-slate-700 mx-auto mb-2" />
              <div className="text-slate-600 text-sm">No devices reporting GPS coordinates</div>
              <div className="text-slate-700 text-xs mt-1">GPS data is sent via device heartbeats</div>
            </div>
          </div>
        )}
      </div>
    </div>
  )
}
