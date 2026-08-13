# Prefix Sync Data Source

## Architecture
The `prefix-updater` microservice runs as part of the `docker-compose.yml` stack on port 8085. It exposes a single endpoint `/api/prefixes/sync` which is proxied by the `api-gateway`.

## Data Source
The source of the data for network prefixes is the public npm package `mcc-mnc-list`.
- **Package Details**: It contains thousands of MCC (Mobile Country Code) and MNC (Mobile Network Code) combinations worldwide.
- **Mapping**:
  - `countryName` maps from the package's `countryName`.
  - `networkName` maps from the package's `brand` or `operator`.
  - `iso` maps from the package's `countryCode`.
  - `mcc` and `mnc` map directly.
  
> [!NOTE]
> The `mcc-mnc-list` package does **not** provide actual dialing prefixes (e.g., +44). Therefore, when new prefixes are added, the `prefix` field must be updated manually or left blank until it's needed for routing.
