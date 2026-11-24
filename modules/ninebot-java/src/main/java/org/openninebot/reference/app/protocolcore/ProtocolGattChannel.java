package org.openninebot.reference.app.protocolcore;

import java.util.UUID;

/** One safe write/notify path identified from one physical GATT topology. */
public final class ProtocolGattChannel {
    public enum NotificationMode { CCCD, LOCAL_ONLY }

    public final String id;
    public final int serviceIndex;
    public final int writeCharacteristicIndex;
    public final int notifyCharacteristicIndex;
    public final UUID serviceUuid;
    public final UUID writeCharacteristicUuid;
    public final UUID notifyCharacteristicUuid;
    public final int writeType;
    public final NotificationMode notificationMode;
    public final String reason;

    private ProtocolGattChannel(
        String id,
        int serviceIndex,
        int writeCharacteristicIndex,
        int notifyCharacteristicIndex,
        UUID serviceUuid,
        UUID writeCharacteristicUuid,
        UUID notifyCharacteristicUuid,
        int writeType,
        NotificationMode notificationMode,
        String reason
    ) {
        this.id = id == null ? "protocol-channel" : id;
        this.serviceIndex = serviceIndex;
        this.writeCharacteristicIndex = writeCharacteristicIndex;
        this.notifyCharacteristicIndex = notifyCharacteristicIndex;
        this.serviceUuid = serviceUuid;
        this.writeCharacteristicUuid = writeCharacteristicUuid;
        this.notifyCharacteristicUuid = notifyCharacteristicUuid;
        this.writeType = writeType;
        this.notificationMode = notificationMode;
        this.reason = reason == null ? "" : reason;
    }

    public static ProtocolGattChannel fromTopology(
        String id,
        ProtocolGattChannelResolver.Service service,
        ProtocolGattChannelResolver.Characteristic write,
        ProtocolGattChannelResolver.Characteristic notify,
        int writeType,
        String reason
    ) {
        return fromTopology(id, service, write, notify, writeType, NotificationMode.CCCD, reason);
    }

    public static ProtocolGattChannel fromTopology(
        String id,
        ProtocolGattChannelResolver.Service service,
        ProtocolGattChannelResolver.Characteristic write,
        ProtocolGattChannelResolver.Characteristic notify,
        int writeType,
        NotificationMode notificationMode,
        String reason
    ) {
        if (
            service == null ||
            notify == null ||
            notificationMode == null ||
            (writeType != ProtocolProfile.WRITE_TYPE_WITH_RESPONSE &&
                writeType != ProtocolProfile.WRITE_TYPE_WITHOUT_RESPONSE)
        ) {
            return null;
        }
        return new ProtocolGattChannel(
            id,
            service.index,
            write == null ? -1 : write.index,
            notify.index,
            service.uuid,
            write == null ? null : write.uuid,
            notify.uuid,
            writeType,
            notificationMode,
            reason
        );
    }
}
