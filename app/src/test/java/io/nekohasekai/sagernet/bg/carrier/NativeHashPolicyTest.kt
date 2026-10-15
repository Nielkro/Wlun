package app.qthrone.mobile.bg.carrier

import app.qthrone.mobile.outbound.types.Qwdtt
import org.junit.Assert.*
import org.junit.Test

class NativeHashPolicyTest {
    @Test fun workerCeilingFollowsTheResultingHashCountWithoutResettingToNine() {
        assertEquals(81,NativeHashPolicy.preservedWorkers(108,108,81))
        assertEquals(108,NativeHashPolicy.preservedWorkers(81,81,108))
        assertEquals(126,NativeHashPolicy.preservedWorkers(108,108,126))
        assertEquals(54,NativeHashPolicy.preservedWorkers(54,108,81))
        for(type in listOf("qwdtt","csqtt")) {
            val old=if(type=="csqtt") app.qthrone.mobile.outbound.types.Csqtt() else Qwdtt()
            old.name="workers";old.server="192.0.2.1";old.password="fixture";old.hashSource="manual"
            old.hashes=listOf("a","b","c","d");old.workers=108
            val incoming=if(type=="csqtt") app.qthrone.mobile.outbound.types.Csqtt() else Qwdtt()
            incoming.name=old.name;incoming.server=old.server;incoming.password=old.password
            // Provider now supplies three slots; the surviving trailing slot duplicates one of them.
            incoming.hashes=listOf("d","e","f");incoming.workers=9
            NativeHashPolicy.preserve(incoming,listOf(old))
            assertEquals(listOf("d","e","f"),incoming.hashes)
            assertEquals(81,incoming.workers)
        }
    }
    @Test fun subscriptionHashesAndExistingRowSurviveRepeatedRefreshForBothTransports() {
        for (old in listOf(profile(),app.qthrone.mobile.outbound.types.Csqtt().apply {
            name="Example";server="192.0.2.1";password="test";hashes=listOf("local_hash")
        })) {
            repeat(2) {
                val incoming=if(old is app.qthrone.mobile.outbound.types.Csqtt) app.qthrone.mobile.outbound.types.Csqtt() else Qwdtt()
                assertTrue(incoming.parseFromJson(old.exportToJson()))
                incoming.hashes=listOf("subscription_default")
                incoming.workers=18
                NativeHashPolicy.preserve(incoming,listOf(old))
                assertEquals(listOf("subscription_default"),incoming.hashes)
                assertEquals(old.deviceId,incoming.deviceId)
                val previous=app.qthrone.mobile.group.OldEntry(7,"old-content",NativeHashPolicy.subscriptionIdentity(old),"old")
                val index=app.qthrone.mobile.group.ContentIndex(listOf(previous))
                val next=app.qthrone.mobile.group.NewEntry("new").apply {
                    id=8;content="new-content";identity=NativeHashPolicy.subscriptionIdentity(incoming)
                }
                index.noteArrival(next.content)
                val plan=app.qthrone.mobile.group.SubscriptionReconcile.reconcile(listOf(previous),listOf(next),index)
                assertEquals(listOf(7L),plan.order)
                assertEquals(listOf(7L to 8L),plan.updates)
                assertEquals(listOf(8L),plan.stale)
            }
        }
    }
    @Test fun subscriptionReplacesLeadingSlotsAndKeepsOnlyUnclaimedManualSlots() {
        for(type in listOf("qwdtt","csqtt")) {
            val old=if(type=="csqtt") app.qthrone.mobile.outbound.types.Csqtt() else Qwdtt()
            old.name="Example";old.hashes=listOf("sub-old","manual-2","manual-3","manual-4")
            for(count in listOf(1,3,4)) {
                val incoming=if(type=="csqtt") app.qthrone.mobile.outbound.types.Csqtt() else Qwdtt()
                incoming.name="Example";incoming.hashes=(1..count).map { "sub-new-$it" }
                NativeHashPolicy.preserve(incoming,listOf(old))
                assertEquals((1..count).map { "sub-new-$it" }+old.hashes.drop(count),incoming.hashes)
                old.hashes=incoming.hashes
            }
        }
        assertEquals(listOf("a","b"),NativeHashPolicy.mergeSlots(emptyList(),listOf("a","b"),4))
    }
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
