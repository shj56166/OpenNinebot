package com.motexa.openninebot;

import java.io.File;
import android.content.Context;
import android.content.ContextWrapper;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import org.json.JSONObject;
import org.junit.Test;
import org.junit.Rule;
import org.junit.rules.TemporaryFolder;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class) @Config(sdk=24)
public class SessionLogCountTest {
    @Rule public TemporaryFolder temporary=new TemporaryFolder();
    @Test public void countIncludesAllSavedRecordsOnceAndDetailsStayInFiles()throws Exception {
        File isolated=temporary.newFolder();
        org.robolectric.shadows.ShadowStatFs.registerStats(isolated.getAbsolutePath(),1_000_000,1_000_000,1_000_000);
        Context context=new ContextWrapper(RuntimeEnvironment.getApplication()){
            public Context getApplicationContext(){return this;}
            public File getFilesDir(){return isolated;}
        };
        try(SessionLog log=new SessionLog(context)){
            long before=log.recordCount();
            assertTrue("journal must open and persist run_started: "+org.robolectric.shadows.ShadowLog.getLogsForTag("OpenNinebot"),before>0);
            for(int i=0;i<350;i++)log.diagnostic("fixture_event","index",i);
            log.log("fixture detail");
            log.metric(new JSONObject().put("fixtureMetric",1));
            log.stream(new JSONObject().put("sent",true).put("auBytes",15));
            log.flush();
            // Every media call now persists a frame record as well as its summary.
            assertEquals(before+354,log.recordCount());
            long rows=0;
            for(String name:new String[]{"events.ndjson","metrics.ndjson","stream.ndjson"})
                rows+=Files.readAllLines(new File(log.getSessionDirectory(),name).toPath(),StandardCharsets.UTF_8).size();
            assertEquals(rows,log.recordCount());
            String detail=new String(Files.readAllBytes(new File(log.getSessionDirectory(),"events.ndjson").toPath()),StandardCharsets.UTF_8);
            assertTrue(detail.contains("fixture detail"));
            assertTrue(detail.contains("\"index\":349"));
        }
    }
}
