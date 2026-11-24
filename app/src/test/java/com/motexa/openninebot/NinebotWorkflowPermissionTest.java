package com.motexa.openninebot;

import static org.junit.Assert.*;
import android.Manifest;
import java.util.Arrays;
import org.junit.Test;

public class NinebotWorkflowPermissionTest {
    @Test public void wifiCastingDoesNotRequestBluetoothPermissions() {
        String[] permissions = NinebotTestPanel.requiredPermissions(34, false, true);
        assertFalse(Arrays.asList(permissions).contains(Manifest.permission.BLUETOOTH_SCAN));
        assertFalse(Arrays.asList(permissions).contains(Manifest.permission.BLUETOOTH_CONNECT));
        assertTrue(Arrays.asList(permissions).contains(Manifest.permission.NEARBY_WIFI_DEVICES));
    }

    @Test public void vehicleReadRequiresBluetoothPermissions() {
        String[] permissions = NinebotTestPanel.requiredPermissions(34, true, false);
        assertTrue(Arrays.asList(permissions).contains(Manifest.permission.BLUETOOTH_SCAN));
        assertTrue(Arrays.asList(permissions).contains(Manifest.permission.BLUETOOTH_CONNECT));
        assertFalse(Arrays.asList(permissions).contains(Manifest.permission.NEARBY_WIFI_DEVICES));
    }

    @Test public void wifiFilterRequiresCompleteNameAndMac() {
        assertTrue(NinebotP2pTransport.isEligiblePeer("NINEBOT-TEST", "01:23:45:67:89:ab"));
        assertFalse(NinebotP2pTransport.isEligiblePeer("", "01:23:45:67:89:ab"));
        assertFalse(NinebotP2pTransport.isEligiblePeer("NINEBOT-TEST", "01:23:45:67:89"));
        assertFalse(NinebotP2pTransport.isEligiblePeer("  ", "01:23:45:67:89:ab"));
        assertFalse(NinebotP2pTransport.isEligiblePeer(null, null));
    }

    @Test public void wifiFilterEnforcesEachConfiguredField() {
        assertEquals("", NinebotP2pTransport.peerRejectionReason("NINEBOT-TEST", "01:23:45:67:89:ab", "NINEBOT-TEST", ""));
        assertEquals("configured_name_mismatch", NinebotP2pTransport.peerRejectionReason("ninebot-test", "01:23:45:67:89:ab", "NINEBOT-TEST", ""));
        assertEquals("", NinebotP2pTransport.peerRejectionReason("NINEBOT-TEST", "01:23:45:67:89:ab", "", "01:23:45:67:89:AB"));
        assertEquals("configured_address_mismatch", NinebotP2pTransport.peerRejectionReason("NINEBOT-TEST", "01:23:45:67:89:ab", "", "01:23:45:67:89:cd"));
        assertEquals("missing_complete_name_or_mac", NinebotP2pTransport.peerRejectionReason("NINEBOT-TEST", "invalid", "NINEBOT-TEST", ""));
    }
}
