package org.openninebot.reference.vehicle.protocol;

import org.openninebot.reference.vehicle.contract.ProtocolDescriptor;
import java.util.List;

/** A connection-generation-scoped, actor-confined pure protocol engine. */
public interface ProtocolEngine {
  ProtocolDescriptor descriptor();
  List<ProtocolEffect> onInput(ProtocolRuntimeInput input);
}
