package com.motexa.openninebot;

import android.content.Context;
import android.os.StatFs;
import android.os.SystemClock;
import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Append-only, bounded asynchronous binary capture; acknowledged parts remain exportable. */
final class DetailedTrace implements AutoCloseable {
    static final long MIN_FREE_BYTES=512L*1024*1024;
    private static final int PART_BYTES=1_000_000, RECORD_BYTES=256_000;
    private static final long MAX_QUEUED_BYTES=16L*1024*1024;
    private final File directory;
    private final String runId;
    private final ThreadPoolExecutor writer;
    private final AtomicLong queuedBytes=new AtomicLong(), sequence=new AtomicLong();
    private final Consumer<String> failure;
    private final java.util.function.LongSupplier availableBytes;
    private volatile String error="";
    private volatile boolean closed;
    private File current;
    private DataOutputStream output;
    private long bytes,records;
    private volatile long totalBytes,totalRecords,writeTimeNs,maxWriteTimeNs;

    DetailedTrace(Context context,Consumer<String> failure){
        this(context,failure,null);
    }
    DetailedTrace(Context context,Consumer<String> failure,java.util.function.LongSupplier availableBytes){
        this(context,UUID.randomUUID().toString().replace("-",""),failure,availableBytes);
    }
    DetailedTrace(Context context,String runId,Consumer<String> failure){this(context,runId,failure,null);}
    private DetailedTrace(Context context,String runId,Consumer<String> failure,java.util.function.LongSupplier availableBytes){
        if(!runId.matches("[a-f0-9]{32}"))throw new IllegalArgumentException("trace_run_id");this.runId=runId;
        directory=new File(context.getFilesDir(),"openninebot/traces");this.failure=failure;
        this.availableBytes=availableBytes==null?()->new StatFs(directory.getAbsolutePath()).getAvailableBytes():availableBytes;
        writer=new ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,new ArrayBlockingQueue<>(256),r->{Thread t=new Thread(r,"cast-trace-writer");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
        if(!directory.isDirectory()&&!directory.mkdirs())fail("trace_directory_failed");
        // A killed process may leave complete records in .open; preserve every byte.
        enqueue(0,()->{
            File[] interrupted=directory.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.(open|bin)"));
            if(interrupted!=null)for(File file:interrupted)if(file.getName().endsWith(".open")||!new File(directory,file.getName().substring(0,32)+".json").isFile())seal(file,true);
        });
    }
    boolean healthy(){return error.isEmpty()&&!closed;}
    String error(){return error;}
    void record(String channel,byte[] payload,JSONObject fields){
        if(!healthy())return;
        byte[] copy=payload==null?new byte[0]:payload.clone();
        String detail=fields==null?"{}":fields.toString();
        long elapsedNs=SystemClock.elapsedRealtimeNanos(),wallMs=System.currentTimeMillis();
        enqueue(copy.length+detail.length()*2L,()->{
            // IDs describe serialization order, not the racing producer threads' enqueue order.
            long id=sequence.incrementAndGet();
            if(availableBytes.getAsLong()<MIN_FREE_BYTES)throw new IOException("trace_storage_low");
            String digest=sha256(copy);
            for(int offset=0;offset<copy.length||offset==0;offset+=RECORD_BYTES){
                int length=Math.min(RECORD_BYTES,copy.length-offset);
                JSONObject h=new JSONObject(detail).put("traceVersion",4).put("runId",runId).put("recordId",id)
                    .put("channel",channel).put("elapsedNs",elapsedNs).put("tsEpochMs",wallMs)
                    .put("totalBytes",copy.length).put("offset",offset).put("bytes",length).put("sha256",digest);
                byte[] header=h.toString().getBytes(StandardCharsets.UTF_8);
                if(header.length>65536)throw new IOException("trace_header_too_large");
                if(output==null||bytes+12+header.length+length>PART_BYTES){finishPart();current=new File(directory,UUID.randomUUID().toString().replace("-","")+".open");output=new DataOutputStream(new BufferedOutputStream(new FileOutputStream(current)));bytes=records=0;}
                output.writeInt(0x4d544334);output.writeInt(header.length);output.writeInt(length);output.write(header);output.write(copy,offset,length);
                bytes+=12L+header.length+length;records++;totalBytes+=length;
                if(copy.length==0)break;
            }
            totalRecords++;
            output.flush();
        });
    }
    private interface Task { void run()throws Exception; }
    private void enqueue(long weight,Task task){
        if(!healthy())return;
        if(queuedBytes.addAndGet(weight)>MAX_QUEUED_BYTES){queuedBytes.addAndGet(-weight);fail("trace_queue_full");return;}
        try{writer.execute(()->{long started=SystemClock.elapsedRealtimeNanos();try{task.run();}catch(Exception e){fail(e.getMessage()!=null&&e.getMessage().matches("[a-z_]{1,80}")?e.getMessage():"trace_write_failed");}finally{long elapsed=SystemClock.elapsedRealtimeNanos()-started;writeTimeNs+=elapsed;maxWriteTimeNs=Math.max(maxWriteTimeNs,elapsed);queuedBytes.addAndGet(-weight);}});}
        catch(RejectedExecutionException e){queuedBytes.addAndGet(-weight);fail("trace_queue_full");}
    }
    private void fail(String code){if(!error.isEmpty())return;error=code;if(failure!=null)failure.accept(code);}
    void checkpoint()throws Exception{if(Thread.currentThread().getName().equals("cast-trace-writer"))return;writer.submit(()->{try{finishPart();}catch(Exception e){fail("trace_sync_failed");throw new IllegalStateException(e);}}).get(30,TimeUnit.SECONDS);}
    private void finishPart()throws Exception{if(output==null)return;output.flush();output.close();output=null;try(RandomAccessFile sync=new RandomAccessFile(current,"rw")){sync.getFD().sync();}seal(current,false);current=null;}
    private void seal(File source,boolean interrupted)throws Exception{
        if(!source.isFile()||source.length()==0)return;
        String id=source.getName().substring(0,32);File data=new File(directory,id+".bin");
        if(!source.equals(data)&&!source.renameTo(data)&&!data.isFile())throw new IOException("trace_commit_failed");
        String recordedRun=runId;
        if(interrupted){try(DataInputStream input=new DataInputStream(new FileInputStream(data))){
            if(input.readInt()!=0x4d544334)throw new IOException("trace_recovery_failed");int headerSize=input.readInt();input.readInt();
            if(headerSize<2||headerSize>65536)throw new IOException("trace_recovery_failed");byte[] header=new byte[headerSize];input.readFully(header);
            recordedRun=new JSONObject(new String(header,StandardCharsets.UTF_8)).getString("runId");
            if(!recordedRun.matches("[a-f0-9]{32}"))throw new IOException("trace_recovery_failed");
        }}
        JSONObject meta=new JSONObject().put("id",id).put("runId",recordedRun).put("bytes",data.length()).put("sha256",sha256(data)).put("interrupted",interrupted);
        File pending=new File(directory,id+".json");writeAtomic(pending,meta.toString().getBytes(StandardCharsets.UTF_8));
    }
    List<File> pending()throws Exception{checkpoint();File[] files=directory.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.json")&&!new File(d,n.substring(0,32)+".acked").isFile());List<File> result=files==null?new ArrayList<>():new ArrayList<>(Arrays.asList(files));result.sort(Comparator.comparing(File::getName));return result;}
    JSONObject metadata(File file)throws Exception{return new JSONObject(new String(read(file),StandardCharsets.UTF_8));}
    File data(File meta){return new File(directory,meta.getName().substring(0,32)+".bin");}
    void acknowledge(File meta)throws Exception{JSONObject value=metadata(meta);writeAtomic(new File(directory,value.getString("id")+".acked"),value.toString().getBytes(StandardCharsets.UTF_8));}
    JSONObject snapshot(){JSONObject value=new JSONObject();try{value.put("runId",runId).put("records",totalRecords).put("payloadBytes",totalBytes).put("queuedBytes",queuedBytes.get()).put("queuedTasks",writer.getQueue().size()).put("writerTimeNs",writeTimeNs).put("maxWriterTaskNs",maxWriteTimeNs).put("error",error).put("minimumFreeBytes",MIN_FREE_BYTES);}catch(Exception ignored){}return value;}
    File export(File target,JSONObject summary)throws Exception{
        return export(target,summary,Collections.emptyMap());
    }
    File export(File target,JSONObject summary,Map<String,File> journal)throws Exception{
        checkpoint();File building=new File(target.getPath()+".building");org.json.JSONArray manifest=new org.json.JSONArray();
        try(FileOutputStream fileOutput=new FileOutputStream(building);ZipOutputStream zip=new ZipOutputStream(fileOutput)){
            zip.putNextEntry(new ZipEntry("report.json"));zip.write(summary.toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("report.md"));zip.write(("# 投屏诊断报告\n\n本地提交与编码结果不代表仪表显示确认。\n\n```json\n"+summary.toString(2)+"\n```\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            Map<String,File> files=new TreeMap<>(journal);File[] parts=directory.listFiles((d,n)->n.matches("[a-f0-9]{32}\\.(bin|json)"));if(parts!=null)for(File file:parts)files.put("traces/"+file.getName(),file);
            for(Map.Entry<String,File> entry:files.entrySet()){
                File file=entry.getValue();zip.putNextEntry(new ZipEntry(entry.getKey()));MessageDigest digest=MessageDigest.getInstance("SHA-256");long copied=0;
                try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1){zip.write(b,0,n);digest.update(b,0,n);copied+=n;}}zip.closeEntry();
                manifest.put(new JSONObject().put("path",entry.getKey()).put("bytes",copied).put("sha256",hex(digest.digest())));
            }
            zip.putNextEntry(new ZipEntry("files.json"));zip.write(new JSONObject().put("archiveVersion",4).put("files",manifest).toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            Map<String,JSONObject> reports=new TreeMap<>();
            for(Map.Entry<String,File> entry:journal.entrySet())if(entry.getKey().endsWith("/metrics.ndjson")){
                try(BufferedReader reader=new BufferedReader(new InputStreamReader(new FileInputStream(entry.getValue()),StandardCharsets.UTF_8))){String line;while((line=reader.readLine())!=null){
                    JSONObject metric=new JSONObject(line);String stream=metric.optString("streamId"),source=metric.optString("source");
                    if(!stream.matches("[a-f0-9]{32}")||(!source.equals("mirror")&&!source.equals("test_pattern")))continue;
                    String key=source+"-"+stream;JSONObject report=reports.get(key);
                    if(report==null){report=new JSONObject().put("streamId",stream).put("source",source).put("firstMetric",metric).put("samples",0).put("displayConfirmation","not_measured").put("hardwareEncodeLatencyMs",JSONObject.NULL);reports.put(key,report);}
                    report.put("lastMetric",metric).put("samples",report.getInt("samples")+1);
                }}
            }
            for(Map.Entry<String,JSONObject> entry:reports.entrySet()){
                zip.putNextEntry(new ZipEntry("reports/"+entry.getKey()+".json"));zip.write(entry.getValue().toString(2).getBytes(StandardCharsets.UTF_8));zip.closeEntry();
                String source=entry.getValue().getString("source").equals("mirror")?"手机屏幕镜像":"动态示例画面";
                zip.putNextEntry(new ZipEntry("reports/"+entry.getKey()+".md"));zip.write(("# "+source+"诊断报告\n\n本报告独立关联一个编码流；本地提交不代表仪表显示确认。\n\n```json\n"+entry.getValue().toString(2)+"\n```\n").getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            }
            zip.finish();zip.flush();fileOutput.getFD().sync();
        }
        if(!building.renameTo(target))throw new IOException("export_commit_failed");return target;
    }
    static byte[] read(File file)throws IOException{try(InputStream in=new FileInputStream(file);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)out.write(b,0,n);return out.toByteArray();}}
    static String sha256(byte[] bytes)throws Exception{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static String sha256(File file)throws Exception{MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return hex(md.digest());}
    private static String hex(byte[] bytes){StringBuilder value=new StringBuilder();for(byte b:bytes)value.append(String.format(Locale.ROOT,"%02x",b&255));return value.toString();}
    private static void writeAtomic(File file,byte[] data)throws IOException{android.util.AtomicFile atomic=new android.util.AtomicFile(file);FileOutputStream out=atomic.startWrite();try{out.write(data);atomic.finishWrite(out);}catch(IOException e){atomic.failWrite(out);throw e;}}
    public void close(){try{checkpoint();}catch(Exception e){fail("trace_close_failed");}closed=true;writer.shutdown();}
}
