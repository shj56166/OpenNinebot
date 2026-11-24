package org.openninebot.reference.vehicle.protocol;

import java.util.*;

/** Host-neutral semantic operation outcome. Never contains wire payloads. */
public final class ProtocolOperationResult {
    public final String requestId,operationId,status,errorCode;
    public final Map<String,ProtocolValue> values;
    public final List<Map<String,ProtocolValue>> records;
    public ProtocolOperationResult(String requestId,String operationId,String status,String errorCode,Map<String,ProtocolValue> values,List<Map<String,ProtocolValue>> records) {
        this.requestId=requestId;this.operationId=operationId;this.status=status;this.errorCode=errorCode==null?"":errorCode;
        this.values=Collections.unmodifiableMap(new LinkedHashMap<>(values==null?Collections.emptyMap():values));
        List<Map<String,ProtocolValue>> copy=new ArrayList<>();
        if(records!=null)for(Map<String,ProtocolValue> record:records)copy.add(Collections.unmodifiableMap(new LinkedHashMap<>(record)));
        this.records=Collections.unmodifiableList(copy);
    }
}
