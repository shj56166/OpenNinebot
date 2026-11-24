package org.openninebot.reference.app.protocolcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/** Brand-neutral helpers for protocol-owned GATT channel resolvers. */
public final class ProtocolGattChannelResolvers {
    public static final int PROPERTY_WRITE = 0x08;
    public static final int PROPERTY_WRITE_NO_RESPONSE = 0x04;
    public static final int PROPERTY_NOTIFY = 0x10;
    public static final int PROPERTY_INDICATE = 0x20;

    private ProtocolGattChannelResolvers() {}

    public static List<ProtocolGattChannel> resolveUniqueNamedPair(
        List<ProtocolGattChannelResolver.Service> services,
        UUID serviceUuid,
        UUID writeUuid,
        int writeProperties,
        UUID notifyUuid,
        int notifyProperties,
        String idPrefix,
        String firstCandidateId,
        int writeType,
        String reason
    ) {
        if (services == null || services.isEmpty()) {
            return Collections.emptyList();
        }
        List<ProtocolGattChannel> candidates = new ArrayList<>();
        for (ProtocolGattChannelResolver.Service service : services) {
            if (service == null || !service.primary || (serviceUuid != null && !serviceUuid.equals(service.uuid))) {
                continue;
            }
            ProtocolGattChannelResolver.Characteristic write = findExactlyOne(service, writeUuid, writeProperties);
            ProtocolGattChannelResolver.Characteristic notify = findExactlyOne(service, notifyUuid, notifyProperties);
            if (write == null || notify == null) {
                continue;
            }
            candidates.add(ProtocolGattChannel.fromTopology(
                candidates.isEmpty() && firstCandidateId != null
                    ? firstCandidateId
                    : idPrefix + "-" + candidates.size(),
                service,
                write,
                notify,
                writeType,
                reason
            ));
        }
        return candidates;
    }

    public static List<ProtocolGattChannel> onlyOne(List<ProtocolGattChannel> candidates) {
        return candidates != null && candidates.size() == 1 ? candidates : Collections.emptyList();
    }

    public static ProtocolGattChannelResolver.Characteristic findExactlyOne(
        ProtocolGattChannelResolver.Service service,
        UUID targetUuid,
        int requiredProperties
    ) {
        if (service == null || targetUuid == null) {
            return null;
        }
        ProtocolGattChannelResolver.Characteristic result = null;
        for (ProtocolGattChannelResolver.Characteristic characteristic : service.characteristics) {
            if (
                characteristic == null ||
                !targetUuid.equals(characteristic.uuid) ||
                !hasProperties(characteristic, requiredProperties)
            ) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = characteristic;
        }
        return result;
    }

    public static ProtocolGattChannelResolver.Characteristic findExactlyOneWithAllProperties(
        ProtocolGattChannelResolver.Service service,
        UUID targetUuid,
        int requiredProperties
    ) {
        if (service == null || targetUuid == null) {
            return null;
        }
        ProtocolGattChannelResolver.Characteristic result = null;
        for (ProtocolGattChannelResolver.Characteristic characteristic : service.characteristics) {
            if (
                characteristic == null ||
                !targetUuid.equals(characteristic.uuid) ||
                !hasAllProperties(characteristic, requiredProperties)
            ) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = characteristic;
        }
        return result;
    }

    public static ProtocolGattChannelResolver.Characteristic findExactlyOneByUuid(
        ProtocolGattChannelResolver.Service service,
        UUID targetUuid
    ) {
        if (service == null || targetUuid == null) {
            return null;
        }
        ProtocolGattChannelResolver.Characteristic result = null;
        for (ProtocolGattChannelResolver.Characteristic characteristic : service.characteristics) {
            if (characteristic == null || !targetUuid.equals(characteristic.uuid)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = characteristic;
        }
        return result;
    }

    public static ProtocolGattChannelResolver.Characteristic findExactlyOneAny(
        ProtocolGattChannelResolver.Service service,
        int requiredProperties
    ) {
        if (service == null) {
            return null;
        }
        ProtocolGattChannelResolver.Characteristic result = null;
        for (ProtocolGattChannelResolver.Characteristic characteristic : service.characteristics) {
            if (characteristic == null || !hasProperties(characteristic, requiredProperties)) {
                continue;
            }
            if (result != null) {
                return null;
            }
            result = characteristic;
        }
        return result;
    }

    public static boolean hasProperties(
        ProtocolGattChannelResolver.Characteristic characteristic,
        int requiredProperties
    ) {
        return characteristic != null && (characteristic.properties & requiredProperties) != 0;
    }

    public static boolean hasAllProperties(
        ProtocolGattChannelResolver.Characteristic characteristic,
        int requiredProperties
    ) {
        return characteristic != null && (characteristic.properties & requiredProperties) == requiredProperties;
    }

    public static boolean containsUuidToken(UUID uuid, String token) {
        return uuid != null && token != null &&
            uuid.toString().replace("-", "").toUpperCase(Locale.US).contains(token.toUpperCase(Locale.US));
    }
}

