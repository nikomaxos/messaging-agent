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
  autoRebootEnabled?: boolean
  activeNetworkType?: string
  apkVersion?: string
  apkUpdateStatus?: string
}

export interface SmppSession {
  sessionId: string;
  bindType: string;
  uptimeSeconds: number;
}

export interface SmppClient {
  id: number
  name: string
  systemId: string
  password?: string
  active: boolean
  createdAt: string
  activeSessions?: SmppSession[]
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
  fallbackStartedAt?: string
}

export interface AuthResponse {
  token: string
  username: string
  role: string
}

export interface ServerMetrics {
  totalMessages: number;
  dlrsReceived: number;
  failedMessages: number;
  queuedMessages: number;
  resentFallback: number;
}

export interface SmscSupplierConfig {
  id: number
  name: string
  systemId: string
  password?: string
  host: string
  port: number
  systemType?: string
  bindType: string
  addressRange?: string
  sourceTon: number
  sourceNpi: number
  destTon: number
  destNpi: number
  throughput: number
  enquireLinkInterval: number
  active: boolean
  createdAt: string
  updatedAt?: string
}

export interface SmscSupplier {
  supplier: SmscSupplierConfig
  uptimeSeconds?: number
  connected: boolean
  totalMessages: number
  dlrsReceived: number
  failed: number
  inQueue: number
}
