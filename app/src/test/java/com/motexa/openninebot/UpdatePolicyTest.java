package com.motexa.openninebot;

import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.ECGenParameterSpec;
import org.json.JSONObject;
import org.junit.*;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
public class UpdatePolicyTest {
    private KeyPair keys;
    private String publicKey;
    @Before public void key()throws Exception{KeyPairGenerator generator=KeyPairGenerator.getInstance("EC");generator.initialize(new ECGenParameterSpec("secp256r1"));keys=generator.generateKeyPair();publicKey=Base64.encodeToString(keys.getPublic().getEncoded(),Base64.NO_WRAP);}
    private JSONObject policy()throws Exception{return new JSONObject().put("schemaVersion",1).put("packageName","com.motexa.openninebot").put("versionCode",2).put("versionName","0.1.1").put("minSupportedVersionCode",1).put("bytes",1024).put("sha256","a".repeat(64)).put("certificateSha256","b".repeat(64)).put("url","https://github.com/shj56166/OpenNinebot/releases/download/v0.1.1/ninebot-lab.apk");}
    private String signed(JSONObject policy)throws Exception{byte[] payload=policy.toString().getBytes(StandardCharsets.UTF_8);Signature signer=Signature.getInstance("SHA256withECDSA");signer.initSign(keys.getPrivate());signer.update(payload);return new JSONObject().put("signedPayload",Base64.encodeToString(payload,Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING)).put("signature",Base64.encodeToString(signer.sign(),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING)).toString();}
    @Test public void optionalAndMandatoryPoliciesAreSignedIndependentlyOfTrial()throws Exception{
        assertEquals(1,GitHubUpdates.verify(signed(policy()),publicKey).getInt("minSupportedVersionCode"));
        assertEquals(2,GitHubUpdates.verify(signed(policy().put("minSupportedVersionCode",2)),publicKey).getInt("minSupportedVersionCode"));
    }
    @Test public void tamperedPayloadIsRejected()throws Exception{JSONObject envelope=new JSONObject(signed(policy()));envelope.put("signedPayload",Base64.encodeToString(policy().put("minSupportedVersionCode",2).toString().getBytes(StandardCharsets.UTF_8),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING));assertThrows(Exception.class,()->GitHubUpdates.verify(envelope.toString(),publicKey));}
    @Test public void wrongProductAndExternalDownloadAreRejected()throws Exception{assertThrows(Exception.class,()->GitHubUpdates.verify(signed(policy().put("packageName","other")),publicKey));assertThrows(Exception.class,()->GitHubUpdates.verify(signed(policy().put("url","https://example.com/app.apk")),publicKey));assertThrows(Exception.class,()->GitHubUpdates.verify(signed(policy().put("minSupportedVersionCode",3)),publicKey));}
    @Test public void redirectsRejectCredentialsCleartextAndUnrelatedHosts(){assertTrue(GitHubUpdates.allowedUrl("https://release-assets.githubusercontent.com/path?token=test"));assertFalse(GitHubUpdates.allowedUrl("http://github.com/shj56166/OpenNinebot/releases/a"));assertFalse(GitHubUpdates.allowedUrl("https://github.com.evil.example/shj56166/OpenNinebot/releases/a"));assertFalse(GitHubUpdates.allowedUrl("https://user@github.com/shj56166/OpenNinebot/releases/a"));}
    @Test public void renamedProjectRejectsLegacyPackageAndRepository()throws Exception {
        assertThrows(Exception.class,()->GitHubUpdates.verify(signed(policy().put("packageName","com.motexa.ninebotlab")),publicKey));
        assertThrows(Exception.class,()->GitHubUpdates.verify(signed(policy().put("url","https://github.com/shj56166/ninebot-lab/releases/download/v0.1.1/old.apk")),publicKey));
        assertTrue(GitHubUpdates.allowedUrl("https://github.com/shj56166/OpenNinebot/releases/download/v0.1.1/OpenNinebot.apk"));
        assertFalse(GitHubUpdates.allowedUrl("https://github.com/shj56166/ninebot-lab/releases/download/v0.1.1/old.apk"));
    }
    @Test public void diagnosticCredentialsAreRedacted()throws Exception{JSONObject result=DiagnosticPrivacy.object(new JSONObject().put("credentials",new JSONObject().put("passwordHex","private-secret")).put("stage","authenticated"));assertFalse(result.toString().contains("private-secret"));assertEquals("authenticated",result.getString("stage"));}
}
