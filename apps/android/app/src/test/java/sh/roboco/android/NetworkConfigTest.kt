package sh.roboco.android

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The cleartext LAN networking contract (android-app#13): the platform must
 * not block plain `http://`/`ws://` dials to LAN engines. Android's config
 * cannot express IP ranges, so the private-range rule lives in app code
 * (tickets 06 and 18), not here.
 */
class NetworkConfigTest {

    @Test
    fun baseConfigPermitsCleartextTraffic() {
        val config = File("src/main/res/xml/network_security_config.xml")
        assertTrue("network_security_config.xml not found (cwd: ${File(".").absolutePath})", config.isFile)

        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(config)
        val baseConfigs = document.getElementsByTagName("base-config")
        assertEquals("expected exactly one base-config", 1, baseConfigs.length)

        val cleartext = baseConfigs.item(0).attributes.getNamedItem("cleartextTrafficPermitted")
        assertEquals("true", cleartext.nodeValue)
    }
}
