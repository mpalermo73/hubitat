/**
 * MySubaru Tire
 *
 * Component child of a MySubaru Vehicle device. Tire pressure and the recommended pressure for that axle.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

metadata {
    definition(name: "MySubaru Tire", namespace: "subarulink", author: "Matt Palermo") {
        capability "Sensor"
        capability "PressureMeasurement"
        capability "Refresh"

        attribute "recommendedPressure", "number"
    }
}

def refresh() { parent.refresh() }

def update(Map values) {
    values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
}
