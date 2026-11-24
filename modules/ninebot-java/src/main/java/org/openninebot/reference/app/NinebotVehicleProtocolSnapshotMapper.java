package org.openninebot.reference.app;

import org.openninebot.reference.vehicle.contract.ProtocolDescriptor;
import org.openninebot.reference.vehicle.protocol.ProtocolField;
import org.openninebot.reference.vehicle.protocol.ProtocolSnapshot;
import org.openninebot.reference.vehicle.protocol.ProtocolValue;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Set;

/** Typed vehicle snapshot projection for the shared Encryption2 engine. */
final class NinebotVehicleProtocolSnapshotMapper {
  private NinebotVehicleProtocolSnapshotMapper() {}

  static ProtocolSnapshot map(
      String profileId,
      long generation,
      long atNanos,
      State source,
      Set<String> capabilities,
      String evidenceRef) {
    LinkedHashMap<String, ProtocolField> fields = new LinkedHashMap<>();
    putString(fields, "identity.serial-number", "身份", source.serialNumber, atNanos, evidenceRef);
    putBoolean(fields, "identity.vehicle-has-password", "身份", source.vehicleHasPassword, atNanos, evidenceRef);
    putDouble(fields, "vehicle.speed", "实时", "km/h", source.speedKmh, atNanos, evidenceRef);
    putDouble(fields, "vehicle.power", "实时", "W", source.powerW, atNanos, evidenceRef);
    putLong(fields, "vehicle.nos", "实时", "%", source.nosRaw, atNanos, evidenceRef);
    putLong(fields, "vehicle.battery", "实时", "%", source.batteryRaw, atNanos, evidenceRef);
    putDouble(fields, "vehicle.range-left", "里程", "km", source.rangeLeftKm, atNanos, evidenceRef);
    putDouble(fields, "vehicle.mileage", "里程", "km", source.mileageKm, atNanos, evidenceRef);
    putDouble(fields, "vehicle.trip-mileage", "里程", "km", source.tripMileageKm, atNanos, evidenceRef);
    putLong(fields, "vehicle.running-time", "运行", "s", source.runningTimeSec, atNanos, evidenceRef);
    putDouble(fields, "diagnostic.battery-voltage", "诊断", "V", source.diagnosticBatteryVoltageV, atNanos, evidenceRef);
    putLong(fields, "diagnostic.battery-soc", "诊断", "%", source.diagnosticBatterySocPct, atNanos, evidenceRef);
    putDoubles(fields, "diagnostic.battery-temperatures", "诊断", "°C", source.diagnosticBatteryTemperaturesC, atNanos, evidenceRef);
    return new ProtocolSnapshot(
        ProtocolDescriptor.Role.VEHICLE,
        "ninebot_gen2_nus",
        "encryption2",
        profileId,
        generation,
        fields,
        capabilities,
        evidenceRef == null || evidenceRef.isEmpty()
            ? Collections.<String>emptyList() : Collections.singletonList(evidenceRef));
  }

  private static void putDouble(LinkedHashMap<String, ProtocolField> out, String id, String group, String unit,
      Double value, long at, String evidence) {
    if (value != null && Double.isFinite(value)) put(out, id, group, unit, ProtocolValue.ofDouble(value), at, evidence);
  }

  private static void putLong(LinkedHashMap<String, ProtocolField> out, String id, String group, String unit,
      Integer value, long at, String evidence) {
    if (value != null) put(out, id, group, unit, ProtocolValue.ofLong(value.longValue()), at, evidence);
  }

  private static void putBoolean(LinkedHashMap<String, ProtocolField> out, String id, String group,
      Boolean value, long at, String evidence) {
    if (value != null) put(out, id, group, "", ProtocolValue.ofBoolean(value), at, evidence);
  }

  private static void putDoubles(LinkedHashMap<String, ProtocolField> out, String id, String group, String unit,
      double[] value, long at, String evidence) {
    if (value == null || value.length == 0) return;
    java.util.ArrayList<Double> copy = new java.util.ArrayList<>(value.length);
    for (double item : value) {
      if (!Double.isFinite(item)) return;
      copy.add(item);
    }
    put(out, id, group, unit, ProtocolValue.ofDoubles(copy), at, evidence);
  }

  private static void putString(LinkedHashMap<String, ProtocolField> out, String id, String group,
      String value, long at, String evidence) {
    if (value != null && !value.trim().isEmpty()) put(out, id, group, "", ProtocolValue.ofString(value), at, evidence);
  }

  private static void put(LinkedHashMap<String, ProtocolField> out, String id, String group, String unit,
      ProtocolValue value, long at, String evidence) {
    out.put(id, new ProtocolField(id, group, unit, value, at, ProtocolField.Acquisition.POLL,
        ProtocolField.Validity.VALID, evidence));
  }

  static final class State {
    String serialNumber;
    Boolean vehicleHasPassword;
    Double speedKmh;
    Double powerW;
    Integer nosRaw;
    Integer batteryRaw;
    Double rangeLeftKm;
    Double mileageKm;
    Double tripMileageKm;
    Integer runningTimeSec;
    Double diagnosticBatteryVoltageV;
    Integer diagnosticBatterySocPct;
    double[] diagnosticBatteryTemperaturesC;
  }
}
