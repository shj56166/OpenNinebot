package org.openninebot.reference.app.protocolcore;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * Platform-neutral view of a discovered GATT topology. The formal transport
 * and protocol-test UI both adapt their Android objects into this model before
 * applying a reviewed controller channel rule.
 */
public interface ProtocolGattChannelResolver {
    List<ProtocolGattChannel> resolve(List<Service> services);

    final class Service {
        public final int index;
        public final UUID uuid;
        public final boolean primary;
        public final List<Characteristic> characteristics;

        public Service(int index, UUID uuid, boolean primary, List<Characteristic> characteristics) {
            this.index = index;
            this.uuid = uuid;
            this.primary = primary;
            this.characteristics = characteristics == null
                ? Collections.emptyList()
                : Collections.unmodifiableList(new ArrayList<>(characteristics));
        }
    }

    final class Characteristic {
        public final int index;
        public final UUID uuid;
        public final int properties;
        /** Null means the topology provider did not report descriptors. */
        public final Boolean hasCccd;

        public Characteristic(int index, UUID uuid, int properties) {
            this(index, uuid, properties, null);
        }

        public Characteristic(int index, UUID uuid, int properties, Boolean hasCccd) {
            this.index = index;
            this.uuid = uuid;
            this.properties = properties;
            this.hasCccd = hasCccd;
        }
    }
}

