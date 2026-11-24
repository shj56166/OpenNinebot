package com.motexa.openninebot;
import android.content.*;
import android.os.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.zip.*;
import org.json.JSONObject;

/** Vehicle authentication belongs to the protocol; application use has no trial lease. */
final class LabRuntime {
    private static LabRuntime instance;
    static synchronized LabRuntime get(Context context){if(instance==null)instance=new LabRuntime(context);return instance;}
    final Context context;
    final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService io=Executors.newSingleThreadExecutor(),updateIo=Executors.newSingleThreadExecutor();
    final SessionLog log;
    final DetailedTrace trace;
    final LabClient client;
    final GitHubUpdates updates;
    final String installId;
    final CastVisualFeed castVisuals=new CastVisualFeed(main::post);
    final NinebotTestController ninebot;
    private final boolean nativeReady;
    volatile NinebotTestController.FrameMode pendingFrameMode;
    volatile JSONObject pendingFrameConfig;
    volatile Object frameSourceOwner;
    volatile boolean casting,checking,updating,uploading;
    volatile long updateReceivedBytes,updateTotalBytes,bleServiceGeneration;
    volatile String message="",updateMessage="",uploadMessage="";
    private long uploadCooldownUntil,nextUploadAt;
    private File archive;
    private String archiveId;
    Runnable listener;
    private LabRuntime(Context context){
        this.context=context.getApplicationContext();
        log=new SessionLog(this.context);client=new LabClient(this.context,log);
        SharedPreferences prefs=this.context.getSharedPreferences("lab",0);
        String id=prefs.getString("installId",null);if(id==null){id=randomId();prefs.edit().putString("installId",id).commit();}installId=id;
        trace=new DetailedTrace(this.context,log.getRunId(),code->main.post(()->{message="诊断存储失败，已停止连接与投屏 · "+code;stopAll();notifyUi();}));
        log.attachTrace(trace,code->main.post(()->{message="诊断存储失败 · "+code;stopAll();notifyUi();}));
        boolean loaded=false;try{com.motexa.vehiclecast.NativeRuntime.load("ninebot_cast");loaded=true;}catch(LinkageError|RuntimeException e){message="原生协议加载失败";}nativeReady=loaded;
        updates=new GitHubUpdates(this.context);
        ninebot=new NinebotTestController(this.context,new NinebotTestController.Host(){
            public boolean authorized(){return allowed();}
            public void log(String event,JSONObject fields){log.event(event,fields);}
            public void trace(String channel,byte[] bytes,JSONObject fields){log.trace(channel,bytes,fields);}
            public void changed(){notifyUi();}
            public void requestFrameSource(NinebotTestController.FrameMode mode,JSONObject config){main.post(()->{pendingFrameMode=mode;pendingFrameConfig=config;notifyUi();});}
            public void stopFrameSource(String reason){main.post(()->{LabRuntime.this.context.stopService(new Intent(LabRuntime.this.context,NinebotFrameService.class));pendingFrameMode=null;pendingFrameConfig=null;notifyUi();});}
            public void stopFrameSource(long generation,String reason){main.post(()->{
                Object owner=frameSourceOwner;if(owner instanceof NinebotFrameService&&((NinebotFrameService)owner).generation()==generation)LabRuntime.this.context.stopService(new Intent(LabRuntime.this.context,NinebotFrameService.class));
                if(pendingFrameConfig!=null&&pendingFrameConfig.optLong("generation",-1)==generation){pendingFrameMode=null;pendingFrameConfig=null;}notifyUi();});}
        });
        main.post(ticker);refresh(false);
    }
    static String randomId(){return UUID.randomUUID().toString().replace("-","");}
    boolean allowed(){return nativeReady;}
    void requireStart(){if(!nativeReady)throw new IllegalStateException("原生协议加载失败");if(updates.mandatory())throw new IllegalStateException("请先更新后发起新的连接或投屏；当前会话可正常结束，日志仍可上传");}
    long uploadCooldownSeconds(){return Math.max(0,(uploadCooldownUntil-SystemClock.elapsedRealtime()+999)/1000);}
    JSONObject nativeSnapshot(){return NinebotTestProfile.json("update",updates.available(),"state",nativeReady?"ready":"native_unavailable");}
    void checkUpdate(){refresh(true);}
    void refresh(boolean manual){if(checking)return;checking=true;updateIo.execute(()->{try{updates.refresh(manual);updateMessage=updates.mandatory()?"需要更新 · 当前会话可继续，请更新后再发起新的连接或投屏":updates.available()!=null?"发现新版本，可选择更新":"当前已是最新版本";}catch(Exception e){updateMessage="更新检查失败，保留上次已验证结果";log.diagnostic("update_check_failed","category",e.getClass().getSimpleName());}finally{checking=false;notifyUi();}});}
    void notifyUi(){if(Looper.myLooper()!=Looper.getMainLooper()){main.post(this::notifyUi);return;}if(listener!=null)listener.run();}
    private final Runnable ticker=new Runnable(){public void run(){notifyUi();main.postDelayed(this,1000);}};
    void connectBle(){}
    void stopBle(){ninebot.disconnect();}
    void stopAll(){castVisuals.clear();pendingFrameMode=null;pendingFrameConfig=null;ninebot.disconnect();context.stopService(new Intent(context,NinebotFrameService.class));context.stopService(new Intent(context,LabConnectionService.class));}
    void upload(){
        if(uploading||uploadCooldownSeconds()>0)return;uploading=true;final long consentAt=System.currentTimeMillis();uploadMessage="正在整理日志…";notifyUi();
        io.execute(()->{String lastId="";int completed=0;try{
            log.beginUploadBatch();
            // Preserve an older failed ZIP before sending newly persisted batches.
            if(archive!=null){
                File original=archive,normalized=normalizePendingArchive(archive,new File(context.getNoBackupFilesDir(),"pending-upload-normalized.zip"),consentAt);
                sendArchive(normalized,archiveId);lastId=archiveId;completed++;
                if(!context.getSharedPreferences("lab",0).edit().remove("pendingUpload").commit())throw new java.io.IOException("upload_cleanup_failed");
                archive=null;removeUploadedFile(original);removeUploadedFile(normalized);
            }
            SessionLog.Snapshot snapshot;
            while((snapshot=log.createUploadSnapshot())!=null){
                File file=batchArchive(consentAt,snapshot);uploadMessage="正在上传日志 · 第 "+(completed+1)+" 批";notifyUi();
                // Each batch has a stable id, so retries cannot duplicate feedback.
                sendArchive(file,snapshot.id);log.acknowledgeUpload(snapshot);lastId=snapshot.id;completed++;removeUploadedFile(file);
                if(!context.getSharedPreferences("lab",0).edit().remove("pendingBatchId").commit())throw new java.io.IOException("upload_cleanup_failed");
                uploadMessage="已上传 "+completed+" 批";notifyUi();
            }
            for(File meta:trace.pending()){
                JSONObject part=trace.metadata(meta);File file=new File(context.getNoBackupFilesDir(),"trace-upload-"+part.getString("id")+".zip");
                if(file.isFile()){
                    boolean needsUpgrade;
                    try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(file)){
                        JSONObject saved=new JSONObject(new String(LabClient.read(zip.getInputStream(zip.getEntry("metadata.json")),65536),StandardCharsets.UTF_8));
                        needsUpgrade=saved.optInt("versionCode")!=BuildConfig.VERSION_CODE;
                    }
                    if(needsUpgrade){File upgraded=new File(file.getPath()+".upgrade");normalizePendingArchive(file,upgraded,consentAt);android.system.Os.rename(upgraded.getAbsolutePath(),file.getAbsolutePath());}
                }else makeTraceArchive(file,consentAt,part,trace.data(meta));
                uploadMessage="正在上传轨迹 · 第 "+(completed+1)+" 批";notifyUi();
                sendArchive(file,part.getString("id"));trace.acknowledge(meta);lastId=part.getString("id");completed++;removeUploadedFile(file);
            }
            uploadCooldownUntil=SystemClock.elapsedRealtime()+30000;
            uploadMessage=completed==0?"暂无待上传日志":"上传成功 · "+lastId+"\n已清理已确认上传的记录";
            log.diagnostic("logs_uploaded","recordId",lastId,"batches",completed);
        }catch(Exception e){
            if(e instanceof LabClient.HttpFailure&&((LabClient.HttpFailure)e).status==429)
                uploadCooldownUntil=SystemClock.elapsedRealtime()+((LabClient.HttpFailure)e).retryAfterSeconds*1000L;
            uploadMessage=uploadFailureMessage(e)+"\n未上传日志已保留";
            log.diagnostic("logs_upload_failed","category",e.getClass().getSimpleName(),"httpStatus",e instanceof LabClient.HttpFailure?((LabClient.HttpFailure)e).status:0,"safeCode",e instanceof LabClient.HttpFailure?((LabClient.HttpFailure)e).code:"","completedBatches",completed);
        }finally{main.post(()->{uploading=false;notifyUi();});}});
    }
    private void sendArchive(File file,String id)throws Exception {
        byte[] body=LabClient.read(new java.io.FileInputStream(file),3*1024*1024);
        for(int attempt=0;;attempt++) {
            long wait=Math.max(0,nextUploadAt-SystemClock.elapsedRealtime());
            if(wait>0)Thread.sleep(wait);
            try {
                JSONObject response=new JSONObject(client.post("logs",body,"application/zip",null,id));
                if(!id.equals(response.optString("uploadId")))throw new java.io.IOException("upload_receipt_mismatch");
                nextUploadAt=SystemClock.elapsedRealtime()+10000; return;
            } catch(LabClient.HttpFailure error) {
                if(!error.uploadRateLimited()||attempt>=2||error.retryAfterSeconds>120)throw error;
                long delay=error.retryAfterSeconds*1000L+java.util.concurrent.ThreadLocalRandom.current().nextLong(1000);
                uploadCooldownUntil=SystemClock.elapsedRealtime()+delay;
                uploadMessage="等待上传频率限制 · "+error.retryAfterSeconds+" 秒";notifyUi();
                Thread.sleep(delay);
            }
        }
    }
    static String uploadFailureMessage(Exception e){
        if(e instanceof LabClient.HttpFailure){LabClient.HttpFailure h=(LabClient.HttpFailure)e;
            if(h.status==429)return ("archive_quota".equals(h.code)||"daily_quota".equals(h.code))?"服务器日志空间已满，请稍后重试":"上传较频繁，请稍后重试";
            if(h.status==401||h.status==403)return "上传授权校验失败，请联网后重试";
            if(h.status==400)return "日志归档校验失败，请重试";
            return "服务器暂时不可用（"+h.status+"），请重试";
        }
        if(e instanceof java.net.SocketTimeoutException)return "上传超时，请检查网络后重试";
        if("upload_receipt_mismatch".equals(e.getMessage()))return "服务器接收确认异常，请重试";
        if("upload_grant_unavailable".equals(e.getMessage()))return "上传授权暂不可用，请联网后重试";
        if("upload_cleanup_failed".equals(e.getMessage()))return "日志已接收，本地清理失败，请重试";
        return "上传失败，请检查网络后重试";
    }
    private static void removeUploadedFile(File file)throws java.io.IOException {
        if(file.exists()&&!file.delete())throw new java.io.IOException("upload_cleanup_failed");
    }
    private File batchArchive(long consentAt,SessionLog.Snapshot snapshot)throws Exception {
        File file=new File(context.getNoBackupFilesDir(),"upload-batch.zip");
        android.content.SharedPreferences prefs=context.getSharedPreferences("lab",0);
        File stage=new File(context.getNoBackupFilesDir(),"upload-batch-building.zip");
        if(snapshot.id.equals(prefs.getString("pendingBatchId",""))&&file.isFile()){
            if(prefs.getInt("pendingBatchVersion",0)==BuildConfig.VERSION_CODE)return file;
            // Tokens bind the running APK version. Preserve older batch payloads
            // while migrating only the envelope after an application upgrade.
            normalizePendingArchive(file,stage,consentAt);
        }else makeArchive(stage,consentAt,snapshot);
        try(java.io.RandomAccessFile synced=new java.io.RandomAccessFile(stage,"rw")){synced.getFD().sync();}
        android.system.Os.rename(stage.getAbsolutePath(),file.getAbsolutePath());
        // Persist the ID before sending: a lost response retries identical ZIP bytes.
        if(!prefs.edit().putString("pendingBatchId",snapshot.id).putInt("pendingBatchVersion",BuildConfig.VERSION_CODE).commit())throw new java.io.IOException("upload_state_failed");
        return file;
    }
    private void makeArchive(File target,long consentAt,SessionLog.Snapshot snapshot)throws Exception {
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(target))){
            JSONObject manifest=archiveManifest(consentAt);
            zip.putNextEntry(new ZipEntry("manifest.json"));zip.write(manifest.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            JSONObject active=ninebot.snapshot();
            JSONObject meta=new JSONObject().put("runId",log.getRunId()).put("versionCode",BuildConfig.VERSION_CODE).put("versionName",BuildConfig.VERSION_NAME).put("model",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT).put("blePhase",ninebot.snapshot().optString("stage")).put("castPhase",ninebot.snapshot().optString("stage"))
                .put("archiveVersion",4).put("batchId",snapshot.id).put("batchRecords",snapshot.recordCount).put("persistentUntilUploaded",true)
                .put("lastEvents",log.summary()).put("castSnapshot",active==null?JSONObject.NULL:DiagnosticPrivacy.object(active));
            if(meta.toString().getBytes(StandardCharsets.UTF_8).length>60000){meta.remove("lastEvents");meta.put("summaryOmittedForSize",true);}
            zip.putNextEntry(new ZipEntry("metadata.json"));zip.write(meta.toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            for(String name:new String[]{"events.ndjson","metrics.ndjson","stream.ndjson"}){
                zip.putNextEntry(new ZipEntry(name));
                File source=new File(snapshot.directory,name);if(source.isFile())try(java.io.InputStream in=new java.io.FileInputStream(source)){byte[] buffer=new byte[8192];int n;while((n=in.read(buffer))!=-1)zip.write(buffer,0,n);}
                zip.closeEntry();
            }
        }
    }
    private void makeTraceArchive(File target,long consentAt,JSONObject part,File data)throws Exception{
        File building=new File(target.getPath()+".building");
        try(ZipOutputStream zip=new ZipOutputStream(new FileOutputStream(building))){
            JSONObject manifest=archiveManifest(consentAt).put("tracePart",part);
            JSONObject meta=new JSONObject().put("runId",part.getString("runId")).put("versionCode",BuildConfig.VERSION_CODE)
                .put("versionName",BuildConfig.VERSION_NAME).put("archiveVersion",4).put("batchId",part.getString("id"))
                .put("model",android.os.Build.MODEL).put("sdk",android.os.Build.VERSION.SDK_INT).put("tracePart",part);
            for(String name:new String[]{"manifest.json","metadata.json"}){zip.putNextEntry(new ZipEntry(name));zip.write((name.equals("manifest.json")?manifest:meta).toString().getBytes(StandardCharsets.UTF_8));zip.closeEntry();}
            zip.putNextEntry(new ZipEntry("trace.bin"));try(java.io.InputStream in=new java.io.FileInputStream(data)){byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)zip.write(bytes,0,n);}zip.closeEntry();
        }
        android.system.Os.rename(building.getAbsolutePath(),target.getAbsolutePath());
    }
    private static JSONObject archiveManifest(long consentAt)throws Exception {
        return new JSONObject().put("kind","vehicle-cast-test").put("captureMode","manual-session").put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",BuildConfig.VERSION_CODE).put("consentDiagnostics",true).put("consentVersion","ninebot-manual-v1").put("consentAt",consentAt);
    }
    static File normalizePendingArchive(File archive,File upgraded,long consentAt)throws Exception {
        try(java.util.zip.ZipFile source=new java.util.zip.ZipFile(archive);ZipOutputStream out=new ZipOutputStream(new FileOutputStream(upgraded))){
            java.util.Enumeration<? extends ZipEntry> entries=source.entries();
            boolean hasManifest=false;
            while(entries.hasMoreElements()){ZipEntry entry=entries.nextElement();byte[] bytes=LabClient.read(source.getInputStream(entry),3*1024*1024);
                if("manifest.json".equals(entry.getName()))hasManifest=true;
                if("manifest.json".equals(entry.getName())||"metadata.json".equals(entry.getName())){
                    JSONObject data=new JSONObject(new String(bytes,StandardCharsets.UTF_8));
                    if("metadata.json".equals(entry.getName()))data.put("sourceArchiveVersionCode",data.optInt("versionCode")).put("sourceArchiveVersionName",data.optString("versionName")).put("versionName",BuildConfig.VERSION_NAME);
                    data.put("versionCode",BuildConfig.VERSION_CODE);bytes=data.toString().getBytes(StandardCharsets.UTF_8);
                }
                out.putNextEntry(new ZipEntry(entry.getName()));out.write(bytes);out.closeEntry();
            }
            // v17 had no manifest. This click grants the same explicit manual
            // upload consent as a new batch while keeping the old payload.
            if(!hasManifest){out.putNextEntry(new ZipEntry("manifest.json"));out.write(archiveManifest(consentAt).toString().getBytes(StandardCharsets.UTF_8));out.closeEntry();}
        }
        // Keep the original failed ZIP intact until the server confirms its stable id.
        return upgraded;
    }
}
