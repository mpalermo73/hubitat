# MyFuelPortal Integration for Hubitat

A Hubitat Elevation integration for propane and fuel accounts hosted on **MyFuelPortal** (used by regional suppliers like Heller's Gas, Koppy's Propane, and others).

It logs into your provider's portal, scrapes the Tank page and account balance, and keeps one child device per tank.

---

## Features

- **Any MyFuelPortal provider:** `hellersgas` expands to `https://hellersgas.myfuelportal.com`; full or vanity domains work too.
- **Polling:** every 1, 3, 6, 12 (default), or 24 hours, plus an "Update now" button in the app and a `Refresh` command on each tank.
- **Multiple tanks and accounts:** one `MyFuelPortal Tank` device per tank, labelled by service address (e.g. "Propane Tank - 164 Sycamore Dr") with the portal's tank name as the device name. Add one app instance per account.
- **Attributes:** `tankLevel` (%), `battery` (%, mirrors `tankLevel`), `gallons`, `capacity`, `product`, `deliveryMode`, `pricePerGallon`, `readingDate`, `lastDelivery`, `nextDelivery`, `pendingDelivery`, `ownership` (e.g. Leased), `address`, `accountBalance`, `accountNumber`, `accountName`, `customerSince`, `dailyUsage`, `daysUntilEmpty`, `costToFill`, `lastUpdated`.
- **Usage estimate:** `dailyUsage` (gal/day) is the average daily drop in gallons over the last 14 days, skipping the day of a fill; it appears once 7 days are counted; `daysUntilEmpty` is current gallons ÷ that rate, rounded down, counted down from the last reading, capped at 999 when usage is zero.
- **Cost to fill:** `costToFill` ($) is (fill level − current gallons) × price per gallon, where the fill level is 80% of capacity for propane (or when the portal names no product) and 90% for any other fuel. The price comes from the portal when it publishes one, otherwise from the price setting in the app; an empty or 0 setting uses $3.00.
- **Pending delivery:** `pendingDelivery` is the date a delivery was ordered for this tank, from the portal home page's Pending Deliveries table, or `none` when nothing is pending. Works for orders placed by phone, which never show as `nextDelivery`.
- **Hubitat Package Manager (HPM) Ready:** Native manifest included for easy updates.

---

## Installation

### Option 1: Hubitat Package Manager (Recommended)
1. Open **Hubitat Package Manager** on your hub.
2. Select **Install** > **From a URL**.
3. Paste the raw link to `packageManifest.json` from this repository:
   ```text
   https://raw.githubusercontent.com/mpalermo73/hubitat/main/myfuelportal/packageManifest.json
   ```

### Option 2: Manual
1. **Drivers Code** > **New Driver**, paste [`drivers/myFuelPortalTankDriver.groovy`](drivers/myFuelPortalTankDriver.groovy), save.
2. **Apps Code** > **New App**, paste [`apps/myFuelPortalApp.groovy`](apps/myFuelPortalApp.groovy), save.
3. Open **Integrations** and add **MyFuelPortal Manager**; enter provider, email, password.

---

## Credits

Login flow and page markup are based on the Home Assistant integrations
[dawg-io/homeassistant-myfuelportal](https://github.com/dawg-io/homeassistant-myfuelportal),
[DeltaNu1142/MyFuelPortal](https://github.com/DeltaNu1142/MyFuelPortal), and
[jterrace/ha_my_fuel_portal](https://github.com/jterrace/ha_my_fuel_portal).
