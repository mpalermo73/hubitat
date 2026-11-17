# MySubaru Connected Services for Hubitat

A Hubitat Elevation port of [G-Two/subarulink](https://github.com/G-Two/subarulink): status, locks, horn and lights, remote start and EV charging through the MySubaru mobile API. Remote commands need an active Security Plus (or Starlink Safety and Security Plus) subscription.

---

## Devices

Each vehicle is one device. It creates child devices for the parts that vehicle actually reports, so a car only gets the devices it supports.

| Device | Capabilities | Custom attributes and commands |
|---|---|---|
| **Crosstrek** (the vehicle) | `PresenceSensor` (see Settings), `Alarm` (`both`/`siren` = horn and lights, `strobe` = lights, `off` = stop), `Refresh` | `odometer`, `distanceToEmpty`, `avgFuelConsumption`, `fuelPercent`, `vehicleState`, `latitude`, `longitude`, `warningLights`, `lastReported`, `commandStatus`, `lastUpdated`; `locate` (wakes the car for a fresh position) |
| **Crosstrek Doors** | `ContactSensor` (any door, hood or tailgate open), `Lock` | `frontLeft`, `frontRight`, `rearLeft`, `rearRight`, `tailgate`, `hood`; `unlockDriverDoor`, `unlockTailgate` |
| **Crosstrek Windows** | `ContactSensor` (any window or sunroof open or vented) | `frontLeft`, `frontRight`, `rearLeft`, `rearRight`, `sunroof` |
| **Crosstrek Engine** | `Switch` (on = remote start with the preset preference, off = stop) | `preset`; `remoteStart(preset)` |
| **Crosstrek Charging** (EVs) | `Switch` (on = charge now), `Battery`, `PowerSource` | `chargerState`, `plug`, `evRange`, `timeToFull` |
| **Crosstrek Tire FL/FR/RL/RR** | `PressureMeasurement` | `recommendedPressure` |

Notes:
- Lock state is only real on vehicles that report it. Elsewhere it shows `unknown` until Hubitat sends a lock or unlock.
- Subaru has no window, sunroof or stop-charging command; those are status only.
- Remote start refuses climate presets that are disabled in MySubaru.
- Engine `on` turns itself back off after the preset's run time.
- Polling every 15 or 30 (default) minutes, or every 1 or 3 hours, reads what the car last reported to Subaru and doesn't wake the car.
- If Subaru rejects the PIN, remote commands stop until a different PIN is saved, so retries can't lock the account.

## Settings (per login)

- **Units:** Hub default (follows the hub's temperature scale), Imperial (mi, psi, mpg) or Metric (km, kPa, L/100 km). Subaru sends both; this picks which one the devices show.
- **Use the hub's location as home** (default on): sets each car's `presence` from its distance to the hub's location (Settings > Location and Modes), within the **Home radius** you pick. Off: position is only stored.
- **Ignore GPS jitter under** (default Off): position changes smaller than this aren't recorded, so a parked car doesn't log new coordinates every poll.

## Setup

Two-step verification isn't built in yet. Each login needs a device ID already registered with MySubaru, for example `device_id` from a working `~/.subarulink.cfg`.

## Installation

### Option 1: Hubitat Package Manager (Recommended)
1. Open **Hubitat Package Manager** on your hub.
2. Select **Install** > **From a URL**.
3. Paste:
   ```text
   https://raw.githubusercontent.com/mpalermo73/hubitat/main/subarulink/packageManifest.json
   ```

### Option 2: Manual
1. **Drivers Code** > **New Driver** for each file in [`drivers/`](drivers/).
2. **Apps Code** > **New App** for [`apps/subarulinkApp.groovy`](apps/subarulinkApp.groovy) and [`apps/subarulinkAccountApp.groovy`](apps/subarulinkAccountApp.groovy).
3. **Apps** > **Add User App** > **MySubaru** > **Add a MySubaru login**, enter email, password, PIN and device ID.

## Multiple logins

Add one login per MySubaru account under the **MySubaru** app; each is listed by its email. New cars on a login are picked up automatically. A car shared by two logins gets one device, under whichever login added it first.

---

## Credits

API endpoints and flow are from [G-Two/subarulink](https://github.com/G-Two/subarulink) (Apache-2.0).

## License

Apache License 2.0; see [LICENSE](LICENSE). This package is a Groovy port of G-Two/subarulink, which is under the same license.
