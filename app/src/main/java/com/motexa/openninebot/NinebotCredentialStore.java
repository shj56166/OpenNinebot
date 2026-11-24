package com.motexa.openninebot;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.AtomicFile;
import com.motexa.vehiclecast.NinebotCredentialProvider;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import java.util.Arrays;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONObject;

/** Separate Keystore namespace for user-authorized Ninebot material. No plaintext fallback. */
public final class NinebotCredentialStore implements NinebotCredentialProvider {
    private static final String ALIAS = "openninebot-credentials-v1";
    private static final byte[] AAD = "motexa-ninebot-test-material-v1".getBytes(StandardCharsets.UTF_8);
    private final AtomicFile file;

    public NinebotCredentialStore(Context context) {
        file = new AtomicFile(new File(context.getNoBackupFilesDir(), "ninebot-material-v1.bin"));
    }
    @Override public synchronized JSONObject load(String vehicleId) {
        requireActive(vehicleId); NinebotTestProfile profile = loadProfile();
        if (profile == null) return null;
        try { return new JSONObject(profile.saved()); }
        catch (Exception invalid) { throw new IllegalStateException("九号资料格式错误"); }
    }
    @Override public synchronized void save(String vehicleId, JSONObject material) {
        requireActive(vehicleId);
        if (material == null) throw new IllegalArgumentException("九号资料不能为空");
        saveProfile(NinebotTestProfile.parse(material.toString()));
    }
    @Override public synchronized void forget(String vehicleId) { requireActive(vehicleId); clear(); }
    private static void requireActive(String vehicleId) {
        if (!ACTIVE_VEHICLE.equals(vehicleId)) throw new IllegalArgumentException("仅保存当前明确选择的九号车辆");
    }
    private void saveProfile(NinebotTestProfile profile) {
        byte[] plaintext = profile.saved().getBytes(StandardCharsets.UTF_8);
        FileOutputStream stream = null;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key()); cipher.updateAAD(AAD);
            byte[] encrypted = cipher.doFinal(plaintext);
            stream = file.startWrite(); stream.write(cipher.getIV()); stream.write(encrypted);
            file.finishWrite(stream); stream = null;
        } catch (Exception error) {
            if (stream != null) file.failWrite(stream);
            throw new IllegalStateException("九号资料加密保存失败，请重试");
        } finally { Arrays.fill(plaintext, (byte) 0); }
    }
    private NinebotTestProfile loadProfile() {
        if (!file.getBaseFile().exists()) return null;
        byte[] plaintext = null;
        try {
            if (file.getBaseFile().length() > NinebotTestProfile.MAX_IMPORT_CHARS * 4L + 64) throw new IllegalStateException();
            byte[] encrypted = file.readFully();
            if (encrypted.length < 28) throw new IllegalStateException();
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, key(), new GCMParameterSpec(128, encrypted, 0, 12));
            cipher.updateAAD(AAD); plaintext = cipher.doFinal(encrypted, 12, encrypted.length - 12);
            return NinebotTestProfile.parse(new String(plaintext, StandardCharsets.UTF_8));
        } catch (Exception error) { throw new IllegalStateException("九号资料不可恢复，请重新导入；不会回退明文存储"); }
        finally { if (plaintext != null) Arrays.fill(plaintext, (byte) 0); }
    }
    private void clear() {
        file.delete();
        if (file.getBaseFile().exists()) throw new IllegalStateException("九号资料清除失败");
    }
    private SecretKey key() throws Exception {
        KeyStore store = KeyStore.getInstance("AndroidKeyStore"); store.load(null);
        if (store.containsAlias(ALIAS)) return (SecretKey) store.getKey(ALIAS, null);
        KeyGenerator generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
        generator.init(new KeyGenParameterSpec.Builder(ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build());
        return generator.generateKey();
    }
}
