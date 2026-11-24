package com.motexa.openninebot;

import android.app.*;
import android.content.*;
import android.media.projection.MediaProjectionManager;
import android.os.Bundle;
import android.view.*;
import android.widget.*;
import org.json.JSONObject;

/** One Ninebot workflow with independent updates, diagnostics and system consent. */
public final class LabActivity extends Activity {
    static final String DISCLAIMER="本项目为非官方独立投屏工具，与九号、Segway-Ninebot 及其关联公司无隶属、合作或背书关系。相关商标归原权利人所有。软件按现状提供，不保证所有车型和固件兼容；请仅用于自有或已获授权的车辆，并在安全环境中使用。";
    private LabRuntime runtime;
    private NinebotTestPanel panel;
    private LabTarget target;
    private TextView updateStatus,logStatus;
    private Button download,upload;
    private ProgressBar progress;
    private CastInstrumentView instrument;
    private JSONObject requestedConsent;
    @Override public void onCreate(Bundle state){
        super.onCreate(state);runtime=LabRuntime.get(this);target=LabTarget.fromId(getPreferences(0).getString("target",""));
        if(state!=null)try{String saved=state.getString("projectionConsent");if(saved!=null)requestedConsent=new JSONObject(saved);}catch(Exception ignored){}
        CastUi ui=new CastUi(this);LinearLayout root=ui.column();int padding=(int)(20*getResources().getDisplayMetrics().density);root.setPadding(padding,padding,padding,padding);
        ScrollView scroll=new ScrollView(this);scroll.addView(root);setContentView(scroll);
        getWindow().getDecorView().setOnApplyWindowInsetsListener((v,insets)->{v.setPadding(insets.getSystemWindowInsetLeft(),insets.getSystemWindowInsetTop(),insets.getSystemWindowInsetRight(),insets.getSystemWindowInsetBottom());return insets;});
        instrument=new CastInstrumentView(this,visible->runtime.castVisuals.visible=visible);
        ui.castHeader(root,"OpenNinebot",instrument);root.addView(ui.hint("非官方九号仪表投屏工具 · "+BuildConfig.VERSION_NAME));
        root.addView(ui.button("项目说明与免责声明",false,()->new AlertDialog.Builder(this).setTitle("关于 OpenNinebot").setMessage(DISCLAIMER+"\n\n自有代码：MIT\n项目：github.com/shj56166/OpenNinebot\n日志仅在主动上传时发送至 Motexa 诊断服务器。").setPositiveButton("关闭",null).show()));
        root.addView(ui.button("检查 GitHub 更新",false,runtime::checkUpdate));
        updateStatus=ui.hint("");root.addView(updateStatus);download=ui.button("下载更新",true,()->LabUpdateInstaller.download(this,runtime));root.addView(download);
        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);progress.setMax(1000);root.addView(progress);
        LabTarget[] targets=LabTarget.values();String[] labels=new String[targets.length];for(int i=0;i<labels.length;i++)labels[i]=targets[i].label;
        Spinner select=ui.spinner(labels);select.setSelection(target.ordinal());root.addView(select);
        panel=new NinebotTestPanel(this,runtime.ninebot,target);root.addView(panel.view());
        select.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){public void onNothingSelected(AdapterView<?> parent){}public void onItemSelected(AdapterView<?> p,View v,int position,long id){if(target!=targets[position]){panel.cancelPendingActions();runtime.stopAll();target=targets[position];getPreferences(0).edit().putString("target",target.id).apply();panel.setTarget(target);requestedConsent=null;render();}}});
        LinearLayout logs=ui.section(root,"诊断日志");logStatus=ui.hint("");logs.addView(logStatus);
        upload=ui.button("上传日志",false,()->new AlertDialog.Builder(this).setTitle("上传诊断日志").setMessage("日志将发送至 Motexa 诊断服务器。完整媒体轨迹可能包含本次镜像的屏幕内容；车辆凭据不随日志上传。请确认本次内容可以提交用于排查。").setNegativeButton("取消",null).setPositiveButton("上传",(dialog,which)->runtime.upload()).show());logs.addView(upload);
        root.addView(ui.button("断开并退出",false,()->{runtime.stopAll();finish();}));render();
    }
    @Override protected void onSaveInstanceState(Bundle state){super.onSaveInstanceState(state);if(requestedConsent!=null)state.putString("projectionConsent",requestedConsent.toString());}
    @Override protected void onResume(){super.onResume();runtime.listener=this::render;runtime.castVisuals.listener=this::render;instrument.foreground(true);render();}
    @Override protected void onPause(){runtime.listener=null;runtime.castVisuals.listener=null;instrument.foreground(false);super.onPause();}
    private void render(){
        if(panel==null)return;panel.render();JSONObject release=runtime.updates.available();download.setVisibility(release==null?View.GONE:View.VISIBLE);download.setEnabled(!runtime.updating);
        if(release!=null)download.setText("下载更新 · "+release.optString("versionName"));
        String policy=runtime.updates.mandatory()?"更新后可发起新的连接或投屏；当前会话与日志上传可继续。\n":"";
        updateStatus.setText(policy+runtime.updateMessage+(runtime.message.isEmpty()?"":"\n"+runtime.message));
        progress.setVisibility(runtime.updating?View.VISIBLE:View.GONE);progress.setProgress(runtime.updateTotalBytes>0?(int)Math.min(1000,runtime.updateReceivedBytes*1000/runtime.updateTotalBytes):0);
        long wait=runtime.uploadCooldownSeconds();upload.setEnabled(!runtime.uploading&&wait==0);upload.setText(runtime.uploading?"正在上传…":wait>0?"稍后重试 · "+wait+" 秒":"上传日志");
        logStatus.setText("已记录 "+runtime.log.displayedRecordCount()+" 条\n"+runtime.uploadMessage);
        CastVisualFeed.Snapshot colors=runtime.castVisuals.snapshot();instrument.setVisibility(target.casting?View.VISIBLE:View.GONE);
        instrument.update(runtime.castVisuals.state(target,runtime.ninebot.snapshot()),android.os.SystemClock.elapsedRealtime()-colors.sampledAt<2500?colors.palette:CastPalette.EMPTY);
        if(requestedConsent!=null||runtime.pendingFrameMode==null||runtime.frameSourceOwner!=null)return;
        JSONObject config=runtime.pendingFrameConfig;NinebotTestController.FrameMode mode=runtime.pendingFrameMode;
        runtime.pendingFrameConfig=null;runtime.pendingFrameMode=null;if(config==null||!runtime.ninebot.acceptsFrameSource(config.optLong("generation",-1)))return;
        if(mode==NinebotTestController.FrameMode.TEST_PATTERN){NinebotFrameService.start(this,null,0,config);return;}
        requestedConsent=config;startActivityForResult(getSystemService(MediaProjectionManager.class).createScreenCaptureIntent(),7243);
    }
    @Override public void onRequestPermissionsResult(int request,String[] permissions,int[] results){super.onRequestPermissionsResult(request,permissions,results);panel.onRequestPermissionsResult(request,permissions,results);}
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request==7243){JSONObject config=requestedConsent;requestedConsent=null;if(config!=null&&runtime.ninebot.acceptsFrameSource(config.optLong("generation",-1))){if(result==RESULT_OK&&data!=null)NinebotFrameService.start(this,data,result,config);else runtime.ninebot.onFrameSourceFailure(config.optLong("generation",-1),"projection_consent_denied");}}}
}
