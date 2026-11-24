package org.openninebot.reference.vehicle.protocol;

import org.openninebot.reference.vehicle.contract.ProtocolDescriptor;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Host-neutral output of a protocol engine before M08 source/time projection. */
public final class ProtocolSnapshot {
  private final ProtocolDescriptor.Role role;
  private final String family;
  private final String variant;
  private final String profileId;
  private final long linkGeneration;
  private final Map<String, ProtocolField> fields;
  private final Set<String> capabilities;
  private final List<String> rawEvidenceRefs;

  public ProtocolSnapshot(
      ProtocolDescriptor.Role role,
      String family,
      String variant,
      String profileId,
      long linkGeneration,
      Map<String, ProtocolField> fields,
      Set<String> capabilities,
      List<String> rawEvidenceRefs) {
    if (family == null || family.trim().isEmpty()) throw new IllegalArgumentException("family");
    if (linkGeneration < 1L) throw new IllegalArgumentException("linkGeneration");
    this.role = Objects.requireNonNull(role, "role");
    this.family = family;
    this.variant = variant == null ? "" : variant;
    this.profileId = profileId == null ? "" : profileId;
    this.linkGeneration = linkGeneration;
    LinkedHashMap<String, ProtocolField> fieldCopy = new LinkedHashMap<>();
    if (fields != null) {
      for (Map.Entry<String, ProtocolField> entry : fields.entrySet()) {
        ProtocolField field = Objects.requireNonNull(entry.getValue(), "field");
        if (!field.id().equals(entry.getKey())) throw new IllegalArgumentException("field key");
        fieldCopy.put(entry.getKey(), field);
      }
    }
    this.fields = Collections.unmodifiableMap(fieldCopy);
    this.capabilities = Collections.unmodifiableSet(new LinkedHashSet<>(capabilities == null ? Collections.<String>emptySet() : capabilities));
    this.rawEvidenceRefs = Collections.unmodifiableList(new ArrayList<>(rawEvidenceRefs == null ? Collections.<String>emptyList() : rawEvidenceRefs));
  }

  public ProtocolDescriptor.Role role() { return role; }
  public String family() { return family; }
  public String variant() { return variant; }
  public String profileId() { return profileId; }
  public long linkGeneration() { return linkGeneration; }
  public Map<String, ProtocolField> fields() { return fields; }
  public Set<String> capabilities() { return capabilities; }
  public List<String> rawEvidenceRefs() { return rawEvidenceRefs; }
}
