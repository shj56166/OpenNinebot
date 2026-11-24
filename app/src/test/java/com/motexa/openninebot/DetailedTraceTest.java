package com.motexa.openninebot;

import android.content.Context;
import android.content.ContextWrapper;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;
import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=28)
public class DetailedTraceTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    private Context context(File folder){return new ContextWrapper(RuntimeEnvironment.getApplication()){
        public Context getApplicationContext(){return this;}
        public File getFilesDir(){return folder;}
    };}
    // Match the persistent journal's host filesystem harness (API 24); API 28
    // Robolectric's directory-fsync implementation is not Windows compatible.
    @Test @Config(sdk=24) public void acknowledgedJournalAndBothSourceReportsRemainInLocalExport()throws Exception{
        File folder=temporary.newFolder();org.robolectric.shadows.ShadowStatFs.registerStats(folder.getAbsolutePath(),1_000_000,1_000_000,1_000_000);
        Context context=context(folder);AtomicReference<String> failure=new AtomicReference<>();
        try(SessionLog log=new SessionLog(context);DetailedTrace trace=new DetailedTrace(context,failure::set,()->2L*1024*1024*1024)){
            log.attachTrace(trace,failure::set);log.metric(new JSONObject().put("streamId","aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa").put("source","mirror").put("encodedFps",20));
            log.metric(new JSONObject().put("streamId","bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb").put("source","test_pattern").put("encodedFps",20));
            log.beginUploadBatch();SessionLog.Snapshot batch=log.createUploadSnapshot();assertNotNull(batch);log.acknowledgeUpload(batch);
            File result=trace.export(new File(folder,"report.zip"),new JSONObject(),log.exportFiles());
            try(java.util.zip.ZipFile zip=new java.util.zip.ZipFile(result)){
                assertNotNull(zip.getEntry("reports/mirror-aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa.json"));assertNotNull(zip.getEntry("reports/test_pattern-bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb.md"));
                assertTrue(java.util.Collections.list(zip.entries()).stream().anyMatch(e->e.getName().startsWith("journal/history/")&&e.getName().endsWith("metrics.ndjson")));
            }
            assertNull(failure.get());
        }
    }
    @Test public void splitBinaryFrameRoundTripsAndAcknowledgedPartsStayExportable()throws Exception{
        File folder=temporary.newFolder();AtomicReference<String> error=new AtomicReference<>();
        byte[] original=new byte[780000];new Random(42).nextBytes(original);
        try(DetailedTrace trace=new DetailedTrace(context(folder),error::set,()->2L*1024*1024*1024)){
            trace.record("encoded_h264",original,new JSONObject().put("frameId",7));
            List<File> parts=trace.pending();assertEquals(1,parts.size());File meta=parts.get(0);
            JSONObject manifest=trace.metadata(meta);assertEquals(DetailedTrace.sha256(trace.data(meta)),manifest.getString("sha256"));
            ByteArrayOutputStream recovered=new ByteArrayOutputStream();int records=0;
            try(DataInputStream in=new DataInputStream(new FileInputStream(trace.data(meta)))){
                while(in.available()>0){assertEquals(0x4d544334,in.readInt());int h=in.readInt(),n=in.readInt();byte[] header=new byte[h];in.readFully(header);JSONObject record=new JSONObject(new String(header,StandardCharsets.UTF_8));assertEquals(recovered.size(),record.getInt("offset"));assertEquals(DetailedTrace.sha256(original),record.getString("sha256"));byte[] payload=new byte[n];in.readFully(payload);recovered.write(payload);records++;}
            }
            assertTrue(records>1);assertArrayEquals(original,recovered.toByteArray());trace.acknowledge(meta);assertTrue(trace.pending().isEmpty());assertTrue(trace.data(meta).isFile());assertNull(error.get());
        }
    }
    @Test public void recoversRenamedPartBeforeManifestCommitWithOriginalRunIdentity()throws Exception{
        File folder=temporary.newFolder();File[] data=new File[1];String[] run=new String[1];
        try(DetailedTrace trace=new DetailedTrace(context(folder),x->fail(x),()->Long.MAX_VALUE)){
            trace.record("control_rx",new byte[]{1,2,3},new JSONObject());File meta=trace.pending().get(0);run[0]=trace.metadata(meta).getString("runId");data[0]=trace.data(meta);
            // Simulate power loss after rename but before atomic metadata commit.
            assertTrue(meta.delete());
        }
        try(DetailedTrace trace=new DetailedTrace(context(folder),x->fail(x),()->Long.MAX_VALUE)){
            File meta=trace.pending().get(0);assertEquals(run[0],trace.metadata(meta).getString("runId"));assertTrue(trace.metadata(meta).getBoolean("interrupted"));assertArrayEquals(DetailedTrace.read(data[0]),DetailedTrace.read(trace.data(meta)));
        }
    }
    @Test public void lowStorageStopsInsteadOfDroppingAcceptedTraceSilently()throws Exception{
        AtomicReference<String> failure=new AtomicReference<>();try(DetailedTrace trace=new DetailedTrace(context(temporary.newFolder()),failure::set,()->DetailedTrace.MIN_FREE_BYTES-1)){
            trace.record("media_tx",new byte[]{3},new JSONObject());trace.checkpoint();assertEquals("trace_storage_low",failure.get());assertFalse(trace.healthy());assertTrue(trace.pending().isEmpty());
        }
    }
}
