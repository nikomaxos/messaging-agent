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
  registrationToken?: string
}

export interface SmppConfig {
  id: number
  name: string
  systemId: string
  password: string
  host: string
  port: number
  bindType: 'TRANSCEIVER' | 'TRANSMITTER' | 'RECEIVER'
  active: boolean
  deviceGroup?: DeviceGroup
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
