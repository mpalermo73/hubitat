/**
 * MySubaru Windows
 *
 * Component child of a MySubaru Vehicle device. Window and sunroof status (open, vented, closed, unknown).
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

metadata {
    definition(name: "MySubaru Windows", namespace: "subarulink", author: "Matt Palermo") {
        capability "Sensor"
        capability "ContactSensor"
        capability "Refresh"

        attribute "frontLeft", "string"
        attribute "frontRight", "string"
        attribute "rearLeft", "string"
        attribute "rearRight", "string"
        attribute "sunroof", "string"
    }
}

def refresh() { parent.refresh() }

def update(Map values) {
    values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
}
