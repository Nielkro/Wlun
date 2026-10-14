package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import org.junit.Assert.*
import org.junit.Test

class NativeHashPolicyTest {
    @Test fun newImportsUseSameIdentityAcrossGroupsAndServerChanges() {
        assertEquals(NativeHashPolicy.defaultDeviceId("same-phone"),NativeHashPolicy.defaultDeviceId("same-phone"))
        assertNotEquals(NativeHashPolicy.defaultDeviceId("same-phone"),NativeHashPolicy.defaultDeviceId("another-phone"))
    }
    private fun profile() = Qwdtt().apply {
        name = "Example"; server = "192.0.2.1"; password = "test"
        hashes = listOf("manual_hash_123456789")
    }
    @Test fun sourceSurvivesSubscriptionServerAndHashChanges() {
        val old = profile().apply { hashSource = "manual" }
        val incoming = profile().apply { server = "192.0.2.2"; hashes = listOf("new_hash_123456789"); hashSource = "auto" }
        NativeHashPolicy.preserve(incoming,listOf(old))
        assertEquals("manual",incoming.hashSource)
        assertTrue(incoming.hashSourceChosen)
        assertEquals(old.deviceId,incoming.deviceId)
    }
    @Test fun automaticSourceSurvivesSubscriptionUpdate() {
        val incoming = profile()
        NativeHashPolicy.preserve(incoming,listOf(profile().apply { hashSource = "auto" }))
        assertEquals("auto",incoming.hashSource)
    }
    @Test fun csqttDeviceIdentitySurvivesReplacedServer() {
        val old=app.qthrone.mobile.outbound.types.Csqtt().apply { name="Same server";deviceId="existing-phone" }
        val incoming=app.qthrone.mobile.outbound.types.Csqtt().apply { name="Same server";server="192.0.2.55" }
        NativeHashPolicy.preserve(incoming,listOf(old))
        assertEquals("existing-phone",incoming.deviceId)
        assertTrue(incoming.deviceIdProvided)
    }
    @Test fun manualHashesSurviveAutomaticModeSerialization() {
        val original = profile().apply { hashSource = "auto" }
        val restored = Qwdtt()
        assertTrue(restored.parseFromJson(original.exportToJson()))
        restored.hashSource = "manual"
        assertEquals(original.hashes,restored.hashes)
        assertEquals(original.exportIdentity().toCompact(),restored.exportIdentity().toCompact())
    }
    @Test fun ambiguousProfilesDoNotCopyAnotherProfilesChoice() {
        val incoming = profile()
        NativeHashPolicy.preserve(incoming,listOf(profile(),profile().apply { hashSource = "auto" }))
        assertFalse(incoming.hashSourceChosen)
    }
}
