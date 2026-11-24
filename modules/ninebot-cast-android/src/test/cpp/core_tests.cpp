#include <cstring>
#include <fstream>
#include <iostream>
#include <sstream>
#include <stdexcept>

#include "cast_core.h"
#include "mbedtls/md5.h"

using namespace motexa::cast;
namespace {
int checks = 0;
void require(bool test, const std::string& message) {
  ++checks;
  if (!test) throw std::runtime_error(message);
}
std::string property(cJSON* j, const char* key) {
  auto v = cJSON_GetObjectItemCaseSensitive(j, key);
  return cJSON_IsString(v) ? v->valuestring : "";
}
std::string load(const char* path) {
  std::ifstream f(path, std::ios::binary);
  if (!f) throw std::runtime_error("fixture_missing");
  std::ostringstream s;
  s << f.rdbuf();
  return s.str();
}
cJSON* parse(const std::string& s) {
  auto p = cJSON_Parse(s.c_str());
  if (!p) throw std::runtime_error("test_json_invalid");
  return p;
}
cJSON* firstType(cJSON* actions, const char* type) {
  for (auto j = actions->child; j; j = j->next)
    if (property(j, "type") == type) return j;
  return nullptr;
}
bool contains(const std::string& s, const char* value) {
  return s.find(value) != std::string::npos;
}
std::string call(Session& s, const std::string& operation,
                 const std::string& args = "{}", const Bytes& b = {},
                 bool flag = false, int64_t value = 0, int64_t time = 1000) {
  return s.dispatch(operation, args, b, flag, value, time,
                    1700000000000LL + time);
}
Bytes drain(Session& s, std::string actions, int64_t time = 1000) {
  Bytes result;
  for (int limit = 0; limit < 20000; limit++) {
    auto array = parse(actions);
    auto tx = firstType(array, "tx");
    if (!tx) {
      cJSON_Delete(array);
      return result;
    }
    auto b = unhex(property(tx, "hex"));
    result.insert(result.end(), b.begin(), b.end());
    auto id = cJSON_GetObjectItemCaseSensitive(tx, "id");
    int64_t n = int64_t(id->valuedouble);
    cJSON_Delete(array);
    actions = call(s, "transport_result",
                   "{\"id\":" + std::to_string(n) + ",\"status\":0}", {}, false,
                   0, time);
  }
  throw std::runtime_error("test_drain_loop");
}
Bytes response(uint8_t source, uint8_t cmd, uint8_t index, const Bytes& data) {
  Bytes b = frame(source, cmd, index, data);
  b[3] = source;
  b[4] = 0x3e;
  return b;
}
using PreparationReads = std::vector<std::array<int, 3>>;
PreparationReads mzPreparationReads() {
  std::unique_ptr<cJSON, decltype(&cJSON_Delete)> fixture(
      parse(load(MZ_PREPARATION_FIXTURE)), cJSON_Delete);
  auto number = [](cJSON* object, const char* key) {
    auto value = cJSON_GetObjectItemCaseSensitive(object, key);
    if (!cJSON_IsNumber(value)) throw std::runtime_error("Mz fixture number");
    return value->valueint;
  };
  require(number(fixture.get(), "model") == 14360 &&
              number(fixture.get(), "hardwareId") == 4360 &&
              number(fixture.get(), "configurationVersion") == 600015500,
          "recovered Mz contract identity is independent of auth generation");
  require(number(fixture.get(), "powerMask") == 4 &&
              number(fixture.get(), "activationMask") == 2048,
          "recovered Mz masks match tested power and activation replies");
  auto rows = cJSON_GetObjectItemCaseSensitive(fixture.get(), "readCommands");
  require(cJSON_GetArraySize(rows) == 6, "six recovered Mz read commands");
  PreparationReads reads;
  for (auto row = rows->child; row; row = row->next) {
    int target = number(row, "target"), index = number(row, "index"),
        length = number(row, "responseBytes");
    require(number(row, "responseSource") == target &&
                number(row, "cmd") == 1 && number(row, "responseCmd") == 4 &&
                unhex(property(row, "dataHex")) == Bytes{uint8_t(length)},
            "frozen Mz request length and response route");
    require(index != 0x37, "Mz must not read N3-only rInfoBool2");
    reads.push_back({target, index, length});
  }
  return reads;
}
std::vector<Bytes> completeDatagrams(Session&, const std::string&, int64_t);
void cryptoVectors(const char* path) {
  auto fixture = parse(load(path));
  auto rows = cJSON_GetObjectItemCaseSensitive(fixture, "rows");
  require(cJSON_GetArraySize(rows) == 53,
          "53 frozen independent Gen2 wire vectors");
  for (auto r = rows->child; r; r = r->next) {
    Gen2Cipher enc;
    enc.key1 = unhex(property(r, "key1"));
    enc.key2 = unhex(property(r, "key2"));
    enc.auth = unhex(property(r, "auth"));
    auto counter = cJSON_GetObjectItemCaseSensitive(r, "counter");
    enc.txCounter = counter->valueint ? counter->valueint - 1 : 0;
    auto plain = unhex(property(r, "plain")), wire = unhex(property(r, "wire"));
    require(enc.encrypt(plain) == wire, "encrypt " + property(r, "id"));
    Gen2Cipher dec;
    dec.key1 = enc.key1;
    dec.key2 = enc.key2;
    dec.auth = enc.auth;
    Bytes decoded;
    require(dec.decrypt(wire, &decoded) == 0 && decoded == plain,
            "decrypt " + property(r, "id"));
    if (counter->valueint) {
      require(dec.decrypt(wire, &decoded) == 2, "replay " + property(r, "id"));
    }
    auto corrupt = wire;
    corrupt[3] ^= 1;
    Gen2Cipher bad;
    bad.key1 = enc.key1;
    bad.key2 = enc.key2;
    bad.auth = enc.auth;
    require(bad.decrypt(corrupt, &decoded) != 0, "tamper " + property(r, "id"));
  }
  cJSON_Delete(fixture);
}
void commonCipherBoundaryVectors(const char* path) {
  auto fixture = parse(load(path));
  auto rows = cJSON_GetObjectItemCaseSensitive(fixture, "cases");
  int tested = 0;
  for (auto r = rows->child; r; r = r->next) {
    if (cJSON_IsTrue(cJSON_GetObjectItemCaseSensitive(r, "aa55"))) continue;
    auto plain = unhex(property(r, "plaintext_hex"));
    if (plain.size() <= 255) continue;
    Gen2Cipher enc;
    enc.key1 = unhex(property(r, "key1_hex"));
    enc.key2 = unhex(property(r, "key2_hex"));
    enc.auth = unhex(property(r, "auth_hex"));
    enc.txCounter =
        cJSON_GetObjectItemCaseSensitive(r, "initial_counter")->valueint;
    auto original = cJSON_GetObjectItemCaseSensitive(r, "original_cipher");
    auto wire = unhex(property(original, "bytes_hex"));
    require(enc.encrypt(plain) == wire,
            "original SDK 259-byte MAC length boundary");
    Gen2Cipher dec;
    dec.key1 = enc.key1;
    dec.key2 = enc.key2;
    dec.auth = enc.auth;
    Bytes decoded;
    require(dec.decrypt(wire, &decoded) == 0 && decoded == plain,
            "original SDK 259-byte frame decryption");
    ++tested;
  }
  require(tested > 0, "independent wide-length common-cipher fixtures present");
  Gen2Cipher advanced;
  advanced.key1 = Bytes(16, 0x11);
  advanced.key2 = advanced.auth = Bytes(16, 0x22);
  advanced.txCounter = 2;
  advanced.rxCounter = 31;
  auto wire = advanced.encrypt(frame(4, 1, 0));
  require(wire[wire.size() - 2] == 0 && wire.back() == 32,
          "transmit counter follows greater authenticated receive counter");
  cJSON_Delete(fixture);
}
void rtpTests() {
  uint16_t seq = 0x1234;
  auto p = packetizeH264({0}, true, 0x12345678, 1, &seq, true, false);
  require(p.size() == 1 &&
              hex(p[0]) == "90a012341234567800000000000000010019780600",
          "frozen original DEX H264 RTP vector");
  for (size_t length : {size_t(1), size_t(1380), size_t(1381), size_t(2761)}) {
    Bytes frame(length, 0x42);
    seq = 65535;
    auto packets = packetizeH264(frame, false, 17, 0, &seq, true, false);
    Bytes reconstructed;
    for (size_t i = 0; i < packets.size(); i++) {
      auto& packet = packets[i];
      require(packet.size() <= 1400, "pro UDP datagram cap");
      require(packet[17] == uint8_t(2 | (i == 0 ? 16 : 0) |
                                    (i + 1 == packets.size() ? 8 : 0)),
              "first middle last header");
      require(bool(packet[1] & 128) == (i + 1 == packets.size()),
              "marker last only");
      reconstructed.insert(reconstructed.end(), packet.begin() + 20,
                           packet.end());
    }
    require(reconstructed == frame, "H264 reconstruction");
  }
}
void bleRtpTests() {
  for (int mtu : {31, 185, 247, 517}) {
    Bytes f(1000, 0x23);
    uint16_t seq = 65535;
    auto packets = packetizeBle(f, true, mtu, 0x12345678, 1, &seq);
    Bytes restored;
    for (size_t i = 0; i < packets.size(); i++) {
      auto& p = packets[i];
      require(p.size() <= size_t(mtu - 10), "ATT media margin");
      require((p[1] & 127) == 0, "BLE PT0");
      require(p[17] == 1, "BLE key picture type");
      restored.insert(restored.end(), p.begin() + 20, p.end());
    }
    require(restored == f, "BLE whole frame");
  }
  bool threw = false;
  try {
    uint16_t seq = 0;
    packetizeBle({1}, true, 30, 0, 1, &seq);
  } catch (...) {
    threw = true;
  }
  require(threw, "small MTU rejected");
}
void sessionTests(const std::string& modelId, const PreparationReads& prepareReads,
                  int channel = 0, const std::string& fault = "") {
  const std::string options =
      "{\"deviceName\":\"NB-PROBE-SYNTH\",\"profileId\":\"encryption2_standard_"
      "nus\",\"modelId\":\"" + modelId + "\",\"authVersion\":2,\"attMtu\":247,"
      "\"controlWriteType\":1}";
  Session s(options, "{\"passwordHex\":\"2122232425262728292a2b2c2d2e2f30\"}");
  auto started = call(s, "start");
  require(contains(started, "\"writeType\":1"),
          "actual characteristic control write property honored");
  auto sent = drain(s, started);
  require(hex(sent) == "5aa5007a1f7e1a000062ff0000",
          "session independent PRE_COMM");
  Bytes auth = unhex("0102030405060708090a0b0c0d0e0f10"),
        sn = unhex("534e313233343536373839303132");
  auto pre = unhex(
      "5aa51e40257e1be872f82f22de77cfe50aeca249152a0aba3eca1914ec45f1db38de9e75"
      "290000c8fb0000");
  auto first = call(s, "control", "{}", Bytes(pre.begin(), pre.begin() + 5));
  require(!contains(first, "wait_auth"), "partial notification not complete");
  auto rest = call(s, "control", "{}", Bytes(pre.begin() + 5, pre.end()));
  sent = drain(s, rest);
  require(hex(sent) == "5aa50ee9aaec493dde7cea7833e7da2a76fcaa5bcc30d672240002",
          "session independent AUTH wire");
  Gen2Cipher peer;
  peer.key1 = unhex("2122232425262728292a2b2c2d2e2f30");
  peer.key2 = auth;
  peer.auth = auth;
  peer.txCounter = 1;
  auto accepted =
      call(s, "control", "{}", peer.encrypt(response(4, 0x5d, 1, {})));
  require(contains(accepted, "\"state\":\"authenticated\""),
          "auth accepted only matching safe reply");
  require(contains(call(s, "command", "{\"name\":\"start_cast\"}"),
                   "verified_connection_preparation_required"),
          "authentication cannot skip VerifyConfig preparation");
  auto preparation = call(s, "command", "{\"name\":\"read_cast_config\"}");
  if (prepareReads.empty()) {
    require(contains(preparation, "verify_config_unavailable_for_model") &&
                !contains(preparation, "\"type\":\"tx\""),
            "unknown model or hardware ID cannot select a preparation table");
    return;
  }
  auto events = parse(preparation);
  auto contract = firstType(events, "preparation_contract");
  require(contract && property(contract, "modelId") == modelId &&
              property(contract, "scope") == "local_read_only" &&
              property(contract, "accountBindingCheck") == "not_performed" &&
              property(contract, "connectionInfoUpload") == "not_performed",
          "local preparation never claims account binding or upload success");
  cJSON_Delete(events);
  auto condition = drain(s, preparation);
  Bytes rejectedPrepare;
  require(peer.decrypt(condition, &rejectedPrepare) == 0 &&
              rejectedPrepare == frame(9, 1, 3, {2}),
          "preparation starts from main power condition");
  if (!fault.empty() && fault != "serial") {
    uint8_t source = fault == "source" ? 1 : 9,
            cmd = fault == "command" ? 1 : 4,
            index = fault == "index" ? 4 : 3;
    Bytes payload = fault == "short" ? Bytes{4}
                    : fault == "long" ? Bytes{4, 0, 0}
                                      : Bytes{4, 0};
    auto rejected = fault == "timeout"
                        ? call(s, "tick", "{}", {}, false, 0, 4001)
                        : call(s, "control", "{}",
                               peer.encrypt(response(source, cmd, index, payload)));
    require(contains(rejected, fault == "timeout" ? "stage_timeout_wait_prepare"
                           : "preparation_read_response_mismatch") &&
                !contains(rejected, "\"type\":\"tx\"") &&
                !contains(rejected, "\"prepared\":true"),
            "invalid Mz preparation response cannot advance: " + fault);
    return;
  }
  auto conditionOff =
      call(s, "control", "{}", peer.encrypt(response(9, 4, 3, {0, 0})));
  require(contains(conditionOff, "vehicle_command_condition_main_power_off") &&
              !contains(conditionOff, "\"type\":\"tx\""),
          "power-off condition prevents casting config commands");
  drain(s, call(s, "command", "{\"name\":\"read_cast_config\"}"));
  drain(s, call(s, "control", "{}", peer.encrypt(response(9, 4, 3, {4, 0}))));
  auto inactive =
      call(s, "control", "{}", peer.encrypt(response(1, 4, 0xb2, {0, 0})));
  require(contains(inactive, "vehicle_activation_not_verified") &&
              !contains(inactive, "\"type\":\"tx\""),
          "activation bit absent never defaults to activated");
  auto preparing =
      drain(s, call(s, "command", "{\"name\":\"read_cast_config\"}"));
  Bytes decoded;
  for (auto read : prepareReads) {
    require(peer.decrypt(preparing, &decoded) == 0 &&
                decoded == frame(uint8_t(read[0]), 1, uint8_t(read[1]),
                                 {uint8_t(read[2])}),
            "frozen VerifyConfig read-only preparation order");
    Bytes reply(read[2], 0);
    if (read[0] == 9 && read[1] == 3) reply = {4, 0};
    if (read[0] == 1 && read[1] == 0xb2) reply = {0, 8};
    if (read[0] == 1 && read[1] == 0x10) reply = sn;
    if (fault == "serial" && read[1] == 0x10) reply[0] ^= 1;
    auto received = call(s, "control", "{}",
                         peer.encrypt(response(uint8_t(read[0]), 4,
                                               uint8_t(read[1]), reply)));
    if (fault == "serial" && read[1] == 0x10) {
      require(contains(received, "preparation_serial_mismatch") &&
                  !contains(received, "\"type\":\"tx\""),
              "Mz preparation SN must match authenticated vehicle");
      return;
    }
    preparing = drain(s, received);
  }
  auto begin = preparing;
  require(peer.decrypt(begin, &decoded) == 0 &&
              decoded == frame(0x23, 0x82, 1, {1, 8, 0}),
          "read config command");
  std::string text =
      "{\"codecType\":" + std::to_string(channel ? 1 : 0) +
      ",\"rtpChannel\":" + std::to_string(channel) +
      ",\"codecWidth\":240,\"codecHeight\":320,\"frameRate\":5,\"bitRate\":200000}";
  Bytes cfg(text.begin(), text.end());
  cfg.push_back(0);
  uint32_t size = uint32_t(cfg.size());
  drain(s,
        call(s, "control", "{}",
             peer.encrypt(response(
                 0x23, 0x82, 1, {uint8_t(size), uint8_t(size >> 8), 0, 0}))));
  drain(s,
        call(s, "control", "{}", peer.encrypt(response(0x23, 0x83, 0, cfg))));
  Bytes md5(16);
  mbedtls_md5(cfg.data(), cfg.size(), md5.data());
  auto config =
      call(s, "control", "{}", peer.encrypt(response(0x23, 0x84, 0, md5)));
  require(contains(config, "\"width\":240") &&
              contains(config, ("\"channel\":" + std::to_string(channel)).c_str()) &&
              contains(config, "cast_config_md5_verified"),
          "verified instrument config selects transport independently of model");
  if (channel != 0) {
    auto open = drain(s, call(s, "command", "{\"name\":\"start_cast\"}"));
    require(peer.decrypt(open, &decoded) == 0 &&
                decoded == frame(0x23, 0x71, 0, {2}),
            "Wi-Fi cast opens through authenticated BLE before P2P discovery");
    auto openAck =
        call(s, "control", "{}", peer.encrypt(response(0x23, 0x71, 0, {})));
    require(contains(openAck, "cast_mode_ready") &&
                !contains(openAck, "casting_ready"),
            "BLE mode acknowledgement waits separately for the Wi-Fi route");
    require(!contains(call(s, "tick", "{}", {}, false, 0, 2000),
                      "\"type\":\"tx\""),
            "BLE mode acknowledgement alone sends no UDP heartbeat");
    require(contains(call(s, "frame", "{}", {0, 0, 0, 1, 0x65}, true),
                     "wifi_transport_not_ready"),
            "frames stay blocked until the Wi-Fi route is ready");
    require(contains(call(s, "command", "{\"name\":\"read_cast_config\"}"),
                     "stop_cast_before_reconfigure"),
            "configuration cannot change after BLE opened the instrument");
    auto stopOpen = drain(s, call(s, "command", "{\"name\":\"stop_cast\"}"));
    require(peer.decrypt(stopOpen, &decoded) == 0 &&
                decoded == frame(0x23, 0x71, 0, {0}),
            "an open-only session can be stopped over BLE without a Wi-Fi link");
    auto stopAck = call(s, "control", "{}",
                        peer.encrypt(response(0x23, 0x71, 0, {})));
    require(contains(stopAck, "stop_mode_ack"),
            "stopping open-only mode waits for its BLE acknowledgement");
    auto reopen = drain(s, call(s, "command", "{\"name\":\"start_cast\"}"));
    require(peer.decrypt(reopen, &decoded) == 0 &&
                decoded == frame(0x23, 0x71, 0, {2}),
            "an acknowledged stop permits a fresh open request");
    auto reopenAck = call(s, "control", "{}",
                          peer.encrypt(response(0x23, 0x71, 0, {})));
    require(contains(reopenAck, "cast_mode_ready"),
            "fresh open waits for Wi-Fi after its BLE acknowledgement");
    auto routeReady = call(s, "command", "{\"name\":\"start_wifi_media\"}");
    require(!contains(routeReady, "\"type\":\"tx\"") &&
                contains(routeReady, "casting_ready"),
            "a ready Wi-Fi route starts media without sending mode 2 again");
  } else {
    auto mode = drain(s, call(s, "command", "{\"name\":\"start_cast\"}"));
    require(
        peer.decrypt(mode, &decoded) == 0 && decoded == frame(0x23, 0x71, 0, {2}),
        "verified model BLE start mode");
    auto ack = call(s, "control", "{}", peer.encrypt(response(0x23, 0x71, 0, {})));
    require(contains(ack, "casting_ready"), "matching mode ack");
  }
  Bytes mpeg = {0, 0, 1, 0xb3, 0x0f, 0x01, 0x40, 0x13, 0, 0, 1, 0, 0, 8};
  auto h264Frame = unhex("000000016742001f0000000168ce000000016588");
  auto media = call(s, "frame", "{}", channel ? h264Frame : mpeg, true, 0);
  require(contains(media, channel == 0 ? "ble_media" : "udp_media") &&
              contains(media, "frame_submitted"),
          "official media follows verified BLE/UDP/KCP config");
  if (channel == 0) {
    auto dropped = call(s, "frame", "{}", mpeg, true, 200000);
    require(contains(dropped, "frame_dropped_transport_backpressure"),
            "bounded BLE frame queue");
    drain(s, media);
  } else {
    require(!completeDatagrams(s, media, 1000).empty(),
            "official UDP/KCP local callbacks acknowledged separately");
  }
  auto request = drain(
      s, call(s, "command",
              "{\"name\":\"read_vehicle\",\"args\":{\"continuous\":false}}"));
  require(
      peer.decrypt(request, &decoded) == 0 && decoded == frame(1, 1, 0x26, {2}),
      "MMAX90 safe read first register");
  auto telemetry =
      call(s, "control", "{}", peer.encrypt(response(1, 4, 0x26, {123, 0})));
  require(contains(telemetry, "speedKmh") && contains(telemetry, "12.3"),
          "MMAX90 speed decoding");
  drain(s, telemetry);
  auto stopRead = call(s, "command", "{\"name\":\"stop_cast\"}");
  require(contains(stopRead, "stop_pending_control_transaction"),
          "stop during active vehicle read retained");
  auto deferredStop = drain(
      s, call(s, "control", "{}", peer.encrypt(response(1, 4, 0xbd, {0, 0}))));
  require(peer.decrypt(deferredStop, &decoded) == 0 &&
              decoded == frame(0x23, 0x71, 0, {0}),
          "read completion sends deferred stop mode");
  auto stopped =
      call(s, "control", "{}", peer.encrypt(response(0x23, 0x71, 0, {})));
  require(
      contains(stopped, "stop_mode_ack") && !contains(stopped, "casting_ready"),
      "deferred stop stays stopped");
  drain(s, call(s, "command", "{\"name\":\"start_cast\"}"));
  call(s, "command", "{\"name\":\"stop_cast\"}");
  auto lateStartAck =
      call(s, "control", "{}", peer.encrypt(response(0x23, 0x71, 0, {})));
  require(!contains(lateStartAck, "casting_ready"),
          "late start acknowledgment cannot restart stopped media");
  deferredStop = drain(s, lateStartAck);
  require(peer.decrypt(deferredStop, &decoded) == 0 &&
              decoded == frame(0x23, 0x71, 0, {0}),
          "late start acknowledgment immediately exits vehicle cast mode");
  call(s, "control", "{}", peer.encrypt(response(0x23, 0x71, 0, {})));
  auto pendingRead =
      drain(s, call(s, "command", "{\"name\":\"read_vehicle\"}"));
  require(peer.decrypt(pendingRead, &decoded) == 0,
          "second read request valid");
  call(s, "command", "{\"name\":\"stop_cast\"}");
  deferredStop = drain(s, call(s, "tick", "{}", {}, false, 0, 5000));
  require(peer.decrypt(deferredStop, &decoded) == 0 &&
              decoded == frame(0x23, 0x71, 0, {0}),
          "timed-out vehicle read still honors deferred stop");
  bool invalidWriteType = false;
  try {
    Session bad("{\"controlWriteType\":3}", "{}");
  } catch (const std::invalid_argument&) {
    invalidWriteType = true;
  }
  require(invalidWriteType, "invalid GATT write property rejected");
  Session gen3("{\"deviceName\":\"N\",\"authVersion\":3}",
               "{\"v3\":{\"sn\":\"synthetic\"}}");
  require(
      contains(call(gen3, "start"), "gen3_explicit_auth_board_key1_required"),
      "Gen3 without explicit key derivation context never authenticates");
  Session pro(
      "{\"authVersion\":0,\"externalCastingEnabled\":true,\"transport\":"
      "\"udp\",\"wireProfile\":\"pro\",\"width\":848,\"height\":480,\"fps\":20,"
      "\"bitrate\":2000000}",
      "{}");
  require(contains(call(pro, "start"), "external_mode_ready"),
          "explicit external mode");
  require(contains(call(pro, "command", "{\"name\":\"start_cast\"}"),
                   "\"authenticated\":false"),
          "external mode no false auth");
  auto h264 = unhex("000000016742001f0000000168ce000000016588");
  require(contains(call(pro, "frame", "{}", h264, true, 1000000), "udp_media"),
          "Pro H264 datagrams");
  auto deadline = call(pro, "tick", "{}", {}, false, 0, 10000);
  require(contains(deadline, "udp_send_callback_timeout"),
          "local UDP completion timeout");
}
std::vector<Bytes> completeDatagrams(Session& session,
                                     const std::string& actions, int64_t time) {
  std::vector<Bytes> packets;
  auto array = parse(actions);
  for (auto action = array->child; action; action = action->next) {
    if (property(action, "type") != "tx") continue;
    packets.push_back(unhex(property(action, "hex")));
    auto id = cJSON_GetObjectItemCaseSensitive(action, "id");
    call(session, "transport_result",
         "{\"id\":" + std::to_string(int64_t(id->valuedouble)) +
             ",\"status\":0}",
         {}, false, 0, time);
  }
  cJSON_Delete(array);
  return packets;
}
void kcpBacklogTests() {
  Session session(
      "{\"authVersion\":0,\"externalCastingEnabled\":true,\"transport\":"
      "\"kcp\","
      "\"wireProfile\":\"pro\",\"width\":848,\"height\":480,\"fps\":20,"
      "\"bitrate\":2000000,\"repeatKeyframe\":false}",
      "{}");
  call(session, "start");
  call(session, "command", "{\"name\":\"start_cast\"}");
  auto frame = unhex("000000016742001f0000000168ce0000000165");
  frame.resize(3300, 0x42);
  auto sent = call(session, "frame", "{}", frame, true, 0, 1000);
  require(contains(sent, "kcp_segment_sent"),
          "KCP first sends have segment diagnostics");
  auto packets = completeDatagrams(session, sent, 1000);
  require(packets.size() >= 3,
          "KCP backlog fixture has several independent outstanding segments");
  auto retransmission = call(session, "tick", "{}", {}, false, 0, 1300);
  require(contains(retransmission, "kcp_retransmit"),
          "KCP retransmits keep original age and are diagnosed");
  completeDatagrams(session, retransmission, 1300);
  Bytes ack;
  for (const auto& packet : packets) {
    if (packet.size() >= 24 && packet[4] == 81 && packet[12] == 1) {
      ack.assign(packet.begin(), packet.begin() + 24);
      ack[4] = 82;
      ack[5] = 0;
      for (int i = 16; i < 24; i++) ack[i] = 0;
      break;
    }
  }
  require(ack.size() == 24,
          "selective ACK fixture targets SN1 while retaining older SN0");
  auto progressed = call(session, "datagram", "{}", ack, false, 0, 1500);
  require(contains(progressed, "kcp_segment_acked"),
          "real selective ACK releases only its acknowledged segment");
  completeDatagrams(session, progressed, 1500);
  auto stopped = call(session, "tick", "{}", {}, false, 0, 2001);
  require(
      contains(stopped, "kcp_oldest_unacked_over_1s") &&
          contains(stopped, "media_summary"),
      "oldest submitted KCP data over1s stops even when other ACKs progress");
  require(contains(call(session, "frame", "{}", frame, true, 1000000, 2010),
                   "session_closed"),
          "KCP terminal backlog stops further encoded frame submission");
}
Bytes gen3PlainReply(uint8_t board, uint8_t group, uint8_t index,
                     const Bytes& payload) {
  Bytes wire{0x5a, 0xa5, uint8_t(payload.size()), board, 0x3e, group, index};
  wire.insert(wire.end(), payload.begin(), payload.end());
  uint16_t sum = 0;
  for (size_t i = 2; i < wire.size(); i++) sum = uint16_t(sum + wire[i]);
  sum = uint16_t(~sum);
  wire.push_back(uint8_t(sum));
  wire.push_back(uint8_t(sum >> 8));
  return wire;
}
void gen3SessionTests(const std::string& modelId,
                     const PreparationReads& prepareReads) {
  const std::string options =
      "{\"authVersion\":3,\"modelId\":\"" + modelId +
      "\",\"attMtu\":247,\"authBoard\":4,"
      "\"authKey1Hex\":\"0102030405060708090a0b0c0d0e0f10\"}";
  const std::string keyHex = "00112233445566778899aabbccddeeff";
  const std::string creds =
      "{\"v3\":{\"sn\":\"SN123456789012\",\"curAppEncHex\":\"" + keyHex +
      "\"}}";
  Session session(options, creds);
  auto challenge = call(session, "start");
  require(contains(challenge, "wait_gen3_fast") &&
              contains(challenge, "\"sensitive\":true"),
          "Gen3 cached-key6005 emits sensitive nonce challenge");
  auto wire = drain(session, challenge);
  require(wire.size() == 43 && wire[5] == 0x60 && wire[6] == 5,
          "Gen3 cached-key uses verified checksummed6005 framing");
  Bytes serial = unhex("534e313233343536373839303132");
  Bytes tlv{0x0f, 1, 0, 0x14, uint8_t(serial.size())};
  tlv.insert(tlv.end(), serial.begin(), serial.end());
  auto selected =
      call(session, "control", "{}", gen3PlainReply(4, 0x60, 6, tlv));
  require(contains(selected, "auth_key_selected") &&
              !contains(selected, "\"authenticated\":true") &&
              !contains(selected, "gen3_key_state"),
          "checksummed6006 cannot authenticate or persist credentials");
  wire = drain(session, selected);
  std::array<uint8_t, 16> key1{}, key{};
  auto key1Bytes = unhex("0102030405060708090a0b0c0d0e0f10"),
       keyBytes = unhex(keyHex);
  std::copy(key1Bytes.begin(), key1Bytes.end(), key1.begin());
  std::copy(keyBytes.begin(), keyBytes.end(), key.begin());
  gen3::CommonFrameCipher peer(key1, key, key, 1, 1);
  Bytes decoded;
  require(peer.decrypt(wire, &decoded) && decoded == frame(9, 1, 3, {2}),
          "Gen3 cipher routes first verified prepare read to ECU9");
  Bytes encrypted;
  for (const auto& unmatched : {
           response(1, 4, 3, {4, 0}), response(9, 1, 3, {4, 0}),
           response(9, 4, 4, {4, 0}), response(9, 4, 3, {4}),
           response(9, 4, 3, {4, 0, 0})}) {
    require(peer.encrypt(unmatched, &encrypted), "Gen3 negative proof fixture");
    auto rejected = call(session, "control", "{}", encrypted);
    require(contains(rejected, "gen3_unmatched_encrypted_proof") &&
                !contains(rejected, "gen3_key_state") &&
                !contains(rejected, "\"authenticated\":true") &&
                !contains(rejected, "\"type\":\"tx\""),
            "wrong source/cmd/index/length cannot authenticate or promote Gen3");
  }
  require(peer.encrypt(response(9, 4, 3, {4, 0}), &encrypted),
          "independent Gen3 prepare response encrypted");
  auto proof = call(session, "control", "{}", encrypted);
  require(contains(proof, "gen3_key_state") &&
              contains(proof, "\"encryptedProof\":true"),
          "Gen3 cached-key persistence waits for valid cipher roundtrip");
  auto pending = drain(session, proof);
  std::string completed;
  for (size_t i = 1; i < prepareReads.size(); i++) {
    auto read = prepareReads[i];
    require(peer.decrypt(pending, &decoded) &&
                decoded == frame(uint8_t(read[0]), 1, uint8_t(read[1]),
                                 {uint8_t(read[2])}),
            "Gen3 prepared queue uses current encrypted session and explicit "
            "targets");
    Bytes payload(read[2], 0);
    if (read[1] == 0xb2) payload = {0, 8};
    if (read[1] == 0x10) payload = serial;
    peer.encrypt(response(uint8_t(read[0]), 4, uint8_t(read[1]), payload),
                 &encrypted);
    completed = call(session, "control", "{}", encrypted);
    pending = drain(session, completed);
  }
  require(contains(completed, "verified_read_only_preparation_complete") &&
              contains(completed, "\"prepared\":true"),
          "Gen3 prepared only after all verification reads");
  auto config = drain(
      session, call(session, "command", "{\"name\":\"read_cast_config\"}"));
  require(peer.decrypt(config, &decoded) &&
              decoded == frame(0x23, 0x82, 1, {1, 8, 0}),
          "Gen3 established cipher routes cast config to TFT35");
  Session tampered(options, creds);
  drain(tampered, call(tampered, "start"));
  drain(tampered,
        call(tampered, "control", "{}", gen3PlainReply(4, 0x60, 6, tlv)));
  encrypted.back() ^= 1;
  auto rejected = call(tampered, "control", "{}", encrypted);
  require(contains(rejected, "gen3_integrity_or_replay_rejected") &&
              !contains(rejected, "gen3_key_state"),
          "forged6006 cannot promote a credential without encrypted proof");
}
std::string providerId(const std::string& actions) {
  auto array = parse(actions);
  auto provider = firstType(array, "auth_provider");
  std::string id = provider ? property(provider, "requestId") : "";
  require(!id.empty(), "native provider event carries a correlation id");
  auto request = provider
                     ? cJSON_GetObjectItemCaseSensitive(provider, "request")
                     : nullptr;
  require(request && property(request, "version") == "3" &&
              property(request, "sn") == "SN123456789012",
          "native provider request contract uses explicit version string and "
          "selected SN");
  cJSON_Delete(array);
  return id;
}
std::string providerReply(Session& session, const std::string& operation,
                          const std::string& id, const std::string& result,
                          int64_t time = 1000) {
  return call(session, "command",
              "{\"name\":\"auth_provider_result\",\"args\":{\"operation\":\"" +
                  operation + "\",\"requestId\":\"" + id +
                  "\",\"success\":true,\"result\":" + result + "}}",
              {}, false, 0, time);
}
void gen3FullSessionTests(const std::string& modelId,
                         const PreparationReads& prepareReads) {
  for (const std::string fault : {"none", "serial", "deadline", "verify",
                                  "fractional_level", "string_level"}) {
    const std::string options =
        "{\"authVersion\":3,\"modelId\":\"" + modelId +
        "\",\"attMtu\":247,\"authBoard\":"
        "4,"
        "\"authKey1Hex\":\"0102030405060708090a0b0c0d0e0f10\"}";
    Session session(options,
                    "{\"v3\":{\"sn\":\"SN123456789012\",\"authApi\":{}}}");
    auto initial = drain(session, call(session, "start"));
    require(initial.size() == 43 && initial[5] == 0x60 && initial[6] == 5,
            "no cached Gen3 keys starts explicit full provider transaction");
    Bytes serial = unhex("534e313233343536373839303132");
    Bytes tlv{0x0f, 1, 3, 0x14, uint8_t(serial.size())};
    tlv.insert(tlv.end(), serial.begin(), serial.end());
    tlv.insert(tlv.end(), {0x16, 4, 'P', 'N', '0', '1', 1, 1, 7, 0x19, 1, 1});
    auto challenge =
        call(session, "control", "{}", gen3PlainReply(4, 0x60, 6, tlv));
    auto id = providerId(challenge);
    require(contains(providerReply(session, "challenge", "stale-id", "{}"),
                     "gen3_stale_provider_result"),
            "stale provider callbacks cannot advance a new transaction");
    const std::string challengeMaterial =
        "{\"sn\":\"SN123456789012\",\"level\":1,\"nonceHex\":"
        "\"0102030405060708\"}";
    if (fault == "serial") {
      auto rejected = providerReply(
          session, "challenge", id,
          "{\"sn\":\"wrong-serial\",\"level\":1,\"nonceHex\":\"01\"}");
      require(contains(rejected, "gen3_provider_serial_binding_mismatch") &&
                  !contains(rejected, "credentials_update"),
              "authorized provider cannot switch selected vehicle SN");
      continue;
    }
    if (fault == "deadline") {
      auto rejected =
          providerReply(session, "challenge", id, challengeMaterial, 30000);
      require(contains(rejected, "gen3_provider_result_after_deadline"),
              "late provider callback rejected without requiring tick first");
      continue;
    }
    if (fault == "fractional_level" || fault == "string_level") {
      auto rejected = providerReply(
          session, "challenge", id,
          "{\"sn\":\"SN123456789012\",\"level\":" +
              std::string(fault == "fractional_level" ? "1.5" : "\"1\"") +
              ",\"nonceHex\":\"01\"}");
      require(contains(rejected, "gen3_provider_level_invalid"),
              "provider security level requires exact numeric1/2/4");
      continue;
    }
    auto wire = drain(
        session, providerReply(session, "challenge", id, challengeMaterial));
    require(wire.size() > 9 && wire[6] == 1,
            "provider challenge yields verified6001");
    auto responseAction = call(
        session, "control", "{}",
        gen3PlainReply(4, 0x60, 2,
                       {7, 3, 0x11, 0x22, 0x33, 2, 8, 1, 2, 3, 4, 5, 6, 7, 8}));
    id = providerId(responseAction);
    auto distribution =
        providerReply(session, "response", id,
                      "{\"sn\":\"SN123456789012\",\"appEncHex\":"
                      "\"00112233445566778899aabbccddeeff\","
                      "\"deviceEncHex\":\"112233445566\",\"nonceHex\":"
                      "\"1020304050607080\",\"aadHex\":\"aabb\"}");
    require(!contains(distribution, "credentials_update"),
            "provider key is candidate before vehicle proof");
    wire = drain(session, distribution);
    require(wire[6] == 3, "provider distribution yields verified6003");
    auto distributed =
        call(session, "control", "{}", gen3PlainReply(4, 0x60, 4, {15, 1, 0}));
    require(!contains(distributed, "credentials_update"),
            "6004 candidate hint never persists current key");
    wire = drain(session, distributed);
    require(wire.size() == 43 && wire[6] == 5,
            "successful distribution uses fresh encrypted6005 challenge");
    tlv = {15, 1, 0, 20, uint8_t(serial.size())};
    tlv.insert(tlv.end(), serial.begin(), serial.end());
    tlv.insert(tlv.end(),
               {22, 4, 'P', 'N', '0', '1', 24, 3, 1, 2, 3, 23, 2, 0xaa, 0xbb});
    auto verify =
        call(session, "control", "{}", gen3PlainReply(4, 0x60, 6, tlv));
    id = providerId(verify);
    require(
        !contains(verify, "credentials_update") &&
            !contains(verify, "\"authenticated\":true"),
        "final checksummed6006 still waits for provider and encrypted proof");
    if (fault == "verify") {
      auto rejected =
          providerReply(session, "verify", id,
                        "{\"sn\":\"SN123456789012\",\"verified\":false}");
      require(contains(rejected, "gen3_full_provider_verification") &&
                  !contains(rejected, "credentials_update"),
              "provider refusal clears candidate without persisting it");
      continue;
    }
    std::array<uint8_t, 16> key1{}, key{};
    auto key1Bytes = unhex("0102030405060708090a0b0c0d0e0f10"),
         keyBytes = unhex("00112233445566778899aabbccddeeff");
    std::copy(key1Bytes.begin(), key1Bytes.end(), key1.begin());
    std::copy(keyBytes.begin(), keyBytes.end(), key.begin());
    gen3::CommonFrameCipher peer(key1, key, key, 1, 1);
    Bytes encrypted, decoded;
    peer.encrypt(response(1, 4, 0x26, {0, 0}), &encrypted);
    auto buffered = call(session, "control", "{}", encrypted);
    require(contains(buffered, "gen3_encrypted_preverify_frame_buffered") &&
                !contains(buffered, "credentials_update"),
            "encrypted control arriving during provider verify is bounded and "
            "retained");
    auto verified = providerReply(
        session, "verify", id, "{\"sn\":\"SN123456789012\",\"verified\":true}");
    require(contains(verified, "gen3_preverify_control") &&
                !contains(verified, "credentials_update"),
            "provider proof drains old encrypted control without claiming "
            "prepared status");
    wire = drain(session, verified);
    require(peer.decrypt(wire, &decoded) && decoded == frame(9, 1, 3, {2}),
            "full verified session starts read-only preparation");
    peer.encrypt(response(1, 4, 0x26, {0, 0}), &encrypted);
    auto unsolicited = call(session, "control", "{}", encrypted);
    require(contains(unsolicited, "gen3_unmatched_encrypted_proof") &&
                !contains(unsolicited, "credentials_update"),
            "unsolicited encrypted notifications cannot promote candidate "
            "before matching business reply");
    peer.encrypt(response(9, 4, 3, {4, 0}), &encrypted);
    auto proof = call(session, "control", "{}", encrypted);
    require(contains(proof, "credentials_update") &&
                contains(proof, "\"encryptedProof\":true") &&
                contains(proof, "\"sensitive\":true"),
            "full candidate persisted only after provider and fresh vehicle "
            "encrypted proof");
    wire = drain(session, proof);
    std::string completed;
    for (size_t i = 1; i < prepareReads.size(); i++) {
      auto read = prepareReads[i];
      require(peer.decrypt(wire, &decoded) &&
                  decoded == frame(uint8_t(read[0]), 1, uint8_t(read[1]),
                                   {uint8_t(read[2])}),
              "full Gen3 auth uses the model-specific local preparation queue");
      Bytes payload(read[2], 0);
      if (read[1] == 0xb2) payload = {0, 8};
      if (read[1] == 0x10) payload = serial;
      require(peer.encrypt(response(uint8_t(read[0]), 4, uint8_t(read[1]), payload),
                           &encrypted),
              "full Gen3 preparation reply fixture");
      completed = call(session, "control", "{}", encrypted);
      wire = drain(session, completed);
    }
    require(contains(completed, "verified_read_only_preparation_complete") &&
                contains(completed, "\"prepared\":true") && wire.empty(),
            "full Gen3 auth completes local preparation without unsolicited config");
    wire = drain(session,
                 call(session, "command", "{\"name\":\"read_cast_config\"}"));
    require(peer.decrypt(wire, &decoded) &&
                decoded == frame(0x23, 0x82, 1, {1, 8, 0}),
            "full Gen3 auth reaches TFT config read after complete preparation");
  }
}
void kcpTests() {
  struct Collector {
    std::vector<Bytes> packets;
  };
  Collector sender, receiver;
  auto output = [](const char* b, int len, ikcpcb*, void* user) -> int {
    static_cast<Collector*>(user)->packets.emplace_back(b, b + len);
    return 0;
  };
  auto a = ikcp_create(3, &sender), b = ikcp_create(3, &receiver);
  ikcp_setoutput(a, output);
  ikcp_setoutput(b, output);
  ikcp_setmtu(a, 1400);
  ikcp_setmtu(b, 1400);
  ikcp_nodelay(a, 1, 10, 2, 1);
  ikcp_nodelay(b, 1, 10, 2, 1);
  Bytes payload(1400);
  for (size_t i = 0; i < payload.size(); i++) payload[i] = uint8_t(i);
  require(ikcp_send(a, reinterpret_cast<const char*>(payload.data()),
                    int(payload.size())) == int(payload.size()),
          "KCP enqueue");
  ikcp_update(a, 1000);
  require(sender.packets.size() == 2, "KCP 1400 RTP splits into two datagrams");
  for (auto& p : sender.packets)
    require(ikcp_input(b, reinterpret_cast<const char*>(p.data()),
                       long(p.size())) == 0,
            "KCP input");
  ikcp_update(b, 1010);
  Bytes out(2000);
  int n = ikcp_recv(b, reinterpret_cast<char*>(out.data()), int(out.size()));
  out.resize(n > 0 ? n : 0);
  require(out == payload, "KCP reassembly");
  for (auto& p : receiver.packets)
    require(ikcp_input(a, reinterpret_cast<const char*>(p.data()),
                       long(p.size())) == 0,
            "KCP ACK");
  require(ikcp_waitsnd(a) == 0, "KCP ACK releases bounded queue");
  ikcp_release(a);
  ikcp_release(b);
}


}  // namespace
int main(int argc, char** argv) {
  try {
    if (argc != 3)
      throw std::runtime_error("expected Gen2 and common-cipher fixture paths");
    cryptoVectors(argv[1]);
    commonCipherBoundaryVectors(argv[2]);
    rtpTests();
    bleRtpTests();
    const PreparationReads n3 = {{9, 3, 2}, {1, 0xb2, 2}, {1, 0x10, 14},
                                 {9, 0x28, 14}, {1, 0x37, 2},
                                 {1, 0x8a, 2}, {1, 0x8c, 2}};
    const PreparationReads mmax2 = {{9, 3, 2}, {1, 0xb2, 2}, {1, 0x10, 14},
                                    {9, 0x28, 14}, {1, 0x8a, 2}, {1, 0x8c, 2}};
    const auto mz = mzPreparationReads();
    sessionTests("14357", n3);
    sessionTests("14101", mmax2);
    for (int channel = 0; channel <= 2; channel++) sessionTests("14360", mz, channel);
    for (const std::string fault : {"source", "command", "index", "short",
                                   "long", "serial", "timeout"})
      sessionTests("14360", mz, 0, fault);
    sessionTests("99999", {});
    sessionTests("4360", {});
    kcpTests();
    kcpBacklogTests();
    gen3SessionTests("14357", n3);
    gen3SessionTests("14101", mmax2);
    gen3SessionTests("14360", mz);
    gen3FullSessionTests("14357", n3);
    gen3FullSessionTests("14360", mz);
    std::cout << "{\"status\":\"passed\",\"checks\":" << checks
              << ",\"gen2FrozenVectors\":53,\"mzPreparationReads\":6,"
                 "\"vehicleAcceptance\":false}"
              << std::endl;
    return 0;
  } catch (const std::exception& e) {
    std::cerr << "FAIL after " << checks << ": " << e.what() << std::endl;
    return 1;
  }
}
