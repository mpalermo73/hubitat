/**
 * MySubaru Doors
 *
 * Component child of a MySubaru Vehicle device. Doors, hood, tailgate and locks.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

metadata {
    definition(name: "MySubaru Doors", namespace: "subarulink", author: "Matt Palermo") {
        capability "Actuator"
        capability "Sensor"
        capability "ContactSensor"
        capability "Lock"
        capability "Refresh"

        attribute "frontLeft", "string"
        attribute "frontRight", "string"
        attribute "rearLeft", "string"
        attribute "rearRight", "string"
        attribute "tailgate", "string"
        attribute "hood", "string"

        command "unlockDriverDoor"
        command "unlockTailgate"
    }
}

def installed() {
    sendEvent(name: "lock", value: "unknown")
}

def lock() { parent.childCommand("lock") }
def unlock() { parent.childCommand("unlock") }
def unlockDriverDoor() { parent.childCommand("unlockDriverDoor") }
def unlockTailgate() { parent.childCommand("unlockTailgate") }

def refresh() { parent.refresh() }

def update(Map values) {
    values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
}
