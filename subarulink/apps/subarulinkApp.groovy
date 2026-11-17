/**
 * MySubaru
 *
 * Parent app; holds one MySubaru Account child app per login.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

definition(
    name: "MySubaru",
    namespace: "subarulink",
    author: "Matt Palermo",
    description: "Lock, unlock, remote start and status for vehicles on MySubaru Connected Services.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "MySubaru", install: true, uninstall: true) {
        section {
            app(name: "accounts", appName: "MySubaru Account", namespace: "subarulink", title: "Add a MySubaru login", multiple: true)
        }
    }
}

def installed() {}

def updated() {}
