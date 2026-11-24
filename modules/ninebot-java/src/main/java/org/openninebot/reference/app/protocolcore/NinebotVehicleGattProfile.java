package org.openninebot.reference.app.protocolcore;

import org.openninebot.reference.app.NinebotVehicleProfile;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/** Reviewed NUS/custom GATT selection for the seven production Encryption2 profiles. */
public final class NinebotVehicleGattProfile {
  public static final String CHANNEL_NUS = "ninebot-encryption2-nus";
  public static final String CHANNEL_CUSTOM = "ninebot-encryption2-custom";

  private NinebotVehicleGattProfile() {}

  public static boolean isChannelId(String id) { return CHANNEL_NUS.equals(id) || CHANNEL_CUSTOM.equals(id); }

  public static ProtocolGattChannelResolver resolver(String profileId) {
    NinebotVehicleProfile profile = NinebotVehicleProfile.resolve(profileId);
    if (profile == null) throw new IllegalArgumentException("profileId");
    return services -> {
      ProtocolGattChannel nus = find(services, NinebotVehicleProfile.NUS_SERVICE_UUID,
          NinebotVehicleProfile.NUS_WRITE_UUID, NinebotVehicleProfile.NUS_NOTIFY_UUID, CHANNEL_NUS);
      ProtocolGattChannel custom = find(services, NinebotVehicleProfile.CUSTOM_SERVICE_UUID,
          NinebotVehicleProfile.CUSTOM_WRITE_UUID, NinebotVehicleProfile.CUSTOM_NOTIFY_UUID, CHANNEL_CUSTOM);
      switch (profile.channelPreference) {
        case NUS: return nus == null ? Collections.<ProtocolGattChannel>emptyList() : Collections.singletonList(nus);
        case CUSTOM: return custom == null ? Collections.<ProtocolGattChannel>emptyList() : Collections.singletonList(custom);
        case AUTO_CUSTOM_FIRST:
          return custom != null ? Collections.singletonList(custom)
              : nus == null ? Collections.<ProtocolGattChannel>emptyList() : Collections.singletonList(nus);
        case AUTO_NUS_FIRST:
        default:
          return nus != null ? Collections.singletonList(nus)
              : custom == null ? Collections.<ProtocolGattChannel>emptyList() : Collections.singletonList(custom);
      }
    };
  }

  private static ProtocolGattChannel find(
      List<ProtocolGattChannelResolver.Service> services,
      UUID serviceUuid,
      UUID writeUuid,
      UUID notifyUuid,
      String channelId) {
    if (services == null) return null;
    ArrayList<ProtocolGattChannel> matches = new ArrayList<>();
    for (ProtocolGattChannelResolver.Service service : services) {
      if (service == null || !serviceUuid.equals(service.uuid)) continue;
      ProtocolGattChannelResolver.Characteristic write = ProtocolGattChannelResolvers.findExactlyOneByUuid(service, writeUuid);
      ProtocolGattChannelResolver.Characteristic notify = ProtocolGattChannelResolvers.findExactlyOneByUuid(service, notifyUuid);
      if (write == null || notify == null
          || !ProtocolGattChannelResolvers.hasProperties(notify, ProtocolGattChannelResolvers.PROPERTY_NOTIFY)) continue;
      int writeType;
      if (ProtocolGattChannelResolvers.hasProperties(write, ProtocolGattChannelResolvers.PROPERTY_WRITE_NO_RESPONSE)) {
        writeType = ProtocolProfile.WRITE_TYPE_WITHOUT_RESPONSE;
      } else if (ProtocolGattChannelResolvers.hasProperties(write, ProtocolGattChannelResolvers.PROPERTY_WRITE)) {
        writeType = ProtocolProfile.WRITE_TYPE_WITH_RESPONSE;
      } else {
        continue;
      }
      matches.add(ProtocolGattChannel.fromTopology(
          channelId, service, write, notify, writeType, "Ninebot Encryption2 " + channelId + " channel."));
    }
    List<ProtocolGattChannel> only = ProtocolGattChannelResolvers.onlyOne(matches);
    return only.isEmpty() ? null : only.get(0);
  }
}
