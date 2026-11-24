package com.motexa.openninebot;
import org.json.*;
import java.util.*;

/** Imported credentials and provider secrets never enter diagnostic archives. */
final class DiagnosticPrivacy {
    private static final Set<String> SECRETS=new HashSet<>(Arrays.asList("password","passwordHex","token","tokenHex","keyHex","appEnc","appEncHex","curAppEncHex","nextAppEncHex","credentials","authorization","Authorization","accessToken","refreshToken","secret","headers"));
    static String text(String input){return input==null?"":input.replaceAll("(?i)(bearer\\s+)[A-Za-z0-9._~+/-]+=*","$1[redacted]");}
    static Object value(Object input)throws Exception{
        if(input instanceof JSONObject){JSONObject source=(JSONObject)input,result=new JSONObject();Iterator<String> keys=source.keys();while(keys.hasNext()){String key=keys.next();result.put(key,SECRETS.contains(key)?"[redacted]":value(source.get(key)));}return result;}
        if(input instanceof JSONArray){JSONArray result=new JSONArray();for(int i=0;i<((JSONArray)input).length();i++)result.put(value(((JSONArray)input).get(i)));return result;}
        return input instanceof String?text((String)input):input;
    }
    static JSONObject object(JSONObject input){try{return input==null?new JSONObject():(JSONObject)value(input);}catch(Exception ignored){return new JSONObject();}}
}
