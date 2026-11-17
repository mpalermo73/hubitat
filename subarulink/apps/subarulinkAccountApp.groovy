/**
 * MySubaru Account
 *
 * Child of the MySubaru app, one per login. Talks to the MySubaru Connected Services mobile API
 * and keeps one child device per vehicle on the login.
 *
 * SPDX-License-Identifier: Apache-2.0
 * Port of github.com/G-Two/subarulink (Apache-2.0), rewritten in Groovy for Hubitat.
 */

import groovy.transform.Field

@Field static final String API_VERSION = "/g2v33"
@Field static final Map API_SERVER = [USA: "mobileapi.prod.subarucs.com", CAN: "mobileapi.ca.prod.subarucs.com"]
@Field static final Map API_APP = [USA: "com.subaru.telematics.app.remote", CAN: "ca.subaru.telematics.remote"]
@Field static final long SESSION_MAX_MS = 240 * 60000L
@Field static final List SESSION_ERRORS = ["403-soa-unableToParseResponseBody", "InvalidToken"]
@Field static final List BUSY_ERRORS = ["ServiceAlreadyStarted", "SXM40009"]
@Field static final List PIN_ERRORS = ["InvalidCredentials", "SXM40006"]
@Field static final List VERSION_PROBE_PATHS = ["/login.json", "/validateSession.json", "/selectVehicle.json"]
@Field static final List SECRET_KEYS = ["password", "pin", "loginUsername", "deviceId"]
@Field static final List BAD_VALUES = ["16383", "65535", "32767"]
@Field static final String STATUS_URL = "/service/api_gen/remoteService/status.json"
@Field static final String G1_HORN_STATUS_URL = "/service/g1/hornLights/status.json"
@Field static final Map COMMANDS = [
    lock: [path: "/service/api_gen/lock/execute.json", data: [forceKeyInCar: false]],
    unlock: [path: "/service/api_gen/unlock/execute.json", data: [unlockDoorType: "ALL_DOORS_CMD"]],
    unlockDriverDoor: [path: "/service/api_gen/unlock/execute.json", data: [unlockDoorType: "FRONT_LEFT_DOOR_CMD"]],
    unlockTailgate: [path: "/service/api_gen/unlock/execute.json", data: [unlockDoorType: "TAILGATE_DOOR_CMD"]],
    hornLights: [path: "/service/api_gen/hornLights/execute.json", g1Poll: G1_HORN_STATUS_URL],
    hornLightsStop: [path: "/service/api_gen/hornLights/stop.json", g1Poll: G1_HORN_STATUS_URL],
    lights: [path: "/service/api_gen/lightsOnly/execute.json", g1Poll: G1_HORN_STATUS_URL],
    lightsStop: [path: "/service/api_gen/lightsOnly/stop.json", g1Poll: G1_HORN_STATUS_URL],
    locate: [path: "/service/g2/vehicleStatus/execute.json", poll: "/service/g2/vehicleStatus/locationStatus.json",
             g1Path: "/service/g1/vehicleLocate/execute.json", g1Poll: "/service/g1/vehicleLocate/status.json"],
    remoteStart: [path: "/service/g2/engineStart/execute.json", needs: ["RES", "PHEV"]],
    remoteStop: [path: "/service/g2/engineStop/execute.json", needs: ["RES", "PHEV"]],
    chargeStart: [path: "/service/g2/phevChargeNow/execute.json", needs: ["PHEV"]]
]
@Field static final Map DOORS = [frontLeft: "FrontLeft", frontRight: "FrontRight", rearLeft: "RearLeft", rearRight: "RearRight", tailgate: "Boot", hood: "EngineHood"]
@Field static final Map WINDOWS = [frontLeft: "FrontLeft", frontRight: "FrontRight", rearLeft: "RearLeft", rearRight: "RearRight", sunroof: "Sunroof"]
@Field static final Map TIRES = [FL: "FrontLeft", FR: "FrontRight", RL: "RearLeft", RR: "RearRight"]

definition(
    name: "MySubaru Account",
    namespace: "subarulink",
    parent: "subarulink:MySubaru",
    author: "Matt Palermo",
    description: "One MySubaru login and its vehicles.",
    category: "Convenience",
    iconUrl: "",
    iconX2Url: "",
    singleInstance: false,
    singleThreaded: true
)

preferences {
    page(name: "mainPage")
}

def mainPage() {
    dynamicPage(name: "mainPage", title: "MySubaru login", install: true, uninstall: true) {
        section(header("Auth")) {
            input "country", "enum", title: "Country", options: ["USA", "CAN"], defaultValue: "USA", required: true
            input "username", "text", title: "MySubaru email", required: true
            input "password", "password", title: "MySubaru password", required: true
            input "pin", "password", title: "MySubaru PIN (needed for remote commands)", required: false
            input "deviceId", "text", title: "Registered device ID", required: true
            paragraph "<small style='color:#666'>A device ID already approved by MySubaru two-step verification, e.g. <code>device_id</code> from <code>~/.subarulink.cfg</code>.</small>"
        }
        section(header("Display and location")) {
            input "units", "enum", title: "Units", options: [hub: "Hub default (${hubMetric() ? 'metric' : 'imperial'})", imperial: "Imperial (mi, psi, mpg)", metric: "Metric (km, kPa, L/100 km)"], defaultValue: "hub", required: true, submitOnChange: true
            input "useHubLocation", "bool", title: "Use the hub's location as home (sets each car's presence)", defaultValue: true, submitOnChange: true
            if (useHubLocation != false) {
                input "homeRadius", "enum", title: "Home radius", options: distanceOptions([25, 50, 100, 200, 400, 800]), required: true
                if (location.latitude == null) paragraph "The hub has no location set (Settings > Location and Modes), so presence can't be worked out."
            }
            input "positionJitter", "enum", title: "Ignore GPS jitter under", options: [0: "Off"] + distanceOptions([10, 25, 50, 100]), defaultValue: 0, required: true
        }
        section(header("Polling")) {
            input "pollInterval", "enum", title: "Poll interval", required: true, options: ["15 Minutes", "30 Minutes", "1 Hour", "3 Hours"], defaultValue: "30 Minutes"
            input "txtEnable", "bool", title: "Enable operation logging", defaultValue: true
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
        section(header("Status")) {
            if (state.pinLockout) paragraph "Remote commands disabled: Subaru rejected the PIN. Save a corrected PIN to re-enable."
            paragraph state.lastError ? "Last error: ${state.lastError}" : (state.lastSuccess ? "Last update: ${state.lastSuccess}" : "Not yet updated")
        }
    }
}

private String header(String title) {
    return "<div style='background-color:#2a4a6e;color:#fff;font-weight:bold;padding:6px 10px;border-radius:4px;margin-top:4px;'>${title}</div>"
}

def installed() {
    initialize()
}

def updated() {
    unschedule("refresh")
    if (state.pinLockout && pin?.hashCode() != state.lockedPin) state.remove("pinLockout")
    resetSession()
    state.rediscover = true
    initialize()
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "MySubaru: debug logging disabled"
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    app.updateLabel("${username}${country == 'CAN' ? ' (CAN)' : ''}")
    switch (pollInterval) {
        case "15 Minutes": runEvery15Minutes("refresh"); break
        case "1 Hour": runEvery1Hour("refresh"); break
        case "3 Hours": runEvery3Hours("refresh"); break
        default: runEvery30Minutes("refresh"); break
    }
    runIn(2, "refresh")
}

// ---------- polling ----------

def refresh() {
    try {
        if (state.vehicles == null || state.rediscover || (state.vins as Set) != (state.vehicles.keySet() + (state.skipped ?: []))) discover()
        List errors = []
        state.vehicles.keySet().each { vin ->
            try {
                refreshVehicle(vin)
            } catch (e) {
                errors << "${vin}: ${e.message ?: e}"
            }
        }
        if (errors) throw new Exception(errors.join("; "))
        succeeded()
    } catch (e) {
        fail(e)
    }
}

def refreshVin(Map data) {
    if (!state.vehicles?.containsKey(data.vin)) return refresh()
    try {
        refreshVehicle(data.vin)
        succeeded()
    } catch (e) {
        fail(e)
    }
}

private void discover() {
    login()
    Map vehicles = [:]
    List skipped = []
    state.vins.each { String vin ->
        Map v = selectVehicle(vin)
        String dni = "subarulink_${vin}"
        if (!getChildDevice(dni)) {
            String label = v.nickname ?: "${v.modelYear} ${v.modelName}"
            try {
                addChildDevice("subarulink", "MySubaru Vehicle", dni, [name: "${v.modelYear} ${v.modelName}", label: label, isComponent: false])
                logInfo "created ${label}"
            } catch (e) {
                if (e.toString().contains("UnknownDeviceTypeException")) throw new Exception("install the MySubaru Vehicle driver: ${e.message}")
                log.warn "MySubaru: skipping ${label} (${vin}), probably already added under another login: ${e.message}"
                skipped << vin
                return
            }
        }
        vehicles[vin] = vehicleInfo(v)
    }
    state.vehicles = vehicles
    state.skipped = skipped
    getChildDevices().findAll { !((it.deviceNetworkId - "subarulink_") in state.vins) }.each {
        log.warn "MySubaru: ${it.displayName} is no longer on this login; delete it if the car is gone"
    }
    state.remove("rediscover")
}

private Map vehicleInfo(Map v) {
    return [features: v.features ?: [], subscriptionFeatures: v.subscriptionFeatures ?: [], subscriptionStatus: v.subscriptionStatus]
}

// Each section becomes a child device of the car; the car device creates children as their sections first appear.
private void refreshVehicle(String vin) {
    def car = getChildDevice("subarulink_${vin}")
    if (!car) {
        logDebug "no device for ${vin}; save the app to recreate it"
        return
    }
    Map info = [:]
    Map out = [car: info]

    Map vs = call(vin, "get", "/vehicleStatus.json").data ?: [:]
    Map v = state.vehicles[vin]
    boolean km = metric()
    info.odometer = good(km ? vs.odometerValueKilometers : vs.odometerValue)
    info.distanceToEmpty = good(km ? vs.distanceToEmptyFuelKilometers10s : vs.distanceToEmptyFuelMiles10s)
    info.avgFuelConsumption = good(km ? vs.avgFuelConsumptionLitersPer100Kilometers : vs.avgFuelConsumptionMpg)
    info.fuelPercent = good(vs.remainingFuelPercent)
    info.vehicleState = vs.vehicleStateType
    info.lastReported = vs.eventDateStr
    info.putAll(position(vs))
    TIRES.each { k, n ->
        def pressure = good(vs["tirePressure${n}${km ? '' : 'Psi'}"])
        Integer psi = recommendedPressure(v, k)
        if (pressure != null) out["tire${k}"] = [pressure: pressure, recommendedPressure: km && psi ? Math.round(psi * 6.894757) : psi]
    }

    boolean isRemote = remote(v)
    if (isRemote) out.doors = [:]
    if (isRemote && v.features.any { it in ["RES", "PHEV"] }) out.engine = [:]

    String partial = null
    if (isRemote && gen(v) == "g2") try {
        Map c = call(vin, "get", "/service/g2/condition/execute.json").data?.result ?: [:]
        Map doors = DOORS.collectEntries { k, n -> [k, c["door${n}Position"]?.toLowerCase()] }.findAll { it.value in ["open", "closed"] }
        if (doors) out.doors = doors + [contact: doors.containsValue("open") ? "open" : "closed"]
        List locks = DOORS.values().collect { c["door${it}LockStatus"] }
        if ("UNLOCKED" in locks) out.doors.lock = "unlocked"
        else if ("LOCKED" in locks) out.doors.lock = "locked"

        Map windows = WINDOWS.collectEntries { k, n -> [k, [OPEN: "open", VENTED: "vented", CLOSE: "closed"][c["window${n}Status"]] ?: "unknown"] }
        if (windows.values().any { it != "unknown" }) out.windows = windows + [contact: windows.values().any { it in ["open", "vented"] } ? "open" : "closed"]

        if ("PHEV" in v.features) {
            out.charging = [battery: good(c.evStateOfChargePercent), powerSource: c.evIsPluggedIn in ["LOCKED_CONNECTED", "UNLOCKED_CONNECTED"] ? "mains" : "battery", plug: c.evIsPluggedIn,
                            chargerState: c.evChargerStateType, evRange: good(km ? vs.evDistanceToEmptyKilometers : vs.evDistanceToEmptyMiles), timeToFull: good(c.evTimeToFullyCharged),
                            switch: c.evChargerStateType == "CHARGING" ? "on" : "off"]
        }
        if (c.lastUpdatedTime) info.lastReported = c.lastUpdatedTime
        info.putAll(position(call(vin, "get", "/service/g2/locate/execute.json").data?.result ?: [:]))

        List health = call(vin, "get", "/vehicleHealth.json").data?.vehicleHealthItems ?: []
        List warnings = health.findAll { it.isTrouble && it.featureCode in v.features }*.featureCode.collect { it - "_MIL" }
        info.warningLights = warnings ? warnings.join(", ") : "none"
    } catch (e) {
        partial = e.message ?: e.toString()
        log.warn "MySubaru: ${car.displayName}: condition data unavailable this poll: ${partial}"
    }
    if (info.latitude != null) {
        if (useHubLocation != false && homeRadius && location.latitude != null) {
            info.presence = distanceMeters(info.latitude, info.longitude, location.latitude, location.longitude) <= (homeRadius as int) ? "present" : "not present"
        }
        def lat = car.currentValue("latitude"), lon = car.currentValue("longitude")
        if (lat != null && lon != null && distanceMeters(info.latitude, info.longitude, lat, lon) < ((positionJitter ?: 0) as int)) {
            info.remove("latitude")
            info.remove("longitude")
        }
    }

    Map sections = out.collectEntries { k, m -> [k, withUnits(m.findAll { it.value != null })] }
    logInfo "${car.displayName}: ${sections.findAll { it.key != 'car' }.keySet()} odometer=${sections.car.odometer}"
    car.updateVehicle(sections)
    if (partial) throw new Exception(partial)
}

private Integer recommendedPressure(Map v, String tire) {
    String f = v.features.find { it.startsWith(tire.startsWith("F") ? "TIF_" : "TIR_") }
    return f?.substring(4)?.isInteger() ? f.substring(4) as int : null
}

private Map position(Map m) {
    if (m.latitude == null || m.longitude == null || (m.latitude == 90 && m.longitude == 180)) return [:]
    return [latitude: m.latitude, longitude: m.longitude]
}

// ---------- units ----------

@Field static final List DISTANCE_KEYS = ["odometer", "distanceToEmpty", "evRange"]
@Field static final List PRESSURE_KEYS = ["pressure", "recommendedPressure"]
@Field static final Map FIXED_UNITS = [fuelPercent: "%", battery: "%", timeToFull: "min"]

private boolean hubMetric() {
    return location.temperatureScale == "C"
}

private boolean metric() {
    return units == "metric" || ((units ?: "hub") == "hub" && hubMetric())
}

// Values with a unit go to the devices as [value, unit]; Subaru sends each in both systems.
private Map withUnits(Map m) {
    boolean km = metric()
    return m.collectEntries { k, v ->
        if (k in DISTANCE_KEYS) return [k, [value: v, unit: km ? "km" : "mi"]]
        if (k in PRESSURE_KEYS) return [k, [value: v, unit: km ? "kPa" : "psi"]]
        if (k == "avgFuelConsumption") return [k, [value: v, unit: km ? "L/100 km" : "mpg"]]
        if (FIXED_UNITS[k]) return [k, [value: v, unit: FIXED_UNITS[k]]]
        return [k, v]
    }
}

private Map distanceOptions(List meters) {
    return meters.collectEntries { int m -> [m, metric() ? "${m} m" : "${Math.round(m * 3.28084)} ft"] }
}

private double distanceMeters(lat1, lon1, lat2, lon2) {
    double dLat = Math.toRadians((lat2 as double) - (lat1 as double)), dLon = Math.toRadians((lon2 as double) - (lon1 as double))
    double a = Math.sin(dLat / 2) ** 2 + Math.cos(Math.toRadians(lat1 as double)) * Math.cos(Math.toRadians(lat2 as double)) * Math.sin(dLon / 2) ** 2
    return 6371000 * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a))
}

private good(value) {
    return value == null || value.toString() in BAD_VALUES ? null : value
}

// ---------- remote commands ----------

def vehicleCommand(String vin, String name, String preset = null) {
    def car = getChildDevice("subarulink_${vin}")
    try {
        Map v = state.vehicles?.get(vin)
        Map cmd = COMMANDS[name]
        if (!v) throw new Exception("vehicle not discovered yet; press Refresh and try again")
        if (!remote(v)) throw new Exception("an active Security Plus subscription is required for ${name}")
        if (cmd.needs && !v.features.any { it in cmd.needs }) throw new Exception("this vehicle does not support ${name}")
        if (state.pinLockout) throw new Exception("remote commands disabled after a PIN rejection; fix the PIN in the app")
        if (!pin) throw new Exception("set the PIN in the app")
        Map data = cmd.data ?: [:]
        if (name == "remoteStart") data = climatePreset(vin, v, preset)
        car.commandUpdate(name, "sending")
        execute(vin, name, data, 0)
    } catch (e) {
        car?.commandUpdate(name, "failed", e.message)
        fail(e)
    }
}

def retryCommand(Map d) {
    try {
        execute(d.vin, d.name, d.data, d.tries)
    } catch (e) {
        getChildDevice("subarulink_${d.vin}")?.commandUpdate(d.name, "failed", e.message)
        fail(e)
    }
}

private void execute(String vin, String name, Map data, int tries) {
    Map v = state.vehicles[vin]
    Map cmd = COMMANDS[name]
    boolean g1 = gen(v) == "g1"
    Map body = [pin: pin, delay: 0, vin: vin] + data
    Map r = call(vin, "post", g1 && cmd.g1Path ? cmd.g1Path : cmd.path.replace("api_gen", gen(v)), [json: body], false)
    if (r.errorCode in BUSY_ERRORS) {
        if (tries >= 3) throw new Exception("vehicle busy with another command")
        logDebug "${name}: vehicle busy, retrying in 10s"
        runIn(10, "retryCommand", [overwrite: false, data: [vin: vin, name: name, data: data, tries: tries + 1]])
        return
    }
    checkError(r)
    String reqId = r.data?.serviceRequestId
    if (!reqId) throw new Exception("no serviceRequestId in ${r}")
    String poll = g1 && cmd.g1Poll ? cmd.g1Poll : (cmd.poll ?: STATUS_URL).replace("api_gen", gen(v))
    runIn(2, "pollCommand", [overwrite: false, data: [vin: vin, name: name, data: data, reqId: reqId, poll: poll, left: 20, wait: 2.0]])
}

def pollCommand(Map d) {
    def car = getChildDevice("subarulink_${d.vin}")
    try {
        Map s = [:]
        try {
            s = call(d.vin, "get", d.poll, [query: [serviceRequestId: d.reqId]]).data ?: [:]
        } catch (e) {
            if (d.left <= 1) throw e
            logDebug "${d.name}: status check failed, retrying: ${e.message ?: e}"
        }
        if (s.remoteServiceState == "finished") {
            if (!s.success) throw new Exception([s.errorCode, s.errorDescription].findAll().join(": ") ?: "rejected by vehicle")
            logInfo "${car.displayName}: ${d.name} succeeded"
            car.commandUpdate(d.name, "succeeded", null, d.data)
            return
        }
        if (d.left <= 1) throw new Exception("no completion from vehicle")
        double wait = s.remoteServiceState == "started" ? Math.min((d.wait as double) * 1.5, 15.0d) : (d.wait as double)
        car.commandUpdate(d.name, s.remoteServiceState ?: "waiting")
        runIn(s.remoteServiceState == "scheduled" ? 15 : Math.round(wait) as int, "pollCommand", [overwrite: false, data: d + [left: d.left - 1, wait: wait]])
    } catch (e) {
        car?.commandUpdate(d.name, "failed", e.message)
        fail(e)
    }
}

// Built-in presets arrive as a list of JSON strings, user presets as one JSON string (or null).
private Map climatePreset(String vin, Map v, String name) {
    String type = "PHEV" in v.features ? "phev" : "gas"
    List presets = (call(vin, "get", "/service/g2/climatePresetSettings/fetch.json").data ?: []).collect { parseJson(it) }.findAll { it.vehicleType == type }
    def user = call(vin, "get", "/service/g2/remoteEngineStartSettings/fetch.json").data
    if (user instanceof String) presets.addAll(parseJson(user))
    presets = presets.findAll { it.disabled?.toString() != "true" }
    Map preset = presets.find { it.name == name }
    if (!preset) throw new Exception("no climate preset '${name ?: ''}'; available: ${presets*.name.join(', ')}")
    preset = new LinkedHashMap(preset)
    ["heatedSeatFrontLeft", "heatedSeatFrontRight"].each { k ->
        String seat = preset[k]?.toString()?.toUpperCase() ?: ""
        if ((seat.contains("_HEAT") && !("RHSF" in v.features)) || (seat.contains("_COOL") && !("RVFS" in v.features))) preset[k] = "OFF"
    }
    Map saved = call(vin, "post", "/service/g2/remoteEngineQuickStartSettings/save.json", [json: preset])
    if (!saved.success) throw new Exception("saving climate preset '${name}' failed: ${saved}")
    return preset
}

// ---------- session ----------

private void login() {
    resetSession()
    Map form = [env: "cloudprod", loginUsername: username, password: password, deviceId: deviceId?.trim(),
                passwordToken: "None", selectedVin: "None", pushToken: "None", deviceType: "android"]
    Map r = api("post", "/login.json", [form: form])
    if (!r.success) throw new Exception("login failed: ${r.errorCode ?: r}")
    if (!r.data?.deviceRegistered) throw new Exception("the device ID is not registered with MySubaru two-step verification")
    state.loginTime = now()
    state.vins = r.data.vehicles*.vin
    logDebug "login ok, vehicles ${state.vins}"
}

private Map selectVehicle(String vin) {
    Map r = api("get", "/selectVehicle.json", [query: [vin: vin, _: (now() / 1000) as long]])
    if (!r.success) {
        resetSession()
        throw new Exception("selecting vehicle ${vin} failed: ${r.errorCode}")
    }
    state.currentVin = vin
    if (state.vehicles?.containsKey(vin)) state.vehicles[vin] = vehicleInfo(r.data)
    return r.data
}

private void ensureSession(String vin) {
    if (!state.cookies || now() - (state.loginTime ?: 0) > SESSION_MAX_MS) login()
    else if (now() - (state.lastOk ?: 0) > 5 * 60000L && !api("get", "/validateSession.json").success) login()
    if (state.currentVin != vin) selectVehicle(vin)
}

private void resetSession() {
    state.remove("cookies")
    state.remove("currentVin")
}

// Session-expiry error codes get one fresh login and retry.
private Map call(String vin, String method, String path, Map opts = [:], boolean check = true) {
    ensureSession(vin)
    Map r = api(method, path, opts)
    if (r.errorCode in SESSION_ERRORS) {
        logDebug "${r.errorCode} on ${path}, logging in again"
        resetSession()
        ensureSession(vin)
        r = api(method, path, opts)
    }
    if (check) checkError(r)
    return r
}

private void checkError(Map r) {
    String error = r.errorCode
    if (!error || error in BUSY_ERRORS) return
    if (error in PIN_ERRORS) {
        state.pinLockout = true
        state.lockedPin = pin?.hashCode()
        throw new Exception("Subaru rejected the PIN; remote commands disabled to avoid an account lockout")
    }
    if (error == "DEVICE_NOT_AUTHENTICATED") throw new Exception("the device ID needs two-step verification")
    throw new Exception("API error ${error}")
}

// A 404 on an endpoint every account has usually means Subaru bumped the API version (/g2v33 -> /g2v34);
// try the next few and keep whichever answers. Error replies that carry an errorCode go back to the caller.
private Map api(String method, String path, Map opts = [:]) {
    String current = state.apiVersion ?: API_VERSION
    int base = (current =~ /\d+$/)[0] as int
    int tries = path in VERSION_PROBE_PATHS ? 5 : 0
    for (int bump = 0; bump <= tries; bump++) {
        String version = current.replaceFirst(/\d+$/, "${base + bump}")
        Map result = request(method, "https://${API_SERVER[country ?: 'USA']}${version}${path}", opts)
        if (result.status == 404) continue
        if (result.status > 299 && !(result.data instanceof Map && result.data.errorCode)) throw new Exception("HTTP ${result.status} on ${path}")
        if (!(result.data instanceof Map) || !(result.data.containsKey("success") || result.data.containsKey("serviceType"))) {
            throw new Exception("unexpected response on ${path}: ${result.data}")
        }
        if (bump) {
            log.warn "MySubaru: API version moved to ${version}"
            state.apiVersion = version
        }
        if (result.data.success) state.lastOk = now()
        return result.data
    }
    throw new Exception(tries ? "HTTP 404 on ${path} for API versions ${current} through +${tries}" : "HTTP 404 on ${path}")
}

private Map request(String method, String uri, Map opts) {
    Map headers = [
        "User-Agent": "Mozilla/5.0 (Linux; Android 10; Android SDK built for x86 Build/QSR1.191030.002; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/74.0.3729.185 Mobile Safari/537.36",
        "Origin": "file://",
        "X-Requested-With": API_APP[country ?: "USA"],
        "Accept-Language": "en-US,en;q=0.9",
        "Accept": "*/*"
    ]
    if (state.cookies) headers.Cookie = state.cookies.collect { k, v -> "${k}=${v}" }.join("; ")
    Map params = [uri: uri, headers: headers, contentType: "application/json", timeout: 30]
    if (opts.query) params.query = opts.query
    if (opts.form != null) { params.requestContentType = "application/x-www-form-urlencoded"; params.body = opts.form }
    if (opts.json != null) { params.requestContentType = "application/json"; params.body = opts.json }
    logDebug "→ ${method.toUpperCase()} ${uri} ${redact(opts.query ?: opts.form ?: opts.json ?: '')}"
    Map result = [:]
    Closure handle = { resp ->
        Map cookies = state.cookies ?: [:]
        resp.getHeaders("Set-Cookie").each { h ->
            def m = (h.value =~ /^([^=;]+)=([^;]*)/)
            if (m.find()) cookies[m.group(1)] = m.group(2)
        }
        state.cookies = cookies
        result.status = resp.status
        result.data = resp.data
        logDebug "← ${resp.status} ${redact(resp.data)}"
    }
    try {
        method == "post" ? httpPost(params, handle) : httpGet(params, handle)
    } catch (groovyx.net.http.HttpResponseException e) {
        handle(e.response)
    }
    return result
}

private redact(obj) {
    if (obj instanceof Map) return obj.collectEntries { k, v -> [k, k in SECRET_KEYS ? "…" : redact(v)] }
    if (obj instanceof List) return obj.collect { redact(it) }
    return obj
}

// ---------- vehicle capabilities ----------

private boolean remote(Map v) {
    return v && v.subscriptionStatus == "ACTIVE" && v.subscriptionFeatures.any { it in ["REMOTE", "COMPANION_PLUS"] }
}

// g3 and g4 vehicles use the g2 endpoints.
private String gen(Map v) {
    return v.features.contains("g1") && !v.features.any { it in ["g2", "g3", "g4"] } ? "g1" : "g2"
}

// ---------- logging ----------

private void succeeded() {
    state.remove("lastError")
    state.lastSuccess = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
}

private void fail(e) {
    state.lastError = e.message ?: e.toString()
    log.error "MySubaru: ${state.lastError}"
}

private void logInfo(String msg) {
    if (txtEnable) log.info "MySubaru: ${msg}"
}

private void logDebug(String msg) {
    if (logEnable) log.debug "MySubaru: ${msg}"
}
