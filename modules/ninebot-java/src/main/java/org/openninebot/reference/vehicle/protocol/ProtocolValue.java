package org.openninebot.reference.vehicle.protocol;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** An immutable, explicitly typed protocol field value. */
public final class ProtocolValue {
  public enum Type { DOUBLE, LONG, BOOLEAN, STRING, DOUBLE_LIST, LONG_LIST, BYTES }

  private final Type type;
  private final Object value;

  private ProtocolValue(Type type, Object value) {
    this.type = Objects.requireNonNull(type, "type");
    this.value = value;
  }

  public static ProtocolValue ofDouble(double value) {
    if (!Double.isFinite(value)) throw new IllegalArgumentException("value");
    return new ProtocolValue(Type.DOUBLE, value);
  }

  public static ProtocolValue ofLong(long value) {
    return new ProtocolValue(Type.LONG, value);
  }

  public static ProtocolValue ofBoolean(boolean value) {
    return new ProtocolValue(Type.BOOLEAN, value);
  }

  public static ProtocolValue ofString(String value) {
    return new ProtocolValue(Type.STRING, Objects.requireNonNull(value, "value"));
  }

  public static ProtocolValue ofDoubles(List<Double> values) {
    ArrayList<Double> copy = new ArrayList<>();
    if (values != null) {
      for (Double value : values) {
        if (value == null || !Double.isFinite(value)) throw new IllegalArgumentException("values");
        copy.add(value);
      }
    }
    return new ProtocolValue(Type.DOUBLE_LIST, Collections.unmodifiableList(copy));
  }

  public static ProtocolValue ofLongs(List<Long> values) {
    ArrayList<Long> copy = new ArrayList<>();
    if (values != null) {
      for (Long value : values) copy.add(Objects.requireNonNull(value, "values"));
    }
    return new ProtocolValue(Type.LONG_LIST, Collections.unmodifiableList(copy));
  }

  public static ProtocolValue ofBytes(byte[] value) {
    return new ProtocolValue(Type.BYTES, value == null ? new byte[0] : value.clone());
  }

  public Type type() { return type; }
  public double asDouble() { require(Type.DOUBLE); return (Double) value; }
  public long asLong() { require(Type.LONG); return (Long) value; }
  public boolean asBoolean() { require(Type.BOOLEAN); return (Boolean) value; }
  public String asString() { require(Type.STRING); return (String) value; }
  @SuppressWarnings("unchecked") public List<Double> asDoubles() { require(Type.DOUBLE_LIST); return (List<Double>) value; }
  @SuppressWarnings("unchecked") public List<Long> asLongs() { require(Type.LONG_LIST); return (List<Long>) value; }
  public byte[] asBytes() { require(Type.BYTES); return ((byte[]) value).clone(); }

  private void require(Type expected) {
    if (type != expected) throw new IllegalStateException("Expected " + expected + " but was " + type);
  }
}
