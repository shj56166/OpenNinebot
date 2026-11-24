"""Generate explicitly labelled Android/config/packet test environment stubs."""
from pathlib import Path

HERE = Path(__file__).resolve().parent
STUBS = HERE / "stubs"


def put(name, source):
    target = STUBS / (name.replace(".", "/") + ".java")
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(source, encoding="utf-8")


put("android.util.Log", "package android.util; public final class Log { public static int d(String a,String b){return 0;} public static String getStackTraceString(Throwable t){return t.toString();} }")
put("cn.ninebot.library.nbbluetooth.NbBluetoothDevice", "package cn.ninebot.library.nbbluetooth; public class NbBluetoothDevice {}")
put("cn.ninebot.library.bluetooth.dynamic.config.DynamicDeviceConfig", """
package cn.ninebot.library.bluetooth.dynamic.config;
public final class DynamicDeviceConfig {
 private final Integer protocol, encrypt;
 public DynamicDeviceConfig(Integer p,Integer e){protocol=p;encrypt=e;}
 public Integer getProtocol(){return protocol;} public Integer getEncrypt(){return encrypt;}
}
""")
put("cn.ninebot.library.nbbluetooth.command.NbFrame", """
package cn.ninebot.library.nbbluetooth.command;
public final class NbFrame {
 private final byte cmd,index; private final byte[] data;
 public NbFrame(byte c,byte i,byte[] d){cmd=c;index=i;data=d;}
 public byte getCmd(){return cmd;} public byte getIndex(){return index;} public byte[] getData(){return data;}
}
""")
put("cn.ninebot.library.nbbluetooth.Utils", """
package cn.ninebot.library.nbbluetooth;
public final class Utils { public static String toHex(byte[] b,CharSequence sep){
 var out=new StringBuilder();for(int i=0;i<b.length;i++){if(i>0)out.append(sep);out.append(String.format("%02x",b[i]&255));}return out.toString();
} }
""")
package = "cn.ninebot.library.nbbluetooth.protocol"
put(package + ".BleProtocol", "package " + package + "; public class BleProtocol {}")
for name in ("BleProtocol1", "BleEncryption2Protocol1", "BleEncryption2Protocol2", "BleEncryption3Protocol2"):
    put(package + "." + name, "package " + package + "; public class " + name + " extends BleProtocol {}")
put(package + ".BleProtocol2", "package " + package + "; public class BleProtocol2 extends BleProtocol { private boolean encrypt=true; public void setEncrypt(boolean v){encrypt=v;} public boolean isEncrypt(){return encrypt;} }")
print("validation environment stubs generated")
