# messaging-agent

> **Carrier-grade, open-source SMPP → RCS gateway with a rooted Android device pool**

[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot](https://img.shields.io/badge/Spring%20Boot-3.2-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![Kotlin](https://img.shields.io/badge/Kotlin-2.0-purple.svg)](https://kotlinlang.org/)
[![React](https://img.shields.io/badge/React-18-blue.svg)](https://react.dev/)
[![License](https://img.shields.io/badge/License-Apache%202.0-blue.svg)](LICENSE)

---

## Overview

`messaging-agent` receives SMS PDUs over SMPP 3.4 from upstream requesters (MNOs, MVNOs, aggregators) and transcodes them to simple-text **RCS messages**, delivered through a swarm of rooted Android phones.

```
Upstream (SMPP 3.4)
        │
        ▼
┌────────────────────┐
│   Spring Backend   │  ← CloudHopper SMPP server
│   + Kafka routing  │  ← Round-robin virtual SMSC
└────────────────────┘
        │ WebSocket (STOMP)
        ▼
┌────────────────────┐
│  Android Device    │  ← Foreground service
│  Pool (rooted)     │  ← Heartbeat (battery/WiFi/GSM)
│                    │  ← RCS send (Google Messages)
└────────────────────┘
        │
        ▼
  Recipient's RCS client
```

If the recipient **does not support RCS**, the Android app returns a failure, and the backend signals `ESME_RDELIVERYFAILURE` (0x00000011) + TLV `0x1400 = 0x01` (NO_RCS_CAPABILITY) to the requester so they can **fail-over to plain SMS**.

---

## Repository Structure

```
messaging-agent/
├── backend/          # Java 21 / Spring Boot 3.x
├── admin-panel/      # React 18 + Vite + TypeScript
├── android-app/      # Kotlin / Jetpack Compose
└── docker-compose.yml
```

---

## Technology Stack

| Component | Technology |
|---|---|
| Backend | Java 21, Spring Boot 3.2, CloudHopper SMPP |
| Message Broker | Apache Kafka (Confluent) |
| Database | PostgreSQL 16 + Flyway |
| Device Comms | Spring WebSocket (STOMP), OkHttp |
| Security | JWT (nimbus-jose-jwt), BCrypt |
| Admin Panel | React 18, Vite, TypeScript, Tailwind CSS |
| Android App | Kotlin, Jetpack Compose, WorkManager, Hilt |
| Root Access | libsu (Shell + service) |
| Container | Docker + Docker Compose |

---

## Quick Start

### Prerequisites

- Docker & Docker Compose
- Java 21 (for local backend dev)
- Node.js 20 (for local admin panel dev)
- Android Studio + Android device with root access

### Run with Docker Compose

```bash
git clone https://github.com/<your-org>/messaging-agent.git
cd messaging-agent

# Start full stack (PostgreSQL + Kafka + Backend + Admin Panel)
docker compose up -d

# Check health
docker compose ps
curl http://localhost:8080/actuator/health
```

Access the admin panel at **http://localhost** with:
- Username: `admin`
- Password: `changeme`

The SMPP server listens on **port 2775**.

> ⚠️ Change `JWT_SECRET`, `ADMIN_PASS`, and database password in `docker-compose.yml` before any production deployment.

---

## Configuration

### Backend (`backend/src/main/resources/application.yml`)

| Property | Description | Default |
|---|---|---|
| `app.smpp.server.port` | SMPP bind port | `2775` |
| `app.smpp.server.system-id` | SMPP system identifier | `MSGAGENT` |
| `spring.security.jwt.secret` | JWT signing secret (≥32 chars) | *(change me)* |

### Environment Variables (Docker)

| Variable | Description |
|---|---|
| `DB_HOST` / `DB_PASS` | PostgreSQL connection |
| `KAFKA_BROKERS` | Kafka bootstrap servers |
| `JWT_SECRET` | JWT HMAC-SHA256 key |
| `ADMIN_USER` / `ADMIN_PASS` | Admin panel credentials |

---

## Android App Setup

1. Build the debug APK:
   ```bash
   cd android-app
   ./gradlew assembleDebug
   ```
2. Install on a **rooted** Android 10+ device
3. Launch **Messaging Agent**, enter:
   - **Backend URL**: e.g. `http://192.168.1.100:8080`
   - **Registration Token**: copy from Admin Panel → Devices → Token column
   - **Device Name**: friendly name shown in dashboard
4. Tap **Save & Connect** — the foreground service starts and the device appears ONLINE in the admin panel

---

## Admin Panel Screens

| Page | Description |
|---|---|
| **Dashboard** | Live device pool grid (battery, WiFi, GSM, RCS status) — auto-refreshes every 5s |
| **Device Groups** | CRUD for virtual SMSCs; each group acts as one SMSC to the requester |
| **Devices** | CRUD for Android devices; copy registration token to clipboard |
| **SMPP Configs** | Configure upstream SMPP connections (systemId, password, host, port, bind type) |
| **Message Logs** | Full audit trail with paginated table (source, destination, status, device, error) |

---

## SMPP Error Codes

| Scenario | SMPP Response |
|---|---|
| RCS delivered | `SUBMIT_SM_RESP STATUS_OK` |
| No RCS capability | `SUBMIT_SM_RESP ESME_RDELIVERYFAILURE (0x00000011)` + TLV 0x1400 = 0x01 |
| No devices online | `SUBMIT_SM_RESP ESME_RDELIVERYFAILURE` + TLV 0x1400 = 0x02 |
| System error | `SUBMIT_SM_RESP ESME_RSYSERR (0x00000008)` |

---

## Local Development

### Backend

```bash
cd backend
# Requires PostgreSQL and Kafka running locally
./mvnw spring-boot:run
```

### Admin Panel

```bash
cd admin-panel
npm install
npm run dev    # → http://localhost:5173
```

### Tests

```bash
# Backend unit + integration tests
cd backend && ./mvnw test

# Admin panel tests
cd admin-panel && npm run test
```

---

## License

Apache 2.0 — see [LICENSE](LICENSE)
