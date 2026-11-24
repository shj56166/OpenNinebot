package com.motexa.openninebot;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.HashSet;
import java.util.Set;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=24)
public class PersistentSessionLogTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Context context()throws Exception {
        File root=temporary.newFolder();org.robolectric.shadows.ShadowStatFs.registerStats(root.getAbsolutePath(),1_000_000,1_000_000,1_000_000);return new ContextWrapper(RuntimeEnvironment.getApplication()){
            public Context getApplicationContext(){return this;}
            public File getFilesDir(){return root;}
        };
    }
    private String content(File directory,String name)throws Exception {
        return new String(Files.readAllBytes(new File(directory,name).toPath()),StandardCharsets.UTF_8);
    }
    @Test public void restartPreservesAllRecordsAndPendingSnapshot()throws Exception {
        Context context=context();long closedCount;String id;
        try(SessionLog first=new SessionLog(context)){
            first.log("before_restart");closedCount=first.recordCount()+1;
        }
        try(SessionLog reopened=new SessionLog(context)){
            assertEquals(closedCount+1,reopened.recordCount());
            assertTrue(content(reopened.getSessionDirectory(),"events.ndjson").contains("before_restart"));
            reopened.beginUploadBatch();SessionLog.Snapshot snapshot=reopened.createUploadSnapshot();id=snapshot.id;
            assertTrue(id.matches("[a-f0-9]{32}"));assertTrue(content(snapshot.directory,"events.ndjson").contains("before_restart"));
        }
        try(SessionLog again=new SessionLog(context)){
            SessionLog.Snapshot retry=again.createUploadSnapshot();assertEquals(id,retry.id);
            long before=again.recordCount();again.log("written_during_upload");
            again.acknowledgeUpload(retry);assertEquals(before+1-retry.recordCount,again.recordCount());
            assertNull(again.createUploadSnapshot());
            assertTrue(content(again.getSessionDirectory(),"events.ndjson").contains("written_during_upload"));
            again.acknowledgeUpload(retry);assertNull(again.createUploadSnapshot());
        }
    }
    @Test public void multipleBatchesCoverEveryRecordWithoutTruncationOrNewRecordDeletion()throws Exception {
        Context context=context();String large="x".repeat(40000);Set<String> ids=new HashSet<>();
        try(SessionLog log=new SessionLog(context)){
            for(int i=0;i<90;i++)log.diagnostic("large_fixture","index",i,"detail",large);
            long frozen=log.recordCount();assertTrue(new File(log.getSessionDirectory(),"events.ndjson").length()>2*1024*1024);
            log.beginUploadBatch();log.log("keep_after_cutoff");long acknowledged=0;int batches=0;
            SessionLog.Snapshot snapshot;
            while((snapshot=log.createUploadSnapshot())!=null){
                assertTrue(ids.add(snapshot.id));long bytes=0;for(String name:new String[]{"events.ndjson","metrics.ndjson","stream.ndjson"})bytes+=new File(snapshot.directory,name).length();
                assertTrue(bytes<=1500000);assertFalse(content(snapshot.directory,"events.ndjson").contains("keep_after_cutoff"));
                assertEquals(snapshot.id,log.createUploadSnapshot().id);acknowledged+=snapshot.recordCount;log.acknowledgeUpload(snapshot);batches++;
            }
            assertTrue(batches>=3);assertEquals(frozen,acknowledged);assertEquals(1,log.recordCount());
            log.beginUploadBatch();snapshot=log.createUploadSnapshot();assertEquals(1,snapshot.recordCount);
            assertTrue(content(snapshot.directory,"events.ndjson").contains("keep_after_cutoff"));
            log.acknowledgeUpload(snapshot);assertEquals(0,log.recordCount());assertNull(log.createUploadSnapshot());
        }
    }
    @Test public void durableFileKeepsMoreThanTwoMegabytesAcrossReopen()throws Exception {
        File file=temporary.newFile();String line="a".repeat(50000);
        try(DurableTextFile durable=new DurableTextFile(file,false)){for(int i=0;i<50;i++)durable.appendLine(line,false);}
        long bytes=file.length();assertEquals(50L*(line.length()+1),bytes);
        try(DurableTextFile durable=new DurableTextFile(file,true)){durable.appendLine("after_reopen",true);}
        assertEquals(bytes+13,file.length());assertTrue(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8).endsWith("after_reopen\n"));
    }
    @Test public void committedServerAcknowledgementRecoversAfterRestart()throws Exception {
        Context context=context();long remaining;
        try(SessionLog log=new SessionLog(context)){
            log.log("uploaded_before_crash");log.beginUploadBatch();SessionLog.Snapshot snapshot=log.createUploadSnapshot();
            log.log("unuploaded_new_record");remaining=log.recordCount()-snapshot.recordCount+1;
            File manifest=new File(snapshot.directory,"manifest.json");
            try(FileOutputStream out=new FileOutputStream(new File(snapshot.directory,"acknowledged.json"))){out.write(Files.readAllBytes(manifest.toPath()));out.getFD().sync();}
        }
        try(SessionLog restored=new SessionLog(context)){
            assertEquals(remaining+1,restored.recordCount());assertNull(restored.createUploadSnapshot());
            assertTrue(content(restored.getSessionDirectory(),"events.ndjson").contains("unuploaded_new_record"));
            restored.beginUploadBatch();SessionLog.Snapshot next=restored.createUploadSnapshot();assertFalse(content(next.directory,"events.ndjson").contains("uploaded_before_crash"));
        }
    }
    @Test public void interruptedTailIsPreservedSeparatedAndCounted()throws Exception {
        Context context=context();File directory=new File(context.getFilesDir(),"openninebot/current");assertTrue(directory.mkdirs());
        File events=new File(directory,"events.ndjson");
        try(FileOutputStream out=new FileOutputStream(events)){out.write("{\"interrupted\":\"half".getBytes(StandardCharsets.UTF_8));out.getFD().sync();}
        try(SessionLog log=new SessionLog(context)){
            log.log("new_complete_record");assertEquals(3,log.recordCount());
            String bytes=content(directory,"events.ndjson");assertTrue(bytes.startsWith("{\"interrupted\":\"half\n{"));
            log.beginUploadBatch();SessionLog.Snapshot snapshot=log.createUploadSnapshot();assertEquals(3,snapshot.recordCount);
            assertTrue(content(snapshot.directory,"events.ndjson").contains("new_complete_record"));
        }
    }
    @Test public void acknowledgementSurvivesSnapshotCleanupInterruptedAfterManifest()throws Exception {
        Context context=context();
        try(SessionLog log=new SessionLog(context)){
            log.log("already_uploaded");log.beginUploadBatch();SessionLog.Snapshot snapshot=log.createUploadSnapshot();
            try(FileOutputStream out=new FileOutputStream(new File(snapshot.directory,"acknowledged.json"))){out.write(Files.readAllBytes(new File(snapshot.directory,"manifest.json").toPath()));out.getFD().sync();}
            assertTrue(new File(snapshot.directory,"manifest.json").delete());
            log.log("after_confirmed_cutoff");
        }
        try(SessionLog restored=new SessionLog(context)){
            assertNull(restored.createUploadSnapshot());restored.beginUploadBatch();SessionLog.Snapshot next=restored.createUploadSnapshot();
            assertFalse(content(next.directory,"events.ndjson").contains("already_uploaded"));assertTrue(content(next.directory,"events.ndjson").contains("after_confirmed_cutoff"));
        }
    }
}
