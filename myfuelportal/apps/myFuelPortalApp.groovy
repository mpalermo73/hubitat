/**
 * MyFuelPortal Manager
 *
 * Logs into a provider's MyFuelPortal site (e.g. hellersgas.myfuelportal.com),
 * scrapes /Tank and the account home page, and keeps one child device per tank.
 * Login flow and markup follow github.com/DeltaNu1142/MyFuelPortal.
 */

definition(
    name: "MyFuelPortal Manager",
    namespace: "myfuelportal",
    author: "Matt Palermo",
    description: "Integrates propane accounts hosted on MyFuelPortal into Hubitat.",
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
    dynamicPage(name: "mainPage", title: "MyFuelPortal", install: true, uninstall: true) {
        section("Provider") {
            input "providerDomain", "text", title: "Portal subdomain or domain — the \"youraccount\" part of youraccount.myfuelportal.com (or a full domain)", required: true
            input "username", "text", title: "Portal email", required: true
            input "password", "password", title: "Portal password", required: true
        }
        section("Polling") {
            input "pollInterval", "enum", title: "Poll interval", required: true, options: ["1 Hour", "3 Hours", "6 Hours", "12 Hours", "24 Hours"], defaultValue: "12 Hours"
            input "txtEnable", "bool", title: "Enable operation logging", defaultValue: true
            input "logEnable", "bool", title: "Enable debug logging", defaultValue: false
        }
        if (state.lastError) {
            section("Status") { paragraph "Last error: ${state.lastError}" }
        } else if (state.lastSuccess) {
            section("Status") { paragraph "Last update: ${state.lastSuccess}" }
        }
    }
}

def installed() {
    initialize()
}

def updated() {
    unschedule()
    initialize()
    if (logEnable) runIn(1800, "logsOff")
}

def logsOff() {
    app.updateSetting("logEnable", [value: "false", type: "bool"])
    log.info "MyFuelPortal: debug logging disabled"
}

def uninstalled() {
    getChildDevices().each { deleteChildDevice(it.deviceNetworkId) }
}

def initialize() {
    switch (pollInterval) {
        case "1 Hour": runEvery1Hour("refresh"); break
        case "3 Hours": runEvery3Hours("refresh"); break
        case "6 Hours": schedule("0 ${new Random().nextInt(60)} */6 ? * *", "refresh"); break
        case "24 Hours": schedule("0 ${new Random().nextInt(60)} 4 ? * *", "refresh"); break
        default: schedule("0 ${new Random().nextInt(60)} */12 ? * *", "refresh"); break
    }
    runIn(2, "refresh")
}

def refresh() {
    try {
        Map cookies = login()
        String tankPage = getPage("/Tank", cookies)
        if (tankPage.contains('id="EmailAddress"')) throw new Exception("session not authenticated loading /Tank")
        List tanks = parseTanks(tankPage)
        Map account = [:]
        try {
            account = parseAccount(getPage("/", cookies))
        } catch (e) {
            log.warn "MyFuelPortal: account info unavailable: ${e.message}"
        }
        if (!tanks) log.warn "MyFuelPortal: no tanks found on ${baseUrl()}/Tank"
        Set keys = []
        tanks.eachWithIndex { tank, i ->
            String key = tank.id ?: tank.name.toLowerCase().replaceAll(/\W+/, "_")
            if (!keys.add(key)) { key = "${key}_${i}".toString(); keys.add(key) }
            updateTank(tank, key, account)
        }
        state.remove("lastError")
        state.lastSuccess = new Date().format("yyyy-MM-dd HH:mm:ss", location.timeZone)
    } catch (e) {
        state.lastError = e.message ?: e.toString()
        log.error "MyFuelPortal: ${state.lastError}"
    }
}

private String baseUrl() {
    String domain = providerDomain.trim()
    if (!domain.startsWith("http://") && !domain.startsWith("https://")) {
        domain = domain.contains(".") ? "https://${domain}" : "https://${domain}.myfuelportal.com"
    }
    return domain.replaceAll(/\/+$/, "")
}

// GET the login form for its session cookie + anti-forgery token, then POST credentials.
// The portal answers bad credentials by re-rendering the form; success is a redirect away from it.
private Map login() {
    Map cookies = [:]
    String form = getPage("/Account/Login", cookies)
    String token = match(form, /name="__RequestVerificationToken"[^>]*value="([^"]+)"/)
    if (!token) throw new Exception("no login form at ${baseUrl()}/Account/Login; check the portal domain")

    Map resp = request("post", "/Account/Login?ReturnUrl=%2F", cookies,
        [EmailAddress: username, Password: password, RememberMe: "false", __RequestVerificationToken: token])
    boolean redirect = resp.status in 300..399
    if (!redirect && resp.status != 200) throw new Exception("login returned HTTP ${resp.status}")
    if (redirect ? resp.location?.contains("/Account/Login") : resp.body.contains('id="EmailAddress"')) {
        throw new Exception("login rejected; check email and password")
    }
    logDebug "login ok, cookies: ${cookies.keySet()}"
    return cookies
}

// Follows in-site redirects by hand so cookies set along the way are kept.
// A page first visited without a session bounces via /Customers to /, so a chain that lands elsewhere retries the original once.
private String getPage(String path, Map cookies) {
    String current = path
    boolean retried = false
    for (int hop = 0; hop < 8; hop++) {
        Map resp = request("get", current, cookies)
        if (resp.status == 200 && current != path) {
            if (retried) throw new Exception("${path} keeps redirecting to ${current}")
            current = path
            retried = true
            continue
        }
        if (resp.status == 200) return resp.body
        if (!(resp.status in 300..399) || !resp.location) throw new Exception("${current} returned HTTP ${resp.status}")
        if (resp.location.contains("/Account/Login") && !path.startsWith("/Account/Login")) throw new Exception("session not authenticated loading ${current}")
        current = resp.location.replaceFirst(/^https?:\/\/[^\/]+/, "")
    }
    throw new Exception("too many redirects loading ${path}")
}

// Redirects are not followed so every Set-Cookie (notably the auth cookie on the login 302) is captured.
private Map request(String method, String path, Map cookies, Map body = null) {
    Map headers = ["User-Agent": "Mozilla/5.0 (Hubitat MyFuelPortal)"]
    if (cookies) headers.Cookie = cookies.collect { k, v -> "${k}=${v}" }.join("; ")
    Map params = [uri: baseUrl() + path, headers: headers, followRedirects: false, textParser: true, timeout: 30]
    if (body != null) {
        params.requestContentType = "application/x-www-form-urlencoded"
        params.body = body
    }
    logDebug "→ ${method.toUpperCase()} ${params.uri} cookies=${cookies.keySet()} form=${body?.keySet() ?: '-'}"
    Map result = [:]
    Closure handle = { resp ->
        resp.getHeaders("Set-Cookie").each { h ->
            def m = (h.value =~ /^([^=;]+)=([^;]*)/)
            if (m.find()) cookies[m.group(1)] = m.group(2)
        }
        result.status = resp.status
        result.location = resp.getFirstHeader("Location")?.value
        def data = resp.data
        result.body = data == null ? "" : (data instanceof CharSequence ? data.toString() : data.text)
        if (logEnable) {
            String dump = resp.allHeaders.collect { h -> h.name.equalsIgnoreCase("Set-Cookie") ? "Set-Cookie: ${h.value.tokenize('=')[0]}=…" : "${h.name}: ${h.value}" }.join(" | ")
            logDebug "← ${result.status} ${path} ${dump}"
            dumpBody(path, result.body)
        }
    }
    try {
        method == "post" ? httpPost(params, handle) : httpGet(params, handle)
    } catch (groovyx.net.http.HttpResponseException e) {
        handle(e.response)
    }
    return result
}

// Capacity + product appears as "240 gal. | Propane", "125 Gal Propane" or "500 Gallon Propane".
@groovy.transform.Field static final String CAPACITY_RE = /(?i)([\d,]+(?:\.\d+)?)\s*(?:gal\.?|gallon)\s*(?:\|\s*|\s)\w/
// Product runs to the end of its line in the HTML, e.g. "HEATING OIL" or "NO. 2 FUEL OIL".
@groovy.transform.Field static final String PRODUCT_RE = /(?i)[\d,.]+\s*(?:gal\.?|gallon)\s*(?:\|\s*|\s)(\w[^<]*?)\s*(?=<|$)/

// Each tank is a <div class="row tank-row ..."> block on /Tank; the last one is cut at the end of the box so the footer can't leak in.
// Capacity is read after the name, which can itself say "240 GALLON MONITORED".
List parseTanks(String html) {
    String page = html.replaceAll(/(?is)<(script|style)\b.*?<\/\1>/, "")
    List chunks = page.split(/<div[^>]*class="[^"]*(?<![\w-])tank-row(?![\w-])[^"]*"/) as List
    return chunks.drop(1).collect { String raw ->
        String chunk = raw.split(/(?i)<!--\s*\/\.box-body|<footer|id="NewFooter"/)[0]
        String text = stripTags(chunk)
        String details = chunk.replaceFirst(/(?s)^.*?class="text-larger"[^>]*>[^<]*</, "")
        [
            id: match(chunk, /\/RequestADelivery\/Index\/(\d+)/),
            name: decode(match(chunk, /class="text-larger"[^>]*>([^<]*)</))?.trim()?.replaceAll(/\s+/, " "),
            level: toNumber(match(chunk, /aria-valuenow="([\d.]+)"/), 1),
            gallons: toNumber(match(text, /Approximately\s+([\d,.]+)\s+gallons/)),
            capacity: toNumber(match(stripTags(details), CAPACITY_RE)),
            product: decode(match(details, PRODUCT_RE))?.toLowerCase()?.tokenize()?.collect { it.capitalize() }?.join(" ") ?: null,
            deliveryMode: match(chunk, /class="text-2"[^>]*>\s*([^<]*?)\s*</),
            pricePerGallon: toNumber(match(text, /\$\s*([\d,.]+)\s*\/\s*gal/)),
            readingDate: isoDate(match(text, /Reading Date:\s*(\d{1,2}\/\d{1,2}\/\d{4})/)),
            lastDelivery: isoDate(match(text, /Last Delivery:\s*(\d{1,2}\/\d{1,2}\/\d{4})/)),
            nextDelivery: isoDate(match(text, /Estimated Next Delivery:\s*(\d{1,2}\/\d{1,2}\/\d{4})/)),
            address: decode([match(chunk, ADDRESS_RE), match(chunk, ADDRESS_RE, 2)?.replaceAll(/\s+/, " ")].findAll().join(", ")) ?: null
        ]
    }.findAll { it.name || it.id }
}

// Service address column: "164 SYCAMORE DR<br />HONEY BROOK, PA 19344".
@groovy.transform.Field static final String ADDRESS_RE = /class="col-sm-6 col-md-2"[^>]*>\s*([^<]+?)\s*<br\s*\/?>\s*([^<]+?)\s*</

Map parseAccount(String html) {
    String text = stripTags(html)
    [
        accountBalance: toNumber(match(text, /(?i)Current\s+Account\s+Balance:?\s*(-?\$?-?[\d,]+(?:\.\d+)?)/)?.replace("\$", ""), 2),
        accountNumber: match(text, /(?i)Account\s+Number:?\s*(\d+)/),
        accountName: decode(match(html, /(?s)customer-information.*?class="text-larger"[^>]*>\s*(?:<strong>)?\s*([^<]+?)\s*</)),
        customerSince: isoDate(match(text, /(?i)Customer\s+Since:?\s*(\d{1,2}\/\d{1,2}\/\d{4})/))
    ].findAll { it.value != null }
}

private String stripTags(String html) {
    return decode(html.replaceAll(/<[^>]+>/, " ")).replaceAll(/\s+/, " ")
}

// &amp; last so "&amp;#39;" stays literal; &nbsp; becomes a plain space so \s matches it.
private String decode(String s) {
    return s?.replaceAll(/&#(\d{1,5});/) { all, n -> (n as int) <= 0xFFFF ? ((n as int) as char).toString() : all }
        ?.replace("&nbsp;", " ")?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")
}

private String match(String s, String pattern, int group = 1) {
    def m = (s =~ pattern)
    return m.find() ? m.group(group) : null
}

private Double toNumber(String s, int decimals = 4) {
    String n = s?.replace(",", "")
    return n?.isNumber() ? n.toBigDecimal().setScale(decimals, BigDecimal.ROUND_HALF_UP).doubleValue() : null
}

private String isoDate(String mdy) {
    if (!mdy) return null
    def (m, d, y) = mdy.split("/")
    return String.format("%04d-%02d-%02d", y as int, m as int, d as int)
}

// Labelled by street ("Propane Tank - 164 Sycamore Dr"); the portal's tank name goes in the device name to tell same-address tanks apart.
private void updateTank(Map tank, String key, Map account) {
    String dni = "myfuelportal_${app.id}_${key}"
    def child = getChildDevice(dni)
    if (!child) {
        String street = tank.address?.tokenize(",")?.first()?.toLowerCase()?.tokenize(" ")?.collect { it.capitalize() }?.join(" ")
        String label = "${tank.product ?: 'Propane'} Tank${street ? ' - ' + street : ''}".toString()
        child = addChildDevice("myfuelportal", "MyFuelPortal Tank", dni, [name: tank.name ?: "MyFuelPortal Tank", label: label, isComponent: false])
        if (txtEnable) log.info "MyFuelPortal: created ${label}"
    }
    if (txtEnable) log.info "MyFuelPortal: ${child.displayName}: ${tank.level}% (${tank.gallons} gal), next delivery ${tank.nextDelivery ?: 'unknown'}"
    child.updateTank(tank + account)
}

// The Logs page renders HTML, so bodies are escaped; scripts/styles are dropped and the CSRF token redacted.
private void dumpBody(String path, String body) {
    String html = body.replaceAll(/(?is)<(script|style|head)\b.*?<\/\1>/, "")
        .replaceAll(/(name="__RequestVerificationToken"[^>]*value=")[^"]+/, '$1…')
        .replaceAll(/\s+/, " ").replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
    int size = 2000
    int parts = Math.max(1, (int) Math.ceil(html.length() / (double) size))
    (0..<parts).each { i -> logDebug "body ${path} [${i + 1}/${parts}] ${html.substring(i * size, Math.min(html.length(), (i + 1) * size))}" }
}

private void logDebug(String msg) {
    if (logEnable) log.debug "MyFuelPortal: ${msg}"
}
