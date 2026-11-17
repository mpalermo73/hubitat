/**
 * MySubaru Vehicle
 *
 * Child device of a MySubaru Account app; one per vehicle. Creates its own child devices
 * (doors, windows, engine, charging, tires) as the vehicle first reports each of them.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

import groovy.transform.Field

@Field static final Map CHILDREN = [
    doors: [type: "MySubaru Doors", label: "Doors"],
    windows: [type: "MySubaru Windows", label: "Windows"],
    engine: [type: "MySubaru Engine", label: "Engine"],
    charging: [type: "MySubaru Charging", label: "Charging"],
    tireFL: [type: "MySubaru Tire", label: "Tire FL"],
    tireFR: [type: "MySubaru Tire", label: "Tire FR"],
    tireRL: [type: "MySubaru Tire", label: "Tire RL"],
    tireRR: [type: "MySubaru Tire", label: "Tire RR"]
]
metadata {
    definition(name: "MySubaru Vehicle", namespace: "subarulink", author: "Matt Palermo") {
        capability "Actuator"
        capability "Sensor"
        capability "Refresh"
        capability "PresenceSensor"
        capability "Alarm"

        attribute "odometer", "number"
        attribute "distanceToEmpty", "number"
        attribute "avgFuelConsumption", "number"
        attribute "fuelPercent", "number"
        attribute "vehicleState", "string"
        attribute "latitude", "number"
        attribute "longitude", "number"
        attribute "warningLights", "string"
        attribute "lastReported", "string"
        attribute "commandStatus", "string"
        attribute "lastUpdated", "string"

        command "locate"
    }
}

def installed() {
    sendEvent(name: "alarm", value: "off")
}

def refresh() { parent.refreshVin([vin: vin()]) }
def locate() { childCommand("locate") }
def both() { childCommand("hornLights") }
def siren() { childCommand("hornLights") }
def strobe() { childCommand("lights") }
def off() { childCommand(state.alarmCmd == "lights" ? "lightsStop" : "hornLightsStop") }

def childCommand(String name, String arg = null) {
    parent.vehicleCommand(vin(), name, arg)
}

def updateVehicle(Map sections) {
    sections.each { String kind, Map values ->
        if (kind == "car") values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
        else child(kind)?.update(values)
    }
    sendEvent(name: "lastUpdated", value: new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone))
}

// Status from the app as a remote command progresses; on success, mirrors the result onto the right device.
def commandUpdate(String name, String status, String detail = null, Map result = null) {
    sendEvent(name: "commandStatus", value: "${name}: ${status}${detail ? ': ' + detail : ''}")
    if (name in ["hornLights", "lights"] && status == "sending") {
        state.alarmCmd = name
        sendEvent(name: "alarm", value: name == "lights" ? "strobe" : "both")
    }
    if ((name in ["hornLights", "lights"] && status in ["succeeded", "failed"]) || (name in ["hornLightsStop", "lightsStop"] && status == "succeeded")) sendEvent(name: "alarm", value: "off")
    if (status != "succeeded") return
    switch (name) {
        case "lock": child("doors").update([lock: "locked"]); break
        case ["unlock", "unlockDriverDoor", "unlockTailgate"]: child("doors").update([lock: "unlocked"]); break
        case "remoteStart":
            child("engine").update([switch: "on", preset: result?.name])
            runIn(((result?.runTimeMinutes ?: 10) as int) * 60, "engineTimedOut")
            break
        case "remoteStop": unschedule("engineTimedOut"); engineTimedOut(); break
        case "locate": refresh(); break
    }
}

def engineTimedOut() {
    child("engine").update([switch: "off"])
}

private child(String kind) {
    Map spec = CHILDREN[kind]
    if (!spec) return null
    String dni = "${device.deviceNetworkId}_${kind}"
    def c = getChildDevice(dni)
    if (!c) c = addChildDevice("subarulink", spec.type, dni, [name: spec.type, label: "${device.displayName} ${spec.label}", isComponent: true])
    return c
}

private String vin() {
    return device.deviceNetworkId - "subarulink_"
}
