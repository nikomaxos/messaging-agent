export interface DeviceGroup {
  id: number
  name: string
  description?: string
  active: boolean
  dlrDelayMinSec: number
  dlrDelayMaxSec: number
  createdAt: string
}

export interface Device {
  id: number
  name: string
  imei?: string
  status: 'ONLINE' | 'OFFLINE' | 'BUSY' | 'MAINTENANCE'
  group?: DeviceGroup
  batteryPercent?: number
  isCharging?: boolean
  simIccid?: string
  phoneNumber?: string
  wifiSignalDbm?: number
  gsmSignalDbm?: number
  gsmSignalAsu?: number
  networkOperator?: string
  rcsCapable?: boolean
  lastHeartbeat?: string
  connectedAt?: string
  registrationToken?: string
  autoRebootEnabled?: boolean
  autoPurge?: string
  lastPurgedAt?: string
  activeNetworkType?: string
  apkVersion?: string
  apkUpdateStatus?: string
  autostartPinned?: boolean
  silentMode?: boolean
  callBlockEnabled?: boolean
  selfHealingEnabled?: boolean
  adbWifiAddress?: string
  sendIntervalSeconds?: number
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
  supplierMessageId?: string
  customerMessageId?: string
  parentMessage?: MessageLog
  sourceAddress?: string
  destinationAddress?: string
  messageText?: string
  status: 'RECEIVED' | 'DISPATCHED' | 'DELIVERED' | 'RCS_FAILED' | 'FAILED'
  device?: Pick<Device, 'id' | 'name' | 'simIccid' | 'phoneNumber' | 'imei'>
  errorDetail?: string
  fallbackSmsc?: any
  createdAt: string
  dispatchedAt?: string
  rcsDlrReceivedAt?: string
  rcsSentAt?: string
  fallbackStartedAt?: string
  fallbackDlrReceivedAt?: string
  deliveredAt?: string
  deviceGroup?: DeviceGroup
  resendTrigger?: string
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

export interface AppUser {
  id: number
  username: string
  role: string
  active: boolean
  createdAt: string
}
