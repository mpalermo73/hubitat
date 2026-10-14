/**
 * MyFuelPortal Tank
 *
 * Child device of the MyFuelPortal Manager app; one per tank on the account.
 */

metadata {
    definition(name: "MyFuelPortal Tank", namespace: "myfuelportal", author: "Matt Palermo") {
        capability "Sensor"
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
        attribute "address", "string"
        attribute "accountBalance", "number"
        attribute "accountNumber", "string"
        attribute "accountName", "string"
        attribute "customerSince", "string"
        attribute "dailyUsage", "number"
        attribute "daysUntilEmpty", "number"
        attribute "lastUpdated", "string"
    }
}

def installed() {}

def refresh() {
    parent.refresh()
}

def updateTank(Map tank) {
    Map units = [tankLevel: "%", gallons: "gal", capacity: "gal", pricePerGallon: "\$/gal", accountBalance: "\$"]
    [tankLevel: tank.level, gallons: tank.gallons, capacity: tank.capacity, product: tank.product,
     deliveryMode: tank.deliveryMode, pricePerGallon: tank.pricePerGallon, readingDate: tank.readingDate,
     lastDelivery: tank.lastDelivery, nextDelivery: tank.nextDelivery, address: tank.address, accountBalance: tank.accountBalance,
     accountNumber: tank.accountNumber, accountName: tank.accountName, customerSince: tank.customerSince].each { k, v ->
        if (v != null) sendEvent(name: k, value: v, unit: units[k])
    }
    if (tank.gallons != null) updateUsage(tank.readingDate ?: today(), tank.gallons, tank.capacity, tank.lastDelivery)
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// One sample per reading date over the last 30 days. A new lastDelivery date, or a rise beyond monitor jitter,
// starts a new window; the last known rate carries over so daysUntilEmpty is recomputed on every poll.
private void updateUsage(String day, Double gallons, Double capacity, String lastDelivery) {
    List readings = state.readings ?: []
    boolean delivered = (lastDelivery && state.lastDelivery && lastDelivery != state.lastDelivery) ||
        (readings && gallons > readings[-1].gallons + (capacity ? capacity * 0.05 : 10))
    if (lastDelivery) state.lastDelivery = lastDelivery
    if (delivered) readings = []
    if (!readings || day > readings[-1].date) readings << [date: day, gallons: gallons]
    readings = readings.findAll { daysBetween(it.date, day) <= 30 }
    state.readings = readings

    if (readings.size() >= 2) {
        int span = daysBetween(readings[0].date, readings[-1].date)
        double used = readings[0].gallons - readings[-1].gallons
        if (span >= 1 && used >= 0) state.usage = Math.round(used / span * 100) / 100.0d
    }
    if (state.usage == null) return

    double usage = state.usage
    long days = usage > 0 ? Math.round(gallons / usage) - Math.max(0, daysBetween(day, today())) : 999L
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
