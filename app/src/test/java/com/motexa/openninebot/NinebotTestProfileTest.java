package com.motexa.openninebot;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class) @Config(sdk = 34)
public class NinebotTestProfileTest {
    private static final String KEY = "00112233445566778899aabbccddeeff";
    private JSONObject gen2() throws Exception {
        return new JSONObject().put("schemaVersion", 1).put("authVersion", 2)
                .put("profileId", "encryption2_standard_nus").put("deviceName", "NBTEST")
                .put("modelId", "14357").put("credentials", new JSONObject().put("passwordHex", KEY));
    }
    @Test public void restoresTypedMaterialWithoutPuttingSecretsInSummary() throws Exception {
        NinebotTestProfile profile = NinebotTestProfile.parse(gen2().toString());
        assertEquals(2, profile.authVersion); assertEquals(KEY, profile.credentials().getString("passwordHex"));
        assertFalse(profile.summary().toString().contains(KEY)); assertFalse(profile.toString().contains(KEY));
        assertEquals(profile.saved(), NinebotTestProfile.parse(profile.saved()).saved());
    }
    @Test public void requiresExplicitAuthAndProfileAndRejectsUnknownCustomFallback() throws Exception {
        JSONObject missing = gen2(); missing.remove("authVersion"); assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(missing.toString()));
        JSONObject profile = gen2().put("profileId", "encryption2_standard_custom");
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(profile.toString()));
    }
    @Test public void rejectsTypeCoercionAndDuplicateSecretSlots() throws Exception {
        String wrongType = gen2().put("authVersion", "2").toString();
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(wrongType));
        String duplicate = gen2().toString().replace("\"passwordHex\":", "\"passwordHex\":\"ffffffffffffffffffffffffffffffff\",\"passwordHex\":");
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(duplicate));
    }
    @Test public void separatesV3MaterialAndPreservesMetadataTypes() throws Exception {
        JSONObject raw = gen2().put("authVersion", 3).put("credentials", new JSONObject().put("v3", new JSONObject()
                .put("sn", "NBTESTSN").put("appEncHex", KEY).put("metadata", new JSONObject().put("exTime", 1234).put("nonce", "abc"))));
        NinebotTestProfile profile = NinebotTestProfile.parse(raw.toString());
        assertTrue(profile.credentials().getJSONObject("v3").getJSONObject("metadata").get("exTime") instanceof Number);
        raw.getJSONObject("credentials").put("passwordHex", KEY);
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(raw.toString()));
    }
    @Test public void externalWifiRequiresExplicitModeAndContainsNoVehicleSecrets() throws Exception {
        JSONObject raw = new JSONObject().put("schemaVersion", 1).put("profileId", "encryption2_standard_nus")
                .put("authVersion", 0).put("transport", "udp").put("wireProfile", "pro").put("externalCastingEnabled", true);
        assertTrue(NinebotTestProfile.parse(raw.toString()).externalCastingEnabled);
        raw.put("externalCastingEnabled", false);
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(raw.toString()));
    }

    @Test public void wifiPresetIsMediaOnlyAndUsesExpectedRoute() {
        NinebotTestProfile five = NinebotTestProfile.wifiPreset(false);
        assertEquals(0, five.authVersion); assertEquals("udp", five.transport);
        assertTrue(five.externalCastingEnabled); assertFalse(five.summary().optBoolean("hasCredentials"));
        NinebotTestProfile seven = NinebotTestProfile.wifiPreset(true);
        assertEquals("kcp", seven.transport); assertEquals(1024, seven.width); assertEquals(600, seven.height);
    }
    @Test public void errorsDoNotEchoImportedSecretOrAllowHttpAuthEndpoint() throws Exception {
        JSONObject raw = gen2().put("authVersion", 3).put("credentials", new JSONObject().put("v3", new JSONObject()
                .put("sn", "NBTESTSN").put("appEncHex", KEY).put("authApi", new JSONObject().put("endpoint", "http://example.org/auth").put("token", "private-token"))));
        IllegalArgumentException error = assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(raw.toString()));
        assertFalse(error.getMessage().contains(KEY)); assertFalse(error.getMessage().contains("private-token"));
    }

    @Test public void authorizedProviderResponseIsBoundToOperationAndRequest() throws Exception {
        String body = new JSONObject().put("schemaVersion", 1).put("operation", "refresh")
                .put("requestId", "req-1").put("success", true).put("result", new JSONObject()
                        .put("v3", new JSONObject().put("sn", "NBTESTSN").put("appEncHex", KEY))).toString();
        JSONObject result = AuthorizedNinebotProvider.validateResponse(body, "refresh", "req-1");
        assertEquals("NBTESTSN", result.getJSONObject("v3").getString("sn"));
        assertThrows(Exception.class, () -> AuthorizedNinebotProvider.validateResponse(body, "verify", "req-1"));
        assertThrows(Exception.class, () -> AuthorizedNinebotProvider.validateResponse(body, "refresh", "req-2"));
    }

    @Test public void authorizedRefreshPreservesConfiguredEndpointAndBindsSn() throws Exception {
        JSONObject raw = gen2().put("authVersion", 3).put("credentials", new JSONObject().put("v3", new JSONObject()
                .put("sn", "NBTESTSN").put("appEncHex", KEY).put("authApi", new JSONObject()
                        .put("endpoint", "https://example.org/material").put("token", "opaque-token")
                        .put("adapter", AuthorizedNinebotProvider.ADAPTER))));
        NinebotTestProfile profile = NinebotTestProfile.parse(raw.toString());
        NinebotTestProfile updated = profile.withAuthorizedRefresh(new JSONObject().put("v3", new JSONObject()
                .put("sn", "NBTESTSN").put("curAppEncHex", "ffeeddccbbaa99887766554433221100")));
        assertEquals("https://example.org/material", updated.credentials().getJSONObject("v3").getJSONObject("authApi").getString("endpoint"));
        assertEquals("ffeeddccbbaa99887766554433221100", updated.credentials().getJSONObject("v3").getString("curAppEncHex"));
        assertThrows(IllegalArgumentException.class, () -> profile.withAuthorizedRefresh(new JSONObject().put("v3", new JSONObject()
                .put("sn", "OTHER").put("appEncHex", KEY))));
    }

    @Test public void gen3ExplicitBoardAndKey1MustBeAPairAndStayOutOfSummary() throws Exception {
        JSONObject v3 = new JSONObject().put("sn", "NBTESTSN").put("curAppEncHex", KEY).put("authBoard", 62);
        JSONObject raw = gen2().put("authVersion", 3).put("credentials", new JSONObject().put("v3", v3));
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(raw.toString()));
        v3.put("authKey1Hex", "ffeeddccbbaa99887766554433221100");
        NinebotTestProfile profile = NinebotTestProfile.parse(raw.toString());
        assertEquals(62, profile.options("NBTEST", 3, 247).getInt("authBoard"));
        assertEquals("ffeeddccbbaa99887766554433221100", profile.options("NBTEST", 3, 247).getString("authKey1Hex"));
        assertFalse(profile.summary().has("authBoard"));
        assertFalse(profile.summary().toString().contains("ffeeddccbbaa99887766554433221100"));
        assertFalse(profile.options("NBTEST", 2, 247).has("authKey1Hex"));
    }

    @Test public void gen3KeyPromotionRequiresCipherProofAndPromotesAtomicallyInMaterial() throws Exception {
        String next = "ffeeddccbbaa99887766554433221100";
        JSONObject v3 = new JSONObject().put("sn", "NBTESTSN").put("curAppEncHex", KEY).put("nextAppEncHex", next);
        NinebotTestProfile profile = NinebotTestProfile.parse(gen2().put("authVersion", 3)
                .put("credentials", new JSONObject().put("v3", v3)).toString());
        assertThrows(IllegalArgumentException.class, () -> profile.withGen3KeyState(new JSONObject().put("promoteNext", true)));
        NinebotTestProfile updated = profile.withGen3KeyState(new JSONObject().put("encryptedProof", true).put("promoteNext", true));
        assertEquals(next, updated.credentials().getJSONObject("v3").getString("curAppEncHex"));
        assertFalse(updated.credentials().getJSONObject("v3").has("nextAppEncHex"));
        assertTrue(profile.credentials().getJSONObject("v3").has("nextAppEncHex"));
    }

    @Test public void gen3FullCredentialUpdateBindsSnAndRetainsHostConfiguration() throws Exception {
        JSONObject v3 = new JSONObject().put("sn", "NBTESTSN").put("curAppEncHex", KEY).put("authBoard", 4)
                .put("authKey1Hex", KEY).put("authApi", new JSONObject().put("adapter", AuthorizedNinebotProvider.ADAPTER)
                        .put("endpoint", "https://example.org/material").put("token", "opaque-token"));
        NinebotTestProfile profile = NinebotTestProfile.parse(gen2().put("authVersion", 3)
                .put("credentials", new JSONObject().put("v3", v3)).toString());
        JSONObject action = new JSONObject().put("encryptedProof", true).put("removeNext", true).put("v3", new JSONObject()
                .put("sn", "NBTESTSN").put("curAppEncHex", "ffeeddccbbaa99887766554433221100"));
        NinebotTestProfile updated = profile.withGen3Credentials(action);
        assertEquals(4, updated.credentials().getJSONObject("v3").getInt("authBoard"));
        assertEquals("opaque-token", updated.credentials().getJSONObject("v3").getJSONObject("authApi").getString("token"));
        action.getJSONObject("v3").put("sn", "OTHER");
        assertThrows(IllegalArgumentException.class, () -> profile.withGen3Credentials(action));
    }

    @Test public void gen3FirstAuthorizationCanUseExplicitBoardAndAuthorizedProviderWithoutCachedKey() throws Exception {
        JSONObject v3 = new JSONObject().put("sn", "NBTESTSN").put("authBoard", 4).put("authKey1Hex", KEY)
                .put("authApi", new JSONObject().put("adapter", AuthorizedNinebotProvider.ADAPTER)
                        .put("endpoint", "https://example.org/auth").put("token", "opaque-token"));
        JSONObject raw = gen2().put("authVersion", 3).put("credentials", new JSONObject().put("v3", v3));
        NinebotTestProfile profile = NinebotTestProfile.parse(raw.toString());
        assertFalse(profile.credentials().getJSONObject("v3").has("curAppEncHex"));
        assertEquals(4, profile.options("NBTEST", 3, 247).getInt("authBoard"));
        v3.remove("authApi");
        assertThrows(IllegalArgumentException.class, () -> NinebotTestProfile.parse(raw.toString()));
    }
}
