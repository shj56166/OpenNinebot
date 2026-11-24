package com.motexa.openninebot;

import android.content.Context;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.SystemClock;
import java.net.URL;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.File;
import java.io.FileOutputStream;
import org.json.JSONObject;

final class LabClient {
    interface DownloadProgress { void update(long received,long total); }
    static final class HttpFailure extends java.io.IOException {
        final int status,retryAfterSeconds;
        final String code;
        HttpFailure(int status,String code,int retryAfterSeconds){super("http_"+status);this.status=status;this.code=code;this.retryAfterSeconds=retryAfterSeconds;}
        boolean uploadRateLimited(){return status==429&&("rate_limit".equals(code)||"unknown".equals(code)||"upload_busy".equals(code));}
    }
    private final Context context;
    private final SessionLog log;
    private static final java.util.concurrent.ScheduledExecutorService deadlines=
        java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r->{Thread t=new Thread(r,"TrialHttpDeadline");t.setDaemon(true);return t;});
    LabClient(Context c,SessionLog log){context=c.getApplicationContext();this.log=log;}
    HttpURLConnection connection(String url)throws Exception {return connection(url,0);}
    HttpURLConnection connection(String url,int attempt)throws Exception {
        if(!url.startsWith(BuildConfig.LOG_API) && !GitHubUpdates.allowedUrl(url))throw new SecurityException("invalid_endpoint");
        ConnectivityManager cm=context.getSystemService(ConnectivityManager.class);
        java.util.ArrayList<Network> candidates=new java.util.ArrayList<>();
        if(cm!=null){
            Network active=cm.getActiveNetwork();
            if(validated(cm,active))candidates.add(active);
            for(Network network:cm.getAllNetworks())if(validated(cm,network)&&!candidates.contains(network))candidates.add(network);
        }
        Network selected=candidates.isEmpty()?null:candidates.get(Math.floorMod(attempt,candidates.size()));
        HttpURLConnection c=(HttpURLConnection)(selected==null?new URL(url).openConnection():selected.openConnection(new URL(url)));
        NetworkCapabilities caps=selected==null||cm==null?null:cm.getNetworkCapabilities(selected);
        log.diagnostic("http_network", "path", url.endsWith("/policy")?"policy":url.endsWith("/logs")?"logs":"apk",
            "validatedNetworkSelected", selected!=null,"networkAttempt",attempt+1,"networkType",transport(caps),"network",selected==null?"default":selected.toString());
        c.setConnectTimeout(15000);c.setReadTimeout(20000);c.setInstanceFollowRedirects(false);return c;
    }
    private static boolean validated(ConnectivityManager cm,Network network){
        if(network==null)return false;NetworkCapabilities caps=cm.getNetworkCapabilities(network);
        return caps!=null&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)&&caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
    }
    private static String transport(NetworkCapabilities caps){
        if(caps==null)return "default";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI))return "wifi";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR))return "cellular";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET))return "ethernet";
        if(caps.hasTransport(NetworkCapabilities.TRANSPORT_VPN))return "vpn";
        return "other";
    }
    String post(String path,byte[] body,String type,String token,String id)throws Exception {
        return post(path,body,type,token,id,0);
    }
    private String post(String path,byte[] body,String type,String token,String id,int attempt)throws Exception {
        HttpURLConnection c=connection(BuildConfig.LOG_API+path,attempt);
        long started=android.os.SystemClock.elapsedRealtime();int status=0;
        String category="ok";
        java.util.concurrent.ScheduledFuture<?> deadline=deadlines.schedule(c::disconnect,30,java.util.concurrent.TimeUnit.SECONDS);
        try{c.setRequestMethod("POST");c.setDoOutput(true);c.setFixedLengthStreamingMode(body.length);c.setRequestProperty("Content-Type",type);
            c.setRequestProperty("X-Install-Id",LabRuntime.get(context).installId);
            if(token!=null)c.setRequestProperty("Authorization","Bearer "+token);if(id!=null)c.setRequestProperty("X-Upload-Id",id);
            try(java.io.OutputStream out=c.getOutputStream()){out.write(body);}status=c.getResponseCode();if(status<200||status>299)throw httpFailure(c,status);
            return new String(read(c.getInputStream(),32768),StandardCharsets.UTF_8);
        }catch(Exception e){category=e instanceof HttpFailure?((HttpFailure)e).code:e.getClass().getSimpleName();throw e;}
        finally{deadline.cancel(false);c.disconnect();log.diagnostic("http_result","path",path,"status",status,"category",category,
            "elapsedMs",android.os.SystemClock.elapsedRealtime()-started,"requestBytes",body.length);}
    }
    File download(JSONObject release)throws Exception {return download(release,null);}
    File download(JSONObject release,DownloadProgress progress)throws Exception {
        File file=new File(context.getCacheDir(),"update/OpenNinebot.apk");
        if(!file.getParentFile().isDirectory()&&!file.getParentFile().mkdirs())throw new java.io.IOException("download_directory_failed");
        long expected=release.getLong("bytes");String digest=release.getString("sha256");
        android.content.SharedPreferences prefs=context.getSharedPreferences("github-download",0);
        boolean resumable=digest.equals(prefs.getString("hash",""))&&file.isFile()&&file.length()<=expected;
        if(!resumable){
            // Commit the empty file before associating it with a new signed hash.
            // A failed connection must never reuse an older release's prefix.
            try(FileOutputStream empty=new FileOutputStream(file,false)){empty.getFD().sync();}
            if(!prefs.edit().putString("hash",digest).commit())throw new java.io.IOException("download_state_failed");
        }
        Exception last=null;
        for(int attempt=0;attempt<3;attempt++)try{return downloadAttempt(release,file,digest,expected,progress,attempt);}
        catch(Exception error){last=error;if(attempt==2||!retryable(error))throw error;retryDelay(attempt,"apk",error);}
        throw last;
    }
    private File downloadAttempt(JSONObject release,File file,String digest,long expected,DownloadProgress progress,int attempt)throws Exception {
        android.content.SharedPreferences prefs=context.getSharedPreferences("github-download",0);
        boolean resumable=digest.equals(prefs.getString("hash",""))&&file.isFile()&&file.length()<=expected;
        long offset=resumable?file.length():0;
        if(progress!=null)progress.update(offset,expected);
        if(offset==expected)return file;
        HttpURLConnection c=GitHubUpdates.downloadConnection(context,release.getString("url"),offset);

        c.setReadTimeout(45000);
        long started=android.os.SystemClock.elapsedRealtime(),count=offset;int status=0;String category="ok";
        java.util.concurrent.ScheduledFuture<?> deadline=deadlines.schedule(c::disconnect,600,java.util.concurrent.TimeUnit.SECONDS);
        try{status=c.getResponseCode();
            if(status==206 && offset>0) {
                if(!("bytes "+offset+"-"+(expected-1)+"/"+expected).equals(c.getHeaderField("Content-Range")))
                    throw new java.io.IOException("invalid_content_range");
            } else if(status==200) {offset=0;count=0;}
            else throw httpFailure(c,status);
            log.diagnostic("download_resume","resumedBytes",offset,"expectedBytes",expected);
            if(progress!=null)progress.update(count,expected);
            try(InputStream in=c.getInputStream();FileOutputStream out=new FileOutputStream(file,offset>0)){
                long lastProgress=0;byte[] buffer=new byte[32768];int n;while((n=in.read(buffer))!=-1){count+=n;if(count>expected)throw new java.io.IOException();out.write(buffer,0,n);long now=android.os.SystemClock.elapsedRealtime();if(progress!=null&&(now-lastProgress>=150||count==expected)){progress.update(count,expected);lastProgress=now;}}out.getFD().sync();
            }if(count!=expected)throw new java.io.IOException();return file;
        }catch(Exception e){category=e.getClass().getSimpleName();throw e;}
        finally{deadline.cancel(false);c.disconnect();log.diagnostic("http_result","path","apk","status",status,"category",category,
            "elapsedMs",android.os.SystemClock.elapsedRealtime()-started,"receivedBytes",count);}
    }
    private void retryDelay(int attempt,String path,Exception error)throws InterruptedException {
        long delay=700L*(attempt+1);log.diagnostic("http_retry","path",path,"attempt",attempt+1,"delayMs",delay,"category",error.getClass().getSimpleName());
        SystemClock.sleep(delay);
    }
    private static boolean retryable(Exception error){
        if(error instanceof HttpFailure)return ((HttpFailure)error).status>=500;
        return error instanceof java.io.IOException;
    }
    void resetDownload(){context.getSharedPreferences("github-download",0).edit().remove("hash").commit();}
    static HttpFailure httpFailure(HttpURLConnection connection,int status) {
        String code="unknown";int retry=0;
        try{String response=new String(read(connection.getErrorStream(),2048),StandardCharsets.UTF_8);String value=new JSONObject(response).optString("error");
            if(value.matches("rate_limit|upload_busy|archive_quota|daily_quota|invalid_archive|invalid_upload_id|storage_unavailable|upload_id_collision"))code=value;
        }catch(Exception ignored){}
        try{retry=Integer.parseInt(connection.getHeaderField("Retry-After"));}catch(Exception ignored){}
        int fallback="unknown".equals(code)||"upload_busy".equals(code)?2:60;
        return new HttpFailure(status,code,status==429?Math.max(1,retry==0?fallback:retry):0);
    }
    static byte[] read(InputStream in,int max)throws Exception {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] buf=new byte[8192];int n;while((n=stream.read(buf))!=-1){if(out.size()+n>max)throw new java.io.IOException("response_too_large");out.write(buf,0,n);}return out.toByteArray();
        }
    }
}
