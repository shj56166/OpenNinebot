"""Execute original APK connection identity/config methods with Android value stubs."""
from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import subprocess

ROOT = Path(__file__).resolve().parent
BUILD = ROOT / "build"

SOURCES = {
    "o10.java": "public abstract class o10 {}",
    "fe.java": "public class fe { public static void s(String text) { throw new IllegalArgumentException(text); } }",
    "a02.java": "public enum a02 { S, T }",
    "b02.java": "public enum b02 { S, T, U }",
    "c02.java": "public enum c02 { S, T }",
    "android/net/wifi/p2p/WifiP2pDevice.java": """
package android.net.wifi.p2p;
public class WifiP2pDevice {
  public String deviceName, deviceAddress;
  public WifiP2pDevice(String name, String mac) { deviceName=name; deviceAddress=mac; }
}
""",
    "android/net/wifi/p2p/WifiP2pGroup.java": """
package android.net.wifi.p2p;
public class WifiP2pGroup {
  public WifiP2pDevice owner;
  public boolean localOwner;
  public String name;
  public WifiP2pGroup(WifiP2pDevice peer, boolean local, String network) {
    owner=peer; localOwner=local; name=network;
  }
  public WifiP2pDevice getOwner() { return owner; }
  public boolean isGroupOwner() { return localOwner; }
  public String getNetworkName() { return name; }
  public String getInterface() { return "p2p-test"; }
}
""",
    "ConnectionValidation.java": """
import java.lang.reflect.Field;
import android.net.wifi.p2p.WifiP2pDevice;
import android.net.wifi.p2p.WifiP2pGroup;
import sun.misc.Unsafe;
public class ConnectionValidation {
  static int checks=0;
  static void check(boolean result, String name) {
    checks++; if (!result) throw new AssertionError(name);
  }
  static void rejects(Runnable action, String name) {
    boolean failed=false;
    try { action.run(); } catch (IllegalArgumentException e) { failed=true; }
    check(failed,name);
  }
  public static void main(String[] args) throws Exception {
    x32 target=new x32("  DASH  ", " AA:BB:CC:DD:EE:FF ");
    check(target.S.equals("DASH"),"constructor trims label");
    check(target.T.equals("aa:bb:cc:dd:ee:ff"),"constructor trims/lowercases MAC");
    check(target.z("DASH","AA:BB:CC:DD:EE:FF"),"MAC case ignored");
    check(!target.z("dash","aa:bb:cc:dd:ee:ff"),"label case exact");
    check(!target.z("DASH suffix","aa:bb:cc:dd:ee:ff"),"label not prefix");
    check(!target.z("DASH","aa:bb:cc:dd:ee:00"),"MAC not suffix match");
    check(!target.z(null,null),"null scan target rejected");
    rejects(()->new x32(" ","aa:bb:cc:dd:ee:ff"),"empty target");
    rejects(()->new x32("DASH","aa:bb:cc"),"short MAC");
    Field field=Unsafe.class.getDeclaredField("theUnsafe"); field.setAccessible(true);
    Unsafe unsafe=(Unsafe)field.get(null);
    hd1 connection=(hd1)unsafe.allocateInstance(hd1.class);
    connection.f=target;
    check(connection.d(new WifiP2pGroup(new WifiP2pDevice("DASH","AA:BB:CC:DD:EE:FF"),false,"DIRECT-any")),"owner label match");
    check(connection.d(new WifiP2pGroup(new WifiP2pDevice("other","aa:bb:cc:dd:ee:ff"),false,"DASH")),"network label fallback");
    check(!connection.d(new WifiP2pGroup(new WifiP2pDevice("DASH","aa:bb:cc:dd:ee:00"),false,"DASH")),"group MAC mismatch");
    check(!connection.d(new WifiP2pGroup(new WifiP2pDevice("other","aa:bb:cc:dd:ee:ff"),false,"other")),"both group labels mismatch");
    check(!connection.d(new WifiP2pGroup(new WifiP2pDevice("DASH","aa:bb:cc:dd:ee:ff"),true,"DASH")),"local owner rejected");
    check(!connection.d(new WifiP2pGroup(null,false,"DASH")),"missing owner rejected");
    connection.h=17;
    check(connection.a(17)&&!connection.a(16),"connection generation comparison");
    d02 five=d02.C(); d02 small=d02.E();
    check(five.S==848&&five.T==480&&five.U==20&&five.V==2000000,"five preset");
    check(small.S==480&&small.T==272&&small.U==10&&small.Z==80&&small.Y==a02.T,"JPEG preset");
    check(d02.B("848,480,20,2000000,0,0").a0==c02.S,"legacy six fields defaults direct H264");
    check(d02.B("480,272,10,1500000,0,0,1,80").Y==a02.T,"eight field JPEG config");
    d02 seven=d02.B("1024,600,25,2000000,0,0,0,80,1");
    check(seven.a0==c02.T&&seven.Y==a02.S,"nine fields selects KCP H264");
    rejects(()->d02.B("1,2,3"),"invalid config count");
    rejects(()->new d02(849,480,20,2000000,0,b02.S,a02.S,80,c02.S),"odd dimensions");
    rejects(()->new d02(1920,1920,20,2000000,0,b02.S,a02.S,80,c02.S),"total pixel budget");
    rejects(()->new d02(848,480,4,2000000,0,b02.S,a02.S,80,c02.S),"FPS lower bound");
    rejects(()->new d02(848,480,31,2000000,0,b02.S,a02.S,80,c02.S),"FPS upper bound");
    rejects(()->new d02(848,480,20,249999,0,b02.S,a02.S,80,c02.S),"bitrate lower bound");
    rejects(()->new d02(848,480,20,8000001,0,b02.S,a02.S,80,c02.S),"bitrate upper bound");
    rejects(()->new d02(848,480,20,2000000,45,b02.S,a02.S,80,c02.S),"rotation multiple");
    rejects(()->new d02(848,480,20,2000000,360,b02.S,a02.S,80,c02.S),"rotation range");
    rejects(()->new d02(848,480,20,2000000,0,b02.S,a02.S,29,c02.S),"JPEG quality lower bound");
    rejects(()->new d02(848,480,20,2000000,0,b02.S,a02.S,96,c02.S),"JPEG quality upper bound");
    d02 mixed=new d02(480,272,10,1500000,0,b02.S,a02.T,80,c02.T);
    check(mixed.F()==c02.T,"config retains KCP even for JPEG; sender separately overrides transport");
    check(five.z()==30&&seven.z()==31,"AVC level selection");
    System.out.println("{\\"passed\\":"+checks+",\\"failed\\":0,\\"origin\\":\\"APK converted JVM methods\\"}");
  }
}
""",
}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--jar", type=Path, default=ROOT.parent / "raw/ninebot-pro-061-dex2jar.jar")
    parser.add_argument("--jbr", type=Path, default=Path("C:/Program Files/Android/Android Studio/jbr/bin"))
    parser.add_argument("--android", type=Path, default=Path(os.environ["LOCALAPPDATA"]) / "Android/Sdk/platforms/android-36/android.jar")
    args = parser.parse_args()
    BUILD.mkdir(parents=True, exist_ok=True)
    paths=[]
    for name, text in SOURCES.items():
        path=BUILD/name
        path.parent.mkdir(parents=True,exist_ok=True)
        path.write_text(text,encoding="utf-8")
        paths.append(str(path))
    cp=os.pathsep.join(map(str,(BUILD,args.jar,args.android)))
    compile_result=subprocess.run([str(args.jbr/"javac.exe"),"-J-Dfile.encoding=UTF-8","-J-Duser.language=en","-encoding","UTF-8","-cp",cp,"-d",str(BUILD),*paths],capture_output=True,text=True,encoding="utf-8",errors="replace")
    (ROOT/"compile.log").write_text(compile_result.stdout+compile_result.stderr,encoding="utf-8")
    compile_result.check_returncode()
    result=subprocess.run([str(args.jbr/"java.exe"),"-Xverify:none","-Dfile.encoding=UTF-8","-Duser.language=en","-cp",cp,"ConnectionValidation"],capture_output=True,text=True,encoding="utf-8",errors="replace")
    if result.returncode:
        raise RuntimeError(result.stdout+result.stderr)
    data=json.loads(result.stdout)
    data["scope"]="Original x32 constructor/matcher; hd1.d and hd1.a; d02 constructors, parser, preset and stored transport. Android group/device value stubs; o10, error helper fe and a02/b02/c02 enum values stubbed; hd1 constructor bypassed. No Android network operations executed."
    data["conversion_limit"]="dex2jar c02 initializer attempts to instantiate java.lang.Enum; only enum value holders replaced. JVM verification disabled because converted control classes lack stack maps. These tests cover isolated method behavior, not original Android execution."
    (ROOT/"validation.json").write_text(json.dumps(data,ensure_ascii=False,indent=2)+"\n",encoding="utf-8")
    print(result.stdout,end="")


if __name__=="__main__":
    main()
