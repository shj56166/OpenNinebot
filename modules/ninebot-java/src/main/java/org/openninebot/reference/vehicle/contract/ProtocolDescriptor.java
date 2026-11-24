package org.openninebot.reference.vehicle.contract;

import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/** Pure protocol registration entry; no Android UUID/GATT objects are exposed. */
public final class ProtocolDescriptor {
  public enum Role { BMS, CONTROLLER, CHARGER, VEHICLE, UNKNOWN }
  public enum SamplingMode { POLL, NOTIFY }
  private final String family, variant, profileId;
  private final Role role; private final SamplingMode samplingMode; private final Set<String> capabilities;
  public ProtocolDescriptor(String family, String variant, String profileId, Role role, SamplingMode samplingMode, Set<String> capabilities) {
    if (family == null || family.trim().isEmpty()) throw new IllegalArgumentException("family");
    this.family = family; this.variant = variant == null ? "" : variant; this.profileId = profileId == null ? "" : profileId;
    this.role = Objects.requireNonNull(role); this.samplingMode = Objects.requireNonNull(samplingMode);
    this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities == null ? Collections.<String>emptySet() : capabilities));
  }
  public String family(){return family;} public String variant(){return variant;} public String profileId(){return profileId;}
  public Role role(){return role;} public SamplingMode samplingMode(){return samplingMode;} public Set<String> capabilities(){return capabilities;}
}
