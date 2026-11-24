package com.motexa.openninebot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.*;

/** Explicit, policy-gated owner of a direct vehicle BLE test. */
public final class LabConnectionService extends Service {
    static final String ACTION_START="com.motexa.openninebot.action.START";
    static final String ACTION_STOP="com.motexa.openninebot.action.STOP";
    static final String ACTION_DUMP="com.motexa.openninebot.action.DUMP";
    static final int MEDIA_DATA_PORT=10920,MEDIA_CONTROL_PORT=10921;
    private long ownedGeneration=-1;
    static void startBle(Context c){start(c,ACTION_START);}
    static void startForNinebot(Context c){start(c,"ninebot");}
    private static void start(Context c,String action){LabRuntime runtime=LabRuntime.get(c);runtime.requireStart();Intent i=new Intent(c,LabConnectionService.class).setAction(action).putExtra("serviceGeneration",++runtime.bleServiceGeneration);if(Build.VERSION.SDK_INT>=26)c.startForegroundService(i);else c.startService(i);}
    static Notification notification(Context c,String message){
        String channel="openninebot";NotificationManager nm=c.getSystemService(NotificationManager.class);
        if(Build.VERSION.SDK_INT>=26)nm.createNotificationChannel(new NotificationChannel(channel,"OpenNinebot",NotificationManager.IMPORTANCE_LOW));
        Notification.Builder b=Build.VERSION.SDK_INT>=26?new Notification.Builder(c,channel):new Notification.Builder(c);
        PendingIntent open=PendingIntent.getActivity(c,0,new Intent(c,LabActivity.class),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
        return b.setContentTitle("OpenNinebot").setContentText(message).setSmallIcon(android.R.drawable.stat_sys_upload).setContentIntent(open).setOngoing(true).build();
    }
    public int onStartCommand(Intent i,int flags,int id){
        LabRuntime r=LabRuntime.get(this);
        if(i==null||!r.allowed()||(!ACTION_START.equals(i.getAction())&&!"ninebot".equals(i.getAction()))){stopSelf();return START_NOT_STICKY;}
        long generation=i.getLongExtra("serviceGeneration",-1);if(generation!=r.bleServiceGeneration)return START_NOT_STICKY;ownedGeneration=generation;
        try{Notification n=notification(this,"九号蓝牙连接服务运行中");if(Build.VERSION.SDK_INT>=29)startForeground(29041,n,ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);else startForeground(29041,n);}
        catch(RuntimeException e){r.message="蓝牙启动失败，请检查权限和车辆资料";r.log.log("ble_start_failed");r.stopBle();stopSelf();r.notifyUi();}
        return START_NOT_STICKY;
    }
    public void onTaskRemoved(Intent i){LabRuntime.get(this).stopAll();stopSelf();super.onTaskRemoved(i);}
    public void onDestroy(){LabRuntime r=LabRuntime.get(this);if(ownedGeneration==r.bleServiceGeneration){r.ninebot.disconnect();}r.log.diagnostic("ble_service_released","serviceGeneration",ownedGeneration,"currentGeneration",r.bleServiceGeneration);super.onDestroy();}
    public IBinder onBind(Intent i){return null;}
}
