package file

import org.junit.Assert.*
import org.junit.Test

class BuildManifestTest {
    @Test fun desktopManifestImportsEndpointAndBothRevisions() {
        val m = BuildManifest.parse("\uFEFF[Build]\ncomplete=true\nversion=00001\nbuild=00002\n" +
            "url_check=\"mro1.myarena.site/build/check.ini\"\n" +
            "[Server]\naddress=\"178.20.47.31\"\nport=\"25565\"\n" +
            "[Content]\ncontent=A.esm\ncontent=B.esm\n")
        assertTrue(m.complete)
        assertEquals("178.20.47.31", BuildManifest.connectionAddress(m))
        assertEquals("25565", BuildManifest.connectionPort(m))
        assertTrue(m.serverAddressSpecified)
        assertEquals("00001", m.contentVersion)
        assertEquals("00002", m.engineBuild)
        assertEquals(listOf("A.esm", "B.esm"), m.content)
    }

    @Test fun alternativeEndpointNeverReplacesDistributedEndpoint() {
        val m = BuildManifest.parse("[Build]\ncomplete=true\n[Server]\naddress=178.20.47.31\nport=25565\n" +
            "use_alt_server=true\nalt_adress=example.org\nalt_port=25566\n")
        assertEquals("example.org", BuildManifest.connectionAddress(m))
        assertEquals("25566", BuildManifest.connectionPort(m))
        m.useAlternativeServer = false
        assertEquals("178.20.47.31", BuildManifest.connectionAddress(m))
        assertEquals("25565", BuildManifest.connectionPort(m))
    }

    @Test fun flatAndLegacyBuildSectionEndpointsAreAccepted() {
        for (header in listOf("", "[Build]\n")) {
            val m = BuildManifest.parse(header + "comlete=TRUE\nadress=178.20.47.31\nport=25565\n")
            assertTrue(m.complete)
            assertEquals("178.20.47.31", m.serverAddress)
            assertEquals("25565", m.serverPort)
        }
    }
}
