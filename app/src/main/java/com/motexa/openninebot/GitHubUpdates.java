package com.motexa.openninebot;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.util.Base64;
import java.io.File;
import java.io.FileInputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import org.json.JSONObject;

/** A signed release policy is independent of vehicle credentials and app authorization. */
final class GitHubUpdates {
    static final long CHECK_INTERVAL_MS=6L*60*60*1000;
    private final Context context;
    private final SharedPreferences prefs;
    private volatile JSONObject policy;
    GitHubUpdates(Context context){
        this.context=context.getApplicationContext();prefs=context.getSharedPreferences("github-updates",0);
        try{policy=verify(prefs.getString("envelope",""),BuildConfig.UPDATE_PUBLIC_KEY);}catch(Exception ignored){}
    }
    boolean mandatory(){JSONObject current=policy;return current!=null&&BuildConfig.VERSION_CODE<current.optInt("minSupportedVersionCode");}
    JSONObject available(){JSONObject current=policy;return current!=null&&current.optInt("versionCode")>BuildConfig.VERSION_CODE?current:null;}
    void refresh(boolean manual)throws Exception {
        long now=System.currentTimeMillis(),last=prefs.getLong("checkedAt",0);
        if(!manual&&policy!=null&&now>=last&&now-last<CHECK_INTERVAL_MS)return;
        HttpURLConnection connection=downloadConnection(context,BuildConfig.UPDATE_URL,0,policy==null?"":prefs.getString("etag",""));
        try{
            int status=connection.getResponseCode();
            if(status==304&&policy!=null){prefs.edit().putLong("checkedAt",now).commit();return;}
            if(status!=200)throw new java.io.IOException("update_http_"+status);
            String envelope=new String(LabClient.read(connection.getInputStream(),32768),StandardCharsets.UTF_8);
            JSONObject verified=verify(envelope,BuildConfig.UPDATE_PUBLIC_KEY);
            if(!prefs.edit().putString("envelope",envelope).putString("etag",connection.getHeaderField("ETag")).putLong("checkedAt",now).commit())throw new java.io.IOException("update_cache_failed");
            policy=verified;
        }finally{connection.disconnect();}
    }
    static JSONObject verify(String envelope,String key)throws Exception {
        if(envelope==null||envelope.length()>32768)throw new SecurityException("update_size");
        JSONObject wrapper=new JSONObject(envelope);
        byte[] payload=Base64.decode(wrapper.getString("signedPayload"),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        byte[] signature=Base64.decode(wrapper.getString("signature"),Base64.URL_SAFE|Base64.NO_WRAP|Base64.NO_PADDING);
        Signature verifier=Signature.getInstance("SHA256withECDSA");
        verifier.initVerify(KeyFactory.getInstance("EC").generatePublic(new X509EncodedKeySpec(Base64.decode(key,Base64.DEFAULT))));
        verifier.update(payload);if(!verifier.verify(signature))throw new SecurityException("update_signature");
        JSONObject value=new JSONObject(new String(payload,StandardCharsets.UTF_8));
        long version=value.getLong("versionCode"),minimum=value.getLong("minSupportedVersionCode"),bytes=value.getLong("bytes");
        String url=value.getString("url");
        if(value.optInt("schemaVersion")!=1||!"com.motexa.openninebot".equals(value.getString("packageName"))||version<1||version>Integer.MAX_VALUE||minimum<1||minimum>version||bytes<1||bytes>150L*1024*1024
            ||!value.getString("sha256").matches("[a-f0-9]{64}")||!value.getString("certificateSha256").matches("[a-f0-9]{64}")
            ||!url.startsWith("https://github.com/shj56166/OpenNinebot/releases/download/")||!allowedUrl(url)||value.getString("versionName").length()>80)
            throw new SecurityException("update_policy_invalid");
        return value;
    }
    static boolean allowedUrl(String value){
        try{URI uri=new URI(value);String host=uri.getHost();return "https".equals(uri.getScheme())&&uri.getUserInfo()==null&&(uri.getPort()==-1||uri.getPort()==443)&&uri.getFragment()==null
            &&(("github.com".equals(host)&&uri.getPath().startsWith("/shj56166/OpenNinebot/releases/"))||"release-assets.githubusercontent.com".equals(host)||"objects.githubusercontent.com".equals(host));}
        catch(Exception error){return false;}
    }
    static HttpURLConnection downloadConnection(Context context,String address,long offset)throws Exception {
        return downloadConnection(context,address,offset,"");
    }
    private static HttpURLConnection downloadConnection(Context context,String address,long offset,String etag)throws Exception {
        ConnectivityManager manager=context.getSystemService(ConnectivityManager.class);Network internet=null;
        if(manager!=null)for(Network candidate:manager.getAllNetworks()){
            NetworkCapabilities caps=manager.getNetworkCapabilities(candidate);
            if(caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)){internet=candidate;break;}
        }
        for(int redirects=0;redirects<6;redirects++){
            if(!allowedUrl(address))throw new SecurityException("update_redirect_host");
            HttpURLConnection connection=(HttpURLConnection)(internet==null?new URL(address).openConnection():internet.openConnection(new URL(address)));
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(15000);connection.setReadTimeout(30000);
            connection.setRequestProperty("Accept-Encoding","identity");
            if(!etag.isEmpty())connection.setRequestProperty("If-None-Match",etag);
            if(offset>0)connection.setRequestProperty("Range","bytes="+offset+"-");
            int status=connection.getResponseCode();
            if(status!=301&&status!=302&&status!=303&&status!=307&&status!=308)return connection;
            String location=connection.getHeaderField("Location");connection.disconnect();
            if(location==null)throw new java.io.IOException("missing_redirect");address=new URL(new URL(address),location).toString();
        }
        throw new java.io.IOException("update_redirect_limit");
    }
    static String sha256(File file)throws Exception {
        MessageDigest digest=MessageDigest.getInstance("SHA-256");try(FileInputStream stream=new FileInputStream(file)){byte[] buffer=new byte[32768];int n;while((n=stream.read(buffer))!=-1)digest.update(buffer,0,n);}
        StringBuilder result=new StringBuilder();for(byte value:digest.digest())result.append(String.format(java.util.Locale.ROOT,"%02x",value&255));return result.toString();
    }
}
