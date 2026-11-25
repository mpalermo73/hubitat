/**
 * MyFuelPortal Tank
 *
 * Child device of the MyFuelPortal Manager app; one per tank on the account.
 */

metadata {
    definition(name: "MyFuelPortal Tank", namespace: "myfuelportal", author: "Matt Palermo") {
        capability "Sensor"
        capability "Battery"
        capability "Refresh"

        attribute "tankLevel", "number"
        attribute "gallons", "number"
        attribute "capacity", "number"
        attribute "product", "string"
        attribute "deliveryMode", "string"
        attribute "pricePerGallon", "number"
        attribute "readingDate", "string"
        attribute "lastDelivery", "string"
        attribute "nextDelivery", "string"
        attribute "pendingDelivery", "string"
        attribute "ownership", "string"
        attribute "address", "string"
        attribute "accountBalance", "number"
        attribute "accountNumber", "string"
        attribute "accountName", "string"
        attribute "customerSince", "string"
        attribute "dailyUsage", "number"
        attribute "daysUntilEmpty", "number"
        attribute "costToFill", "number"
        attribute "lastUpdated", "string"
    }

    preferences {
        input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
    }
}

def installed() {
    sendEvent(name: "tankLevel", value: 0, unit: "%")
}

def refresh() {
    parent.refresh()
}

def updateTank(Map tank) {
    if (logEnable) log.debug "updateTank: ${tank}"
    Map units = [tankLevel: "%", gallons: "gal", capacity: "gal", pricePerGallon: "\$/gal", accountBalance: "\$"]
    [tankLevel: tank.level, gallons: tank.gallons, capacity: tank.capacity, product: tank.product,
     deliveryMode: tank.deliveryMode, pricePerGallon: tank.pricePerGallon, readingDate: tank.readingDate,
     lastDelivery: tank.lastDelivery, nextDelivery: tank.nextDelivery, pendingDelivery: tank.pendingDelivery, ownership: tank.ownership, address: tank.address, accountBalance: tank.accountBalance,
     accountNumber: tank.accountNumber, accountName: tank.accountName, customerSince: tank.customerSince].each { k, v ->
        if (v != null) sendEvent(name: k, value: v, unit: units[k])
    }
    if (tank.level != null) sendEvent(name: "battery", value: tank.level, unit: "%")
    // Propane fills to 80% of capacity, liquid fuels to 90%.
    def price = tank.pricePerGallon ?: tank.manualPrice
    if (tank.gallons != null && tank.capacity && price) {
        double fill = !tank.product || tank.product =~ /(?i)propane|\bLPG?\b/ ? 0.8 : 0.9
        double cost = Math.max(0d, tank.capacity * fill - tank.gallons) * price
        sendEvent(name: "costToFill", value: Math.round(cost * 100) / 100.0d, unit: "\$")
    }
    if (tank.gallons != null) updateUsage(tank.readingDate ?: today(), tank.gallons, tank.capacity)
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// Average daily drop over the last 14 days, one reading per date; a rise beyond gauge jitter is a fill, so that day is skipped.
private void updateUsage(String day, Double gallons, Double capacity) {
    List readings = state.readings ?: []
    if (!readings || day > readings[-1].date) readings << [date: day, gallons: gallons]
    readings = readings.findAll { daysBetween(it.date, day) <= 14 }
    state.readings = readings

    double burned = 0
    int counted = 0
    for (int i = 1; i < readings.size(); i++) {
        double drop = readings[i - 1].gallons - readings[i].gallons
        if (drop < -(capacity ? capacity * 0.05 : 10)) continue
        burned += drop
        counted += daysBetween(readings[i - 1].date, readings[i].date)
    }
    if (counted < 7) return

    double usage = Math.max(0d, Math.round(burned / counted * 100) / 100.0d)
    long days = usage > 0 ? (Math.floor(gallons / usage) as long) - Math.max(0, daysBetween(day, today())) : 999L
    sendEvent(name: "dailyUsage", value: usage, unit: "gal/day")
    sendEvent(name: "daysUntilEmpty", value: Math.max(0L, Math.min(999L, days)), unit: "days")
}

String today() {
    return new Date().format("yyyy-MM-dd", location.timeZone)
}

private int daysBetween(String from, String to) {
    def fmt = new java.text.SimpleDateFormat("yyyy-MM-dd")
    fmt.setTimeZone(TimeZone.getTimeZone("UTC"))
    return (int) ((fmt.parse(to).time - fmt.parse(from).time) / 86400000L)
}
