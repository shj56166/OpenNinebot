package com.motexa.openninebot;

import android.content.Context;
import android.content.Intent;
import android.os.Process;
import android.os.SystemClock;
import android.util.AtomicFile;
import android.util.Log;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.BufferedInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.Locale;

/** Persistent journal. Only a server-confirmed immutable upload batch retires records. */
final class SessionLog implements AutoCloseable {
    static final String ACTION_LOG_LINE="com.motexa.openninebot.LOG_LINE",EXTRA_LINE="line";
    private static final String TAG="OpenNinebot";
    private static final Object LOCK=new Object();
    private static final String[] NAMES={"events.ndjson","metrics.ndjson","stream.ndjson"};
    private static final long BATCH_BYTES=1500000;
    private static final String LEGACY_ID="00000000000000000000000000000001";
    private final Context context;
    private final String runId;
    private final File base,runDirectory,segmentsDirectory,pendingDirectory;
    private DurableTextFile events,metrics,stream;
    private volatile long recordCount;
    private volatile boolean closed;
    private volatile java.util.function.Consumer<String> failureListener;
    private final java.util.concurrent.atomic.AtomicBoolean writeFailed=new java.util.concurrent.atomic.AtomicBoolean();
    private final java.util.concurrent.atomic.AtomicBoolean failureDelivered=new java.util.concurrent.atomic.AtomicBoolean();
    private volatile String failureCode;
    private final java.util.concurrent.ThreadPoolExecutor writer = new java.util.concurrent.ThreadPoolExecutor(
        1,1,0,java.util.concurrent.TimeUnit.MILLISECONDS,new java.util.concurrent.ArrayBlockingQueue<>(1024),
        task->{Thread thread=new Thread(task,"cast-journal-writer");thread.setDaemon(true);return thread;});
    private volatile DetailedTrace detailedTrace;
    private long lastStreamSecond=-1,streamPulls,streamBytes,streamEmpty,streamMaxAge,streamMaxWait,streamMaxWrite;
    private JSONObject streamSample;
    private final java.util.LinkedHashMap<String,JSONObject> lastEvents=new java.util.LinkedHashMap<>();

    static final class Snapshot {
        final String id;
        final File directory;
        final long recordCount;
        private Snapshot(String id,File directory,long count){this.id=id;this.directory=directory;recordCount=count;}
    }

    SessionLog(Context context){
        this.context=context.getApplicationContext();
        runId=java.util.UUID.randomUUID().toString().replace("-","");
        base=new File(this.context.getFilesDir(),"openninebot");runDirectory=new File(base,"current");
        segmentsDirectory=new File(base,"segments");pendingDirectory=new File(base,"upload-pending");
        synchronized(LOCK){try{
            ensureDirectory(base);ensureDirectory(segmentsDirectory);ensureDirectory(runDirectory);
            migratePrevious();recoverAcknowledgement();openCurrent();recordCount=countOutstanding();
        }catch(Exception error){String code=error.getMessage();Log.e(TAG,"journalOpenFailed category="+error.getClass().getSimpleName()+" code="+(code!=null&&code.matches("[a-z_]{1,64}")?code:"unknown")+" errno="+(error.getCause() instanceof android.system.ErrnoException?((android.system.ErrnoException)error.getCause()).errno:0));}}
        event("run_started",object("runId",runId,"persistent",true));
    }
    String getRunId(){return runId;}
    File getSessionDirectory(){return runDirectory;}
    File getLatestDirectory(){return runDirectory;}
    File getPreviousDirectory(){return new File(base,"previous");}
    long recordCount(){awaitWrites();synchronized(LOCK){return recordCount;}}
    long displayedRecordCount(){return recordCount;}
    void attachTrace(DetailedTrace trace,java.util.function.Consumer<String> listener){detailedTrace=trace;failureListener=listener;deliverFailure();}
    void trace(String channel,byte[] bytes,JSONObject fields){DetailedTrace trace=detailedTrace;if(trace!=null)trace.record(channel,bytes,fields);}

    void log(String message){
        String safe=DiagnosticPrivacy.text(message==null?"":message.replace('\r',' ').replace('\n',' '));
        Log.i(TAG,safe);JSONObject value=new JSONObject();put(value,"message",safe);writeEvent("log",value);
        Intent update=new Intent(ACTION_LOG_LINE).setPackage(context.getPackageName());update.putExtra(EXTRA_LINE,safe);context.sendBroadcast(update);
    }
    void event(String type,JSONObject detail){
        JSONObject value=detail==null?new JSONObject():DiagnosticPrivacy.object(detail);put(value,"type",type==null?"event":type);stamp(value);
        write(value,0);
        synchronized(lastEvents){lastEvents.put(type,value);while(lastEvents.size()>64)lastEvents.remove(lastEvents.keySet().iterator().next());}
        Log.i(TAG,value.toString());
    }
    void diagnostic(String type,Object... fields){JSONObject detail=new JSONObject();for(int i=0;i+1<fields.length;i+=2)put(detail,String.valueOf(fields[i]),fields[i+1]);event(type,detail);}
    void metric(JSONObject value){value=DiagnosticPrivacy.object(value);stamp(value);write(value,1);}
    synchronized void stream(JSONObject value){
        JSONObject frame=DiagnosticPrivacy.object(value);put(frame,"type","media_frame");stamp(frame);write(frame,2);
        long second=SystemClock.elapsedRealtime()/1000;if(lastStreamSecond!=-1&&second!=lastStreamSecond)flushStream();lastStreamSecond=second;
        streamPulls++;streamBytes+=value.optLong("auBytes");if(!value.optBoolean("sent"))streamEmpty++;
        streamMaxAge=Math.max(streamMaxAge,value.optLong("frameAgeMs"));streamMaxWait=Math.max(streamMaxWait,value.optLong("pullWaitMs"));streamMaxWrite=Math.max(streamMaxWrite,value.optLong("writeMs"));streamSample=DiagnosticPrivacy.object(value);
    }
    private synchronized void flushStream(){
        if(streamSample==null||streamPulls==0)return;
        put(streamSample,"pullsSinceLastSample",streamPulls);put(streamSample,"bytesSinceLastSample",streamBytes);put(streamSample,"emptyPullsSinceLastSample",streamEmpty);
        put(streamSample,"maxFrameAgeMs",streamMaxAge);put(streamSample,"maxPullWaitMs",streamMaxWait);put(streamSample,"maxWriteMs",streamMaxWrite);
        stamp(streamSample);write(streamSample,2);streamPulls=streamBytes=streamEmpty=streamMaxAge=streamMaxWait=streamMaxWrite=0;streamSample=null;
    }
    JSONObject summary(){JSONObject result=new JSONObject();synchronized(lastEvents){for(java.util.Map.Entry<String,JSONObject> entry:lastEvents.entrySet())put(result,entry.getKey(),entry.getValue());}return DiagnosticPrivacy.object(result);}
    void dump(String reason,JSONObject detail){JSONObject value=detail==null?new JSONObject():detail;put(value,"reason",reason==null?"adb":reason);event("dump",value);flush();}
    void flush(){flushStream();awaitWrites();synchronized(LOCK){try{forceCurrent();}catch(IOException error){journalFailure("journal_force_failed");}}}

    /** Freeze this invocation's cutoff. New records remain in current for a later upload. */
    void beginUploadBatch()throws IOException {
        flushStream();awaitWrites();synchronized(LOCK){ensureOpen();forceCurrent();
            if(countFiles(runDirectory)==0)return;
            closeCurrent();File target=new File(segmentsDirectory,java.util.UUID.randomUUID().toString().replace("-", ""));
            if(!runDirectory.renameTo(target)){openCurrent();throw new IOException("journal_rotate_failed");}
            syncDirectory(segmentsDirectory);syncDirectory(base);
            ensureDirectory(runDirectory);openCurrent();
        }
    }
    /** Returns the same durable pending snapshot after failure/restart, or null at the frozen cutoff. */
    Snapshot createUploadSnapshot()throws IOException {
        synchronized(LOCK){ensureOpen();try{
            recoverAcknowledgement();
            if(pendingDirectory.isDirectory()){
                if(atomicExists(new File(pendingDirectory,"manifest.json")))return snapshot(readJson(new File(pendingDirectory,"manifest.json")));
                // An interrupted final cleanup can leave an empty directory;
                // source cursors remain durable and decide what is outstanding.
                clearKnown(pendingDirectory);
            }
            File stage=new File(base,"upload-building");clearKnown(stage);ensureDirectory(stage);
            JSONObject manifest=new JSONObject();JSONArray ranges=new JSONArray();long bytes=0,rows=0;
            java.util.Map<String,FileOutputStream> outputs=new java.util.HashMap<>();
            try{
                for(String name:NAMES)outputs.put(name,new FileOutputStream(new File(stage,name)));
                outer:for(File segment:listSegments()){
                    JSONObject acknowledged=readOptionalJson(new File(segment,"confirmed.json"));
                    for(String name:NAMES){File source=new File(segment,name);if(!source.isFile())continue;
                        long start=acknowledged.optLong(name,0),end=start,count=0;
                        try(LineReader input=new LineReader(source,start)){
                            byte[] line;
                            while((line=input.readLine())!=null){
                                if(bytes+line.length>BATCH_BYTES){if(bytes==0)throw new IOException("journal_record_too_large");break;}
                                outputs.get(name).write(line);bytes+=line.length;rows++;count++;end=input.position;
                            }
                        }
                        if(count>0)ranges.put(new JSONObject().put("segment",segment.getName()).put("file",name).put("start",start).put("end",end).put("count",count));
                        if(bytes>=BATCH_BYTES||end<source.length()||ranges.length()>=100)break outer;
                    }
                }
                for(FileOutputStream output:outputs.values()){output.flush();output.getFD().sync();}
            }finally{for(FileOutputStream output:outputs.values())try{output.close();}catch(IOException ignored){}}
            if(rows==0){clearKnown(stage);return null;}
            manifest.put("id",java.util.UUID.randomUUID().toString().replace("-", "")).put("recordCount",rows).put("bytes",bytes).put("ranges",ranges);
            writeJson(new File(stage,"manifest.json"),manifest);
            if(!stage.renameTo(pendingDirectory))throw new IOException("snapshot_commit_failed");
            syncDirectory(base);
            return snapshot(manifest);
        }catch(JSONException error){throw new IOException("snapshot_manifest_failed",error);}}
    }
    /** Call only after the server accepted this exact snapshot ID. Idempotent across interruption. */
    void acknowledgeUpload(Snapshot acknowledged)throws IOException {
        synchronized(LOCK){ensureOpen();try{
            if(!pendingDirectory.isDirectory())return;
            JSONObject manifest=readJson(new File(pendingDirectory,"manifest.json"));
            if(!manifest.getString("id").equals(acknowledged.id))throw new IOException("snapshot_identity_mismatch");
            writeJson(new File(pendingDirectory,"acknowledged.json"),manifest);
            recoverAcknowledgement();recordCount=countOutstanding();
        }catch(JSONException error){throw new IOException("snapshot_ack_failed",error);}}
    }

    private void recoverAcknowledgement()throws IOException,JSONException {
        File marker=new File(pendingDirectory,"acknowledged.json");if(!atomicExists(marker))return;
        JSONObject manifest=readJson(marker);JSONArray ranges=manifest.getJSONArray("ranges");
        for(int i=0;i<ranges.length();i++){
            JSONObject range=ranges.getJSONObject(i);String id=range.getString("segment"),name=range.getString("file");
            if(!id.matches("[a-f0-9]{32}")||!Arrays.asList(NAMES).contains(name))throw new IOException("invalid_ack_range");
            File segment=new File(segmentsDirectory,id);if(!segment.isDirectory())continue;
            JSONObject state=readOptionalJson(new File(segment,"confirmed.json"));put(state,name,Math.max(state.optLong(name,0),range.getLong("end")));writeJson(new File(segment,"confirmed.json"),state);
        }
        // Upload acknowledgements retire the upload cursor, while complete test
        // evidence remains available for the user's local export.
        File history=new File(base,"history");ensureDirectory(history);
        for(File segment:listSegments())if(countSegment(segment)==0){
            File archived=new File(history,segment.getName());
            if(!segment.renameTo(archived))throw new IOException("journal_archive_failed");
        }
        syncDirectory(history);
        clearKnown(pendingDirectory);syncDirectory(base);
        // Versions before the persistent journal mirrored current into latest.
        // Retire that stale duplicate only after a confirmed upload.
        clearKnown(new File(base,"latest"));
    }
    private Snapshot snapshot(JSONObject manifest)throws JSONException {return new Snapshot(manifest.getString("id"),pendingDirectory,manifest.getLong("recordCount"));}
    private long countOutstanding()throws IOException,JSONException {long count=countFiles(runDirectory);for(File segment:listSegments())count+=countSegment(segment);return count;}
    private long countSegment(File segment)throws IOException,JSONException {
        JSONObject state=readOptionalJson(new File(segment,"confirmed.json"));long count=0;
        for(String name:NAMES)count+=countLines(new File(segment,name),state.optLong(name,0));return count;
    }
    private static long countFiles(File directory)throws IOException {long count=0;for(String name:NAMES)count+=countLines(new File(directory,name),0);return count;}
    private static long countLines(File file,long offset)throws IOException {
        if(!file.isFile())return 0;try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek(offset);long count=0;byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1)for(int i=0;i<n;i++)if(buffer[i]=='\n')count++;return count;}
    }
    private File[] listSegments(){File[] files=segmentsDirectory.listFiles(file->file.isDirectory()&&file.getName().matches("[a-f0-9]{32}"));if(files==null)return new File[0];Arrays.sort(files,(a,b)->{int order=Long.compare(a.lastModified(),b.lastModified());return order!=0?order:a.getName().compareTo(b.getName());});return files;}
    java.util.Map<String,File> exportFiles()throws IOException {
        beginUploadBatch();synchronized(LOCK){
            java.util.Map<String,File> files=new java.util.TreeMap<>();
            for(String group:new String[]{"segments","history"}){
                File[] runs=new File(base,group).listFiles(File::isDirectory);if(runs==null)continue;
                for(File run:runs)if(run.getName().matches("[a-f0-9]{32}"))for(String name:NAMES){File file=new File(run,name);if(file.isFile())files.put("journal/"+group+"/"+run.getName()+"/"+name,file);}
            }
            return files;
        }
    }
    private static final class LineReader implements AutoCloseable {
        final BufferedInputStream input;long position;
        LineReader(File file,long offset)throws IOException {
            FileInputStream source=new FileInputStream(file);source.getChannel().position(offset);input=new BufferedInputStream(source,32768);position=offset;
        }
        byte[] readLine()throws IOException {
            ByteArrayOutputStream line=new ByteArrayOutputStream();int b;
            while((b=input.read())!=-1){position++;line.write(b);if(b=='\n')return line.toByteArray();if(line.size()>BATCH_BYTES)throw new IOException("journal_record_too_large");}
            if(line.size()==0)return null;
            // An older process may have stopped halfway through its last write.
            // Keep those bytes in the immutable upload rather than discarding them.
            line.write('\n');return line.toByteArray();
        }
        public void close()throws IOException {input.close();}
    }
    private void migratePrevious()throws IOException,JSONException {
        File marker=new File(base,"migration-v22.json"),legacy=new File(segmentsDirectory,LEGACY_ID);
        if(atomicExists(marker))return;
        File previous=new File(base,"previous");
        if(!legacy.isDirectory()&&countFiles(previous)>0){
            if(!previous.renameTo(legacy))throw new IOException("legacy_migration_failed");
            syncDirectory(segmentsDirectory);syncDirectory(base);
        }
        writeJson(marker,new JSONObject().put("migrated",true));
    }
    private void openCurrent()throws IOException {ensureDirectory(runDirectory);events=new DurableTextFile(new File(runDirectory,NAMES[0]),true);metrics=new DurableTextFile(new File(runDirectory,NAMES[1]),true);stream=new DurableTextFile(new File(runDirectory,NAMES[2]),true);}
    private void forceCurrent()throws IOException {if(events==null||metrics==null||stream==null)throw new IOException("journal_unavailable");events.force();metrics.force();stream.force();syncDirectory(runDirectory);}
    private void closeCurrent()throws IOException {IOException failure=null;for(DurableTextFile file:new DurableTextFile[]{events,metrics,stream})if(file!=null)try{file.close();}catch(IOException error){failure=error;}events=metrics=stream=null;if(failure!=null)throw failure;}
    private void ensureOpen()throws IOException {if(closed)throw new IOException("journal_closed");if(events==null||metrics==null||stream==null)throw new IOException("journal_unavailable");}
    public void close(){if(closed)return;event("run_stopped",null);flushStream();awaitWrites();synchronized(LOCK){closed=true;try{closeCurrent();}catch(IOException error){journalFailure("journal_close_failed");}}writer.shutdown();}
    private void writeEvent(String type,JSONObject value){put(value,"type",type);stamp(value);write(value,0);}
    private void write(JSONObject value,int kind){
        if(value==null||closed)return;String line=value.toString();
        try{writer.execute(()->{synchronized(LOCK){if(closed)return;try{ensureOpen();if(new android.os.StatFs(base.getAbsolutePath()).getAvailableBytes()<DetailedTrace.MIN_FREE_BYTES)throw new IOException("journal_storage_low");DurableTextFile output=kind==0?events:kind==1?metrics:stream;output.appendLine(line,false);recordCount++;}catch(IOException error){journalFailure("journal_storage_low".equals(error.getMessage())?"journal_storage_low":"journal_append_failed");}}});}
        catch(java.util.concurrent.RejectedExecutionException error){journalFailure("journal_queue_full");}
    }
    private void awaitWrites(){if(Thread.currentThread().getName().equals("cast-journal-writer")||writer.isShutdown())return;try{writer.submit(()->{}).get(30,java.util.concurrent.TimeUnit.SECONDS);}catch(Exception error){journalFailure("journal_flush_failed");}}
    private void journalFailure(String code){if(writeFailed.compareAndSet(false,true)){failureCode=code;Log.e(TAG,code);}deliverFailure();}
    private void deliverFailure(){java.util.function.Consumer<String> listener=failureListener;String code=failureCode;if(listener!=null&&code!=null&&failureDelivered.compareAndSet(false,true))listener.accept(code);}
    private void stamp(JSONObject value){if(value==null)return;if(!value.has("runId"))put(value,"runId",runId);if(!value.has("tsEpochMs"))put(value,"tsEpochMs",System.currentTimeMillis());if(!value.has("uptimeMs"))put(value,"uptimeMs",SystemClock.elapsedRealtime());}
    private static JSONObject object(String a,Object b,String c,Object d){JSONObject out=new JSONObject();put(out,a,b);put(out,c,d);return out;}
    private static void put(JSONObject out,String key,Object value){try{out.put(key,value);}catch(JSONException ignored){}}
    private static void ensureDirectory(File directory)throws IOException {if(!directory.isDirectory()&&!directory.mkdirs()&&!directory.isDirectory())throw new IOException("journal_directory_failed");}
    private static boolean atomicExists(File file){return file.isFile()||new File(file.getPath()+".bak").isFile();}
    private static JSONObject readOptionalJson(File file)throws IOException,JSONException {return atomicExists(file)?readJson(file):new JSONObject();}
    private static JSONObject readJson(File file)throws IOException,JSONException {try(FileInputStream input=new AtomicFile(file).openRead()){ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){if(bytes.size()+n>65536)throw new IOException("manifest_too_large");bytes.write(buffer,0,n);}return new JSONObject(new String(bytes.toByteArray(),StandardCharsets.UTF_8));}}
    private static void writeJson(File file,JSONObject value)throws IOException {
        byte[] bytes=value.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>65536)throw new IOException("manifest_too_large");AtomicFile atomic=new AtomicFile(file);FileOutputStream output=null;
        try{output=atomic.startWrite();output.write(bytes);output.flush();output.getFD().sync();atomic.finishWrite(output);syncDirectory(file.getParentFile());}catch(IOException error){if(output!=null)atomic.failWrite(output);throw error;}
    }
    private static void syncDirectory(File directory)throws IOException {
        java.io.FileDescriptor descriptor=null;
        try{descriptor=android.system.Os.open(directory.getAbsolutePath(),android.system.OsConstants.O_RDONLY,0);android.system.Os.fsync(descriptor);}
        catch(android.system.ErrnoException error){throw new IOException("journal_directory_sync_failed",error);}
        finally{if(descriptor!=null)try{android.system.Os.close(descriptor);}catch(android.system.ErrnoException ignored){}}
    }
    /** Only fixed logs/manifests from known app-owned dirs are removed; never recurse. */
    private static void clearKnown(File directory)throws IOException {
        if(!directory.exists())return;
        for(String name:new String[]{"events.ndjson","metrics.ndjson","stream.ndjson","manifest.json","confirmed.json","acknowledged.json"}){
            for(String suffix:new String[]{"",".new",".bak"}){File file=new File(directory,name+suffix);if(file.exists()&&!file.delete())throw new IOException("journal_cleanup_failed");}
        }
        if(!directory.delete())throw new IOException("journal_directory_cleanup_failed");
    }
}
