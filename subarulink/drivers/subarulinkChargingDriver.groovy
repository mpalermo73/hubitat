/**
 * MySubaru Charging
 *
 * Component child of a MySubaru Vehicle device. EV charge level and plug state; on starts charging (Subaru has no stop command).
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

metadata {
    definition(name: "MySubaru Charging", namespace: "subarulink", author: "Matt Palermo") {
        capability "Actuator"
        capability "Sensor"
        capability "Switch"
        capability "Battery"
        capability "PowerSource"
        capability "Refresh"

        attribute "chargerState", "string"
        attribute "plug", "string"
        attribute "evRange", "number"
        attribute "timeToFull", "number"
    }
}

def on() { parent.childCommand("chargeStart") }
def off() { log.warn "${device.displayName}: Subaru has no stop-charging command" }

def refresh() { parent.refresh() }

def update(Map values) {
    values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
}
