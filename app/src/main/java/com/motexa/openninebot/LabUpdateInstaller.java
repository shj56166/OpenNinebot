package com.motexa.openninebot;

import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import android.net.Uri;
import androidx.core.content.FileProvider;
import java.io.File;
import java.security.MessageDigest;
import org.json.JSONObject;

final class LabUpdateInstaller {
    static void download(Activity activity,LabRuntime r){
        JSONObject update=r.nativeSnapshot().optJSONObject("update");if(update==null||r.updating)return;r.updating=true;r.updateReceivedBytes=0;r.updateTotalBytes=update.optLong("bytes");
        r.updateMessage="正在下载更新…";r.notifyUi();android.widget.Toast.makeText(activity,"正在下载更新…",android.widget.Toast.LENGTH_SHORT).show();
        r.io.execute(()->{String stage="download";try{
            r.log.log("update_download_started version="+update.optInt("versionCode"));
            File apk=r.client.download(update,(received,total)->{r.updateReceivedBytes=received;r.updateTotalBytes=total;r.updateMessage="正在下载 · "+(total==0?0:received*100/total)+"% · "+String.format(java.util.Locale.ROOT,"%.1f / %.1f MB",received/1048576d,total/1048576d);r.notifyUi();});
            stage="hash";r.log.log("update_downloaded bytes="+apk.length());
            r.updateMessage="下载完成，正在校验…";r.notifyUi();
            if(apk.length()!=update.getLong("bytes")||!GitHubUpdates.sha256(apk).equals(update.getString("sha256")))throw new SecurityException("apk_hash_mismatch");
            stage="package_certificate";
            PackageManager pm=activity.getPackageManager();int flags=Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES;
            PackageInfo candidate=pm.getPackageArchiveInfo(apk.getAbsolutePath(),flags),installed=pm.getPackageInfo(activity.getPackageName(),flags);
            long version=candidate==null?0:Build.VERSION.SDK_INT>=28?candidate.getLongVersionCode():candidate.versionCode;
            if(candidate==null||!activity.getPackageName().equals(candidate.packageName)||version!=update.getLong("versionCode")||version<=BuildConfig.VERSION_CODE)throw new SecurityException();
            String cert=certificate(candidate),current=certificate(installed);
            if(!cert.equals(current)||!cert.equals(update.getString("certificateSha256")))throw new SecurityException();
            r.log.log("update_verified version="+version);
            r.main.post(()->{if(activity.isFinishing()||activity.isDestroyed()){
                r.updateMessage="下载已完成，请点下载更新继续安装";r.notifyUi();return;
            }
                if(Build.VERSION.SDK_INT>=26&&!pm.canRequestPackageInstalls()){
                    activity.startActivity(new Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+activity.getPackageName())));r.updateMessage="请允许安装后，再点下载更新";r.notifyUi();return;
                }
                Uri uri=FileProvider.getUriForFile(activity,activity.getPackageName()+".files",apk);
                Intent intent=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,"application/vnd.android.package-archive").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
                activity.startActivity(intent);r.updateMessage="更新已校验，请在系统界面确认安装";r.notifyUi();
            });
        }catch(Exception e){if(!"download".equals(stage))r.client.resetDownload();r.updateMessage="download".equals(stage)?"下载失败，已保留进度，可重试":"更新校验失败，请重新下载";r.log.log("update_failed stage="+stage+" type="+e.getClass().getSimpleName());r.main.post(()->{r.notifyUi();android.widget.Toast.makeText(activity,r.updateMessage,android.widget.Toast.LENGTH_LONG).show();});}finally{r.main.post(()->{r.updating=false;r.notifyUi();});}});
    }
    private static String certificate(PackageInfo info)throws Exception {
        Signature[] signatures=Build.VERSION.SDK_INT>=28?info.signingInfo==null?null:info.signingInfo.getApkContentsSigners():info.signatures;
        if(signatures==null||signatures.length!=1)throw new SecurityException();byte[] hash=MessageDigest.getInstance("SHA-256").digest(signatures[0].toByteArray());StringBuilder s=new StringBuilder();for(byte b:hash)s.append(String.format(java.util.Locale.ROOT,"%02x",b&255));return s.toString();
    }
}
