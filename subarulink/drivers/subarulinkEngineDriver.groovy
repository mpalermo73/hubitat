/**
 * MySubaru Engine
 *
 * Component child of a MySubaru Vehicle device. Remote start; on starts with the climate preset preference, off stops.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

metadata {
    definition(name: "MySubaru Engine", namespace: "subarulink", author: "Matt Palermo") {
        capability "Actuator"
        capability "Switch"
        capability "Refresh"

        attribute "preset", "string"

        command "remoteStart", [[name: "Climate preset", type: "STRING", description: "Preset name; blank uses the preference"]]
    }
    preferences {
        input "climatePreset", "text", title: "Remote start climate preset", required: false
    }
}

def installed() {
    sendEvent(name: "switch", value: "off")
}

def on() { remoteStart() }
def off() { parent.childCommand("remoteStop") }
def remoteStart(String preset = null) { parent.childCommand("remoteStart", preset ?: climatePreset) }

def refresh() { parent.refresh() }

def update(Map values) {
    values.each { k, v -> sendEvent(v instanceof Map ? [name: k] + v : [name: k, value: v]) }
}
