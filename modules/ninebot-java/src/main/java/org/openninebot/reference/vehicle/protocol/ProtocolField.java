package org.openninebot.reference.vehicle.protocol;

import java.util.Objects;

/** One protocol field with its own acquisition time and evidence semantics. */
public final class ProtocolField {
  public enum Acquisition { POLL, NOTIFY, CACHE, RESTORE }
  public enum Validity { VALID, UNKNOWN, INVALID }

  private final String id;
  private final String group;
  private final String unit;
  private final ProtocolValue value;
  private final long sourceMonotonicNanos;
  private final Acquisition acquisition;
  private final Validity validity;
  private final String evidenceRef;
  private final Long staleAfterMs;
  private final Long unavailableAfterMs;
  private final boolean configuration;

  public ProtocolField(
      String id,
      String group,
      String unit,
      ProtocolValue value,
      long sourceMonotonicNanos,
      Acquisition acquisition,
      Validity validity,
      String evidenceRef) {
    this(id, group, unit, value, sourceMonotonicNanos, acquisition, validity, evidenceRef, null, null, false);
  }

  public ProtocolField(String id, String group, String unit, ProtocolValue value,
      long sourceMonotonicNanos, Acquisition acquisition, Validity validity, String evidenceRef,
      Long staleAfterMs, Long unavailableAfterMs, boolean configuration) {
    if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("id");
    if (sourceMonotonicNanos < 0L) throw new IllegalArgumentException("sourceMonotonicNanos");
    this.id = id;
    this.group = group == null ? "" : group;
    this.unit = unit == null ? "" : unit;
    this.value = value;
    this.sourceMonotonicNanos = sourceMonotonicNanos;
    this.acquisition = Objects.requireNonNull(acquisition, "acquisition");
    this.validity = Objects.requireNonNull(validity, "validity");
    this.evidenceRef = evidenceRef == null ? "" : evidenceRef;
    if (staleAfterMs != null && staleAfterMs < 0L || unavailableAfterMs != null && unavailableAfterMs < 0L
        || staleAfterMs != null && unavailableAfterMs != null && unavailableAfterMs < staleAfterMs) {
      throw new IllegalArgumentException("freshness thresholds");
    }
    this.staleAfterMs = staleAfterMs;
    this.unavailableAfterMs = unavailableAfterMs;
    this.configuration = configuration;
    if (validity == Validity.VALID && value == null) throw new IllegalArgumentException("valid value required");
  }

  public String id() { return id; }
  public String group() { return group; }
  public String unit() { return unit; }
  public ProtocolValue value() { return value; }
  public long sourceMonotonicNanos() { return sourceMonotonicNanos; }
  public Acquisition acquisition() { return acquisition; }
  public Validity validity() { return validity; }
  public String evidenceRef() { return evidenceRef; }
  public Long staleAfterMs() { return staleAfterMs; }
  public Long unavailableAfterMs() { return unavailableAfterMs; }
  public boolean configuration() { return configuration; }
}
