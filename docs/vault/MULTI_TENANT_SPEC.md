# Multi-Tenant Architecture Specification
# Path: docs/vault/MULTI_TENANT_SPEC.md

## 1. Overview
The Messaging Agent platform utilizes a strict Multi-Tenant architecture. All core entities (Devices, Campaigns, Accounts, SMSC Suppliers, API Clients) MUST be logically isolated by a `tenant_id`. Data cross-pollination between tenants is considered a catastrophic security breach.

## 2. API Key Data Model & Authentication
- **API Keys**: External API clients and SMSC suppliers authenticate via an `X-API-Key` header. API Keys are cryptographically hashed in the database (never stored in plaintext).
- **Tenant Context**: Upon successful API Key authentication, the Spring Security `SecurityContext` is populated with a custom `Authentication` token that holds the `tenant_id` and authorized roles.
- **Filter Chain**: The `ApiKeyAuthFilter` MUST be registered before the `UsernamePasswordAuthenticationFilter` in all Spring Security configurations.

## 3. Database Tenancy Scoping
- Every table belonging to a tenant MUST include a `tenant_id` column (indexed for performance).
- All JPA Repositories MUST explicitly filter by `tenant_id` (e.g., `findByUuidAndTenantId(UUID uuid, Long tenantId)`).
- **Global Entities**: Entities without a `tenant_id` (e.g., global configuration, global rate limit rules) are considered "System Level" and can only be managed by the `SUPER_ADMIN` role.

## 4. Integration Guidelines

### Adding New SMSC Suppliers
- When an SMSC Supplier connects via SMPP to the `smpp-edge` node, the `system_id` and password MUST map to a specific `Tenant`.
- The `SmppServerService` MUST inject the authenticated `tenant_id` into the message headers before placing the message onto the Kafka topic `inbound.raw`.
- Downstream services processing Kafka messages MUST route billing and routing decisions based on the Kafka header `tenant_id`.

### API Client Integrations
- Third-party API clients submit HTTP REST requests. The API Gateway or Core Service MUST extract the `X-API-Key`, authenticate the tenant, and automatically scope all CRUD operations to that tenant.
- 404 Not Found should be returned (instead of 403 Forbidden) if a tenant attempts to access an entity belonging to another tenant by ID, to prevent ID enumeration.

### Campaign Batch Processors
- Batch processing jobs (e.g., Quartz or Spring Batch) that execute scheduled campaigns MUST execute within the security context of the tenant owning the campaign.
- Any background thread interacting with the database must manually construct a `SecurityContext` containing the relevant `tenant_id` if using tenant-scoped repository methods, or pass the `tenant_id` explicitly as a method argument.
