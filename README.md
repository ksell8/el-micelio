# El Micelio

Android companion app for La Semilla — a network of Raspberry Pi nodes that advertise their presence over Bluetooth Low Energy and host content over Wi-Fi.

## How it works

1. **Discover** — the app scans for nearby Semilla nodes broadcasting Eddystone-UID BLE beacons. Each node is identified by its namespace and instance ID.
2. **Connect** — tapping a node joins its Wi-Fi hotspot (`disturb`) using Android's peer-to-peer Wi-Fi API.
3. **Browse** — an in-app WebView connects to the node's web server where content can be browsed and downloaded to the phone.

## Architecture

| File | Responsibility |
|------|---------------|
| `BeaconScanner.kt` | BLE scanning via Nordic Scanner Compat Library. Parses Eddystone-UID and iBeacon advertisement data into `SemillaNode` objects. |
| `WiFiConnector.kt` | Connects to the node's WPA2 hotspot using `WifiNetworkSpecifier`. Detects WPA2 vs WPA3 from scan results. Exposes connection state as a `StateFlow`. |
| `NodeWebScreen.kt` | In-app WebView browser. Handles file downloads in-process (required for custom CA TLS). Clears history, cache, and cookies on exit. |
| `MainViewModel.kt` | Owns scanner and connector lifecycle. Exposed to UI via `StateFlow`. |
| `MainActivity.kt` | Permission handling, Compose navigation, and all UI screens. |

## BLE beacon format

Nodes broadcast **Eddystone-UID**:
- Namespace: `6469737475724274A865` (ASCII `disturbBt` + `0xA865`)
- Instance: `737461747573` (ASCII `status`)
- Service UUID: `0000FEAA-0000-1000-8000-00805F9B34FB`

## TLS

The app trusts a custom CA certificate (`res/raw/ca.crt`) in addition to system CAs. The Pi's nginx server presents a certificate signed by this CA. Place `ca.crt` in `app/src/main/res/raw/` before building.

To generate certificates:
Run ansible playbooks at https://github.com/ksell8/la-semilla

# Copy CA cert into the app
cp certs/ca.crt app/src/main/res/raw/ca.crt
```

## Requirements

- Android 10+ (API 29)
- Bluetooth and Location permissions (required for BLE scanning on Android)
- Wi-Fi permissions

## Building

```bash
./gradlew assembleDebug
```
