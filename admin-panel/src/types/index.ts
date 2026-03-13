export interface DeviceGroup {
  id: number
  name: string
  description?: string
  active: boolean
  createdAt: string
}

export interface Device {
  id: number
  name: string
  imei?: string
  status: 'ONLINE' | 'OFFLINE' | 'BUSY' | 'MAINTENANCE'
  group?: DeviceGroup
  batteryPercent?: number
  wifiSignalDbm?: number
  gsmSignalDbm?: number
  gsmSignalAsu?: number
  networkOperator?: string
  rcsCapable?: boolean
  lastHeartbeat?: string
  connectedAt?: string
  registrationToken?: string
}

export interface SmppClient {
  id: number
  name: string
  systemId: string
  password?: string
  active: boolean
  createdAt: string
}

export interface SmppRouting {
  id: number
  smppClient: SmppClient
  deviceGroup: DeviceGroup
  default: boolean
  createdAt: string
}

export interface MessageLog {
  id: number
  smppMessageId?: string
  sourceAddress?: string
  destinationAddress?: string
  messageText?: string
  status: 'RECEIVED' | 'DISPATCHED' | 'DELIVERED' | 'RCS_FAILED' | 'FAILED'
  device?: Pick<Device, 'id' | 'name'>
  errorDetail?: string
  createdAt: string
  deliveredAt?: string
}

export interface AuthResponse {
  token: string
  username: string
  role: string
}
