#include "cast_core.h"

#include <algorithm>
#include <chrono>
#include <cmath>
#include <cstring>
#include <limits>
#include <stdexcept>

#include "mbedtls/ctr_drbg.h"
#include "mbedtls/entropy.h"
#include "mbedtls/md5.h"
#include "mbedtls/platform_util.h"

namespace motexa::cast {
namespace {
using Json = std::unique_ptr<cJSON, decltype(&cJSON_Delete)>;
void wipeJsonStrings(cJSON* node) {
  for (auto value = node; value; value = value->next) {
    if (value->child) wipeJsonStrings(value->child);
    if (cJSON_IsString(value) && value->valuestring &&
        !(value->type & cJSON_IsReference))
      mbedtls_platform_zeroize(value->valuestring,
                               std::strlen(value->valuestring));
  }
}
void secureJsonDelete(cJSON* value) {
  wipeJsonStrings(value);
  cJSON_Delete(value);
}
Json parse(const std::string& s) {
  if (s.size() > 512 * 1024) throw std::invalid_argument("json_too_large");
  const char* end = nullptr;
  auto p = cJSON_ParseWithLengthOpts(s.c_str(), s.size() + 1, &end, 1);
  if (!p) throw std::invalid_argument("json_invalid");
  return Json(p, secureJsonDelete);
}
cJSON* item(cJSON* j, const char* k) {
  return cJSON_GetObjectItemCaseSensitive(j, k);
}
std::string str(cJSON* j, const char* k, const std::string& d = "") {
  auto v = item(j, k);
  return cJSON_IsString(v) ? v->valuestring : d;
}
int integer(cJSON* j, const char* k, int d) {
  auto v = item(j, k);
  if (cJSON_IsNumber(v) && std::isfinite(v->valuedouble) &&
      std::floor(v->valuedouble) == v->valuedouble &&
      v->valuedouble >= INT32_MIN && v->valuedouble <= INT32_MAX)
    return int(v->valuedouble);
  if (cJSON_IsString(v)) {
    try {
      size_t used = 0;
      int out = std::stoi(v->valuestring, &used);
      if (used == std::strlen(v->valuestring)) return out;
    } catch (...) {
    }
  }
  return d;
}
bool boolean(cJSON* j, const char* k, bool d = false) {
  auto v = item(j, k);
  return cJSON_IsBool(v) ? cJSON_IsTrue(v) : d;
}
void put(cJSON* j, const char* k, const std::string& s) {
  cJSON_AddStringToObject(j, k, s.c_str());
}
void put(cJSON* j, const char* k, const char* s) {
  cJSON_AddStringToObject(j, k, s);
}
void put(cJSON* j, const char* k, int64_t n) {
  cJSON_AddNumberToObject(j, k, double(n));
}
void put(cJSON* j, const char* k, bool b) { cJSON_AddBoolToObject(j, k, b); }
cJSON* typed(const char* type) {
  auto j = cJSON_CreateObject();
  cJSON_AddStringToObject(j, "type", type);
  return j;
}
int le16(const Bytes& b, size_t p) { return b[p] | b[p + 1] << 8; }
uint32_t le32(const Bytes& b, size_t p) {
  return uint32_t(b[p]) | uint32_t(b[p + 1]) << 8 | uint32_t(b[p + 2]) << 16 |
         uint32_t(b[p + 3]) << 24;
}
bool utf8(const std::string& s) {
  for (size_t p = 0; p < s.size();) {
    uint8_t c = s[p++];
    if (!c) return false;
    if (c < 128) continue;
    int n = c >= 0xc2 && c <= 0xdf   ? 1
            : c >= 0xe0 && c <= 0xef ? 2
            : c >= 0xf0 && c <= 0xf4 ? 3
                                     : -1;
    if (n < 0 || p + n > s.size()) return false;
    uint32_t code = c & ((1 << (6 - n)) - 1);
    for (int i = 0; i < n; i++) {
      uint8_t d = s[p++];
      if ((d & 0xc0) != 0x80) return false;
      code = (code << 6) | (d & 63);
    }
    if ((n == 1 && code < 128) || (n == 2 && code < 2048) ||
        (n == 3 && code < 65536) || code > 0x10ffff ||
        (code >= 0xd800 && code <= 0xdfff))
      return false;
  }
  return true;
}
uint32_t randomSsrc() {
  uint32_t value = 0;
  FILE* f = fopen("/dev/urandom", "rb");
  if (f) {
    bool ok = fread(&value, 1, sizeof(value), f) == sizeof(value);
    fclose(f);
    if (ok && value) return value;
  }
  return uint32_t(std::chrono::steady_clock::now().time_since_epoch().count());
}
bool verifiedModel(const std::string& id) {
  return id == "14357" || id == "14101" || id == "14360";
}
std::array<uint8_t, 8> randomNonce8() {
  mbedtls_entropy_context entropy;
  mbedtls_ctr_drbg_context random;
  mbedtls_entropy_init(&entropy);
  mbedtls_ctr_drbg_init(&random);
  static const unsigned char personal[] = "vehicle-cast-v23-challenge";
  std::array<uint8_t, 8> nonce{};
  int rc = mbedtls_ctr_drbg_seed(&random, mbedtls_entropy_func, &entropy,
                                 personal, sizeof(personal) - 1);
  if (!rc) rc = mbedtls_ctr_drbg_random(&random, nonce.data(), nonce.size());
  mbedtls_ctr_drbg_free(&random);
  mbedtls_entropy_free(&entropy);
  if (rc) throw std::runtime_error("gen3_random_failed");
  return nonce;
}
void wipe(std::string& value) {
  if (!value.empty()) mbedtls_platform_zeroize(value.data(), value.size());
  value.clear();
}
}  // namespace
Session::Session(const std::string& options, const std::string& credentials) {
  auto o = parse(options), c = parse(credentials);
  if (!cJSON_IsObject(o.get()) || !cJSON_IsObject(c.get()))
    throw std::invalid_argument("session_options");
  if (str(o.get(), "brand", "ninebot") != "ninebot")
    throw std::invalid_argument("brand_unsupported");
  configure(o.get(), true);
  if (authVersion_ == 3) {
    auto v3 = item(c.get(), "v3");
    if (!cJSON_IsObject(v3))
      throw std::invalid_argument("gen3_material_required");
    gen3Board_ = integer(o.get(), "authBoard", -1);
    auto key1 = unhex(str(o.get(), "authKey1Hex"));
    auto serial = str(v3, "sn");
    gen3ProviderAvailable_ = cJSON_IsObject(item(v3, "authApi"));
    if (gen3Board_ >= 0 && gen3Board_ <= 255 &&
        key1.size() == gen3Key1_.size() && !serial.empty() &&
        serial.size() <= 120) {
      std::copy(key1.begin(), key1.end(), gen3Key1_.begin());
      serial_ = Bytes(serial.begin(), serial.end());
      gen3Fast_ = std::make_unique<gen3::V23FastSession>(
          uint8_t(gen3Board_), gen3Key1_, serial, str(v3, "curAppEncHex"),
          str(v3, "nextAppEncHex"));
    }
    if (!key1.empty()) mbedtls_platform_zeroize(key1.data(), key1.size());
  } else {
    password_ = unhex(str(c.get(), "passwordHex"));
    if (!password_.empty() && password_.size() != 16)
      throw std::invalid_argument("password_length");
  }
  ssrc_ = randomSsrc();
}
Session::~Session() {
  if (actions_) cJSON_Delete(actions_);
  if (rawConfig_) cJSON_Delete(rawConfig_);
  if (kcp_) ikcp_release(kcp_);
  for (auto bytes : {&password_, &serial_})
    if (!bytes->empty()) mbedtls_platform_zeroize(bytes->data(), bytes->size());
  mbedtls_platform_zeroize(gen3Key1_.data(), gen3Key1_.size());
  wipe(gen3Candidate_);
}
void Session::action(cJSON* j) { cJSON_AddItemToArray(actions_, j); }
void Session::state(const std::string& s, const std::string& detail) {
  state_ = s;
  auto j = typed("state");
  put(j, "state", s);
  put(j, "detail", detail);
  put(j, "authenticated", authenticated_);
  put(j, "externalMode", externalMode_);
  put(j, "prepared", prepared_);
  action(j);
}
void Session::diagnostic(const std::string& code, int64_t value) {
  auto j = typed("diagnostic");
  put(j, "code", code);
  put(j, "value", value);
  action(j);
}
void Session::error(const std::string& code, bool fatal) {
  auto j = typed("error");
  put(j, "code", code);
  put(j, "fatal", fatal);
  action(j);
  if (fatal) {
    authenticated_ = false;
    prepared_ = false;
    modeReady_ = false;
    deadline_ = 0;
    controls_.clear();
    media_.clear();
    datagramPending_.clear();
    reads_.clear();
    cipher_.clear();
    gen3Fast_.reset();
    gen3Full_.reset();
    wipe(gen3Candidate_);
    gen3PreverifyFrames_.clear();
    gen3PreverifyBytes_ = 0;
    gen3ProviderId_.clear();
    gen3ProviderOperation_.clear();
    for (auto bytes : {&password_, &serial_}) {
      if (!bytes->empty())
        mbedtls_platform_zeroize(bytes->data(), bytes->size());
      bytes->clear();
    }
    mbedtls_platform_zeroize(gen3Key1_.data(), gen3Key1_.size());
    state("failed", code);
    closed_ = true;
  }
}
void Session::configure(cJSON* o, bool initial) {
  if (initial) {
    deviceName_ = str(o, "deviceName");
    profileId_ = str(o, "profileId", "encryption2_standard_nus");
    modelId_ = str(o, "modelId");
    authVersion_ = integer(o, "authVersion", 2);
    externalMode_ = boolean(o, "externalCastingEnabled");
    transport_ = str(o, "transport", "ble");
    wireProfile_ = str(o, "wireProfile", "official");
    if (profileId_ == "encryption2_mmax90_capture_nus" ||
        profileId_ == "encryption2_vcu_nus_settle")
      preTarget_ = authTarget_ = 9;
    else if (profileId_ != "encryption2_standard_nus" &&
             profileId_ != "encryption2_standard_nus_immediate" &&
             profileId_ != "encryption2_standard_auto")
      throw std::invalid_argument("unverified_control_profile");
  }
  if (item(o, "attMtu")) {
    int m = integer(o, "attMtu", 23);
    if (m < 23 || m > 517) throw std::invalid_argument("att_mtu_range");
    attMtu_ = m;
  }
  if (item(o, "controlWriteType")) {
    int type = integer(o, "controlWriteType", 0);
    if (type != 1 && type != 2)
      throw std::invalid_argument("control_write_type_invalid");
    controlWriteType_ = type;
  }
  if (item(o, "width")) width_ = integer(o, "width", 0);
  if (item(o, "height")) height_ = integer(o, "height", 0);
  if (item(o, "fps")) fps_ = integer(o, "fps", 10);
  if (item(o, "bitrate")) bitrate_ = integer(o, "bitrate", 200000);
  repeatKeyframe_ = boolean(o, "repeatKeyframe", repeatKeyframe_);
  if (transport_ != "ble" && transport_ != "udp" && transport_ != "kcp")
    throw std::invalid_argument("transport_unknown");
  if (wireProfile_ != "official" && wireProfile_ != "pro")
    throw std::invalid_argument("wire_profile_unknown");
  if (width_ < 0 || width_ > 4096 || height_ < 0 || height_ > 4096 ||
      fps_ < 1 || fps_ > 60 || bitrate_ < 1000 || bitrate_ > 50000000)
    throw std::invalid_argument("encoder_parameters_range");
  if (initial && externalMode_ &&
      (authVersion_ != 0 || transport_ == "ble" || wireProfile_ != "pro"))
    throw std::invalid_argument("external_mode_requires_pro_wifi");
  if (!initial) diagnostic("configured_att_mtu", attMtu_);
}
std::string Session::dispatch(const std::string& op,
                              const std::string& arguments, const Bytes& bytes,
                              bool flag, int64_t value, int64_t elapsed,
                              int64_t wall) {
  actions_ = cJSON_CreateArray();
  now_ = elapsed;
  wall_ = wall;
  try {
    auto args = parse(arguments);
    if (closed_) {
      error("session_closed", false);
    } else if (op == "start")
      start();
    else if (op == "configure") {
      if (modeReady_ || state_ == "cast_mode_ready" || state_ == "wait_mode")
        error("configure_while_casting", false);
      else
        configure(args.get());
    } else if (op == "command")
      command(args.get());
    else if (op == "control")
      acceptControl(bytes);
    else if (op == "datagram")
      datagram(bytes, flag);
    else if (op == "tick")
      tick();
    else if (op == "frame")
      submit(bytes, flag, value);
    else if (op == "transport_result")
      transportResult(args.get());
    else
      error("operation_unknown", false);
    if (!closed_) pumpGatt();
  } catch (const std::exception& e) {
    error(e.what());
  }
  char* encoded = cJSON_PrintUnformatted(actions_);
  std::string result = encoded ? encoded : "[]";
  if (encoded) mbedtls_platform_zeroize(encoded, std::strlen(encoded));
  cJSON_free(encoded);
  secureJsonDelete(actions_);
  actions_ = nullptr;
  return result;
}
void Session::start() {
  if (state_ != "idle") {
    error("session_already_started", false);
    return;
  }
  if (authVersion_ == 3) {
    if (!gen3Fast_)
      error("gen3_explicit_auth_board_key1_required");
    else
      startGen3Challenge();
    return;
  }
  if (authVersion_ == 0 && externalMode_) {
    channel_ = transport_ == "kcp" ? 2 : 1;
    codec_ = 1;
    state("external_mode_ready",
          "instrument_cast_mode_must_already_be_enabled");
    return;
  }
  if (authVersion_ != 2 || deviceName_.empty() || deviceName_.size() > 248) {
    error("unsupported_auth_or_missing_raw_device_name");
    return;
  }
  cipher_.key1 = Bytes(deviceName_.begin(), deviceName_.end());
  cipher_.key2 = firmwareKey();
  cipher_.txCounter = 0;
  cipher_.rxCounter = 0;
  deadline_ = now_ + 10000;
  state("wait_precomm", "nus_notifications_enabled_before_start");
  sendControl(frame(preTarget_, 0x5b, 0));
}
void Session::sendControl(const Bytes& plain) {
  if (authVersion_ == 3) {
    Bytes wire;
    if (plain.size() < 7) throw std::runtime_error("gen3_plain_frame_invalid");
    const Bytes payload(plain.begin() + 7, plain.end());
    bool accepted =
        gen3Full_
            ? gen3Full_->encryptTo(plain[4], plain[5], plain[6], payload, &wire)
            : gen3Fast_ && gen3Fast_->encryptTo(plain[4], plain[5], plain[6],
                                                payload, &wire);
    if (!accepted) throw std::runtime_error("gen3_encrypt_session_unavailable");
    sendWire(wire);
  } else
    sendWire(cipher_.encrypt(plain));
}
void Session::startGen3Challenge() {
  const auto nonce = randomNonce8();
  Bytes wire;
  if (wall_ <= 0 || wall_ / 1000 > UINT32_MAX) {
    error("gen3_challenge_time_invalid");
    return;
  }
  if (!gen3Fast_->challenge(uint32_t(wall_ / 1000), nonce, &wire)) {
    if (gen3ProviderAvailable_)
      startGen3Full();
    else
      error("gen3_current_or_next_key_required_full_auth_provider_needed");
    return;
  }
  deadline_ = now_ + 10000;
  state("wait_gen3_fast",
        "cached_key_6005_challenge_full_distribution_separate");
  sendWire(wire, true);
}
void Session::sendWire(const Bytes& wire, bool sensitive) {
  int chunk = authenticated_ ? attMtu_ - 3 : 20;
  for (size_t p = 0; p < wire.size(); p += chunk) {
    Tx t;
    t.channel = "control";
    t.writeType = controlWriteType_;
    t.sensitive = sensitive;
    t.bytes = Bytes(wire.begin() + p,
                    wire.begin() + std::min(wire.size(), p + chunk));
    controls_.push_back(std::move(t));
  }
}
void Session::emitTx(Tx t) {
  t.id = nextId_++;
  t.created = now_;
  auto j = typed("tx");
  put(j, "id", t.id);
  put(j, "channel", t.channel);
  put(j, "hex", hex(t.bytes));
  put(j, "writeType", int64_t(t.writeType));
  put(j, "bytes", int64_t(t.bytes.size()));
  put(j, "sensitive", t.sensitive);
  action(j);
  if (t.channel == "control" || t.channel == "ble_media")
    gattPending_ = std::move(t);
  else
    datagramPending_.emplace(t.id, std::move(t));
}
void Session::pumpGatt() {
  if (gattPending_.id) return;
  if (!controls_.empty()) {
    auto t = std::move(controls_.front());
    controls_.pop_front();
    emitTx(std::move(t));
  } else if (!media_.empty()) {
    auto t = std::move(media_.front());
    media_.pop_front();
    emitTx(std::move(t));
  }
}
void Session::acceptControl(const Bytes& fragment) {
  if (fragment.empty()) return;
  if (fragment.size() > 4096 ||
      controlBuffer_.size() + fragment.size() > 4096) {
    error("control_buffer_overflow");
    return;
  }
  controlBuffer_.insert(controlBuffer_.end(), fragment.begin(), fragment.end());
  while (controlBuffer_.size() >= 3 && !closed_) {
    if (controlBuffer_[0] != 0x5a || controlBuffer_[1] != 0xa5) {
      error("control_sync_mismatch");
      return;
    }
    size_t n = size_t(controlBuffer_[2]) +
               (authVersion_ == 3 && (state_ == "wait_gen3_fast" ||
                                      (gen3Full_ && !gen3Full_->cipherReady()))
                    ? 9
                    : 13);
    if (controlBuffer_.size() < n) return;
    Bytes wire(controlBuffer_.begin(), controlBuffer_.begin() + n);
    controlBuffer_.erase(controlBuffer_.begin(), controlBuffer_.begin() + n);
    if (authVersion_ == 3) {
      acceptGen3(wire);
      continue;
    }
    uint16_t counter = uint16_t(wire[n - 2] << 8 | wire[n - 1]);
    if ((state_ == "wait_precomm" && counter) ||
        (state_ != "wait_precomm" && !counter)) {
      error("counter_mode_downgrade_or_unexpected");
      return;
    }
    Bytes plain;
    int rc = cipher_.decrypt(wire, &plain);
    if (rc) {
      error(rc == 2 ? "replay_rejected" : "integrity_rejected");
      return;
    }
    if (plain.size() < 7 || plain[4] != 0x3e) {
      error("control_destination_invalid");
      return;
    }
    acceptFrame(plain);
  }
}
void Session::acceptGen3(const Bytes& wire) {
  if (gen3Full_ && !gen3Full_->established()) {
    if (gen3Full_->cipherReady()) {
      if (gen3PreverifyBytes_ + wire.size() > 4096 ||
          gen3PreverifyFrames_.size() >= 16) {
        error("gen3_preverify_control_buffer_full");
        return;
      }
      gen3PreverifyFrames_.push_back(wire);
      gen3PreverifyBytes_ += wire.size();
      diagnostic("gen3_encrypted_preverify_frame_buffered",
                 int64_t(wire.size()));
      return;
    }
    gen3FullResult(gen3Full_->accept(wire));
    return;
  }
  if (!gen3Fast_ && !gen3Full_) {
    error("gen3_session_unavailable");
    return;
  }
  if (state_ == "wait_gen3_fast") {
    auto result = gen3Fast_->accept(wire);
    switch (result.result) {
      case gen3::FastReply::Accepted:
        gen3KeyState_ = result;
        gen3KeyState_.removeNext = gen3KeyState_.removeNext || gen3RemoveNext_;
        gen3ProofPending_ = true;
        deadline_ = 0;
        state("auth_key_selected",
              "cached_key_6006_not_authenticated_until_encrypted_reply");
        beginPreparation(false);
        return;
      case gen3::FastReply::RetryCurrent:
        gen3RemoveNext_ = result.removeNext;
        diagnostic("gen3_next_key_rejected_retry_current", result.keyState);
        startGen3Challenge();
        return;
      case gen3::FastReply::NeedAuthentication:
        if (gen3ProviderAvailable_)
          startGen3Full();
        else
          error("gen3_full_auth_distribution_required");
        return;
      case gen3::FastReply::Ignored:
        diagnostic("gen3_unrelated_handshake_frame");
        return;
      case gen3::FastReply::SerialMismatch:
        error("gen3_serial_mismatch");
        return;
      default:
        error("gen3_fast_response_invalid");
        return;
    }
  }
  gen3::V23Message message;
  const bool decoded = gen3Full_ ? gen3Full_->decrypt(wire, &message)
                                 : gen3Fast_->decrypt(wire, &message);
  if (!decoded) {
    error("gen3_integrity_or_replay_rejected");
    return;
  }
  if ((gen3FullProofPending_ || gen3ProofPending_) &&
      (state_ != "wait_prepare" || preparation_.empty() ||
       message.board != preparation_.front().target || message.group != 4 ||
       message.index != preparation_.front().index ||
       message.payload.size() != size_t(preparation_.front().length))) {
    auto facts = typed("gen3_unmatched_encrypted_proof");
    put(facts, "source", int64_t(message.board));
    put(facts, "group", int64_t(message.group));
    put(facts, "index", int64_t(message.index));
    put(facts, "bytes", int64_t(message.payload.size()));
    action(facts);
    return;
  }
  if (gen3FullProofPending_) {
    gen3FullProofPending_ = false;
    authenticated_ = true;
    auto update = typed("credentials_update");
    put(update, "sensitive", true);
    put(update, "encryptedProof", true);
    put(update, "removeNext", true);
    auto delta = cJSON_CreateObject();
    put(delta, "sn", std::string(serial_.begin(), serial_.end()));
    put(delta, "curAppEncHex", gen3Candidate_);
    cJSON_AddItemToObject(update, "v3", delta);
    action(update);
    wipe(gen3Candidate_);
  }
  if (gen3ProofPending_) {
    gen3ProofPending_ = false;
    authenticated_ = true;
    auto facts = typed("gen3_key_state");
    put(facts, "keyState", int64_t(gen3KeyState_.keyState));
    put(facts, "promoteNext", gen3KeyState_.promoteNext);
    put(facts, "removeNext", gen3KeyState_.removeNext);
    put(facts, "backgroundRefreshSuggested",
        gen3KeyState_.backgroundRefreshSuggested);
    put(facts, "encryptedProof", true);
    action(facts);
  }
  Bytes plain{0x5a,          0xa5, uint8_t(message.payload.size()),
              message.board, 0x3e, message.group,
              message.index};
  plain.insert(plain.end(), message.payload.begin(), message.payload.end());
  acceptFrame(plain);
}
void Session::startGen3Full() {
  if (!gen3ProviderAvailable_ || gen3Board_ < 0 || serial_.empty()) {
    error("gen3_authorized_full_provider_required");
    return;
  }
  if (wall_ <= 0 || wall_ / 1000 > UINT32_MAX) {
    error("gen3_challenge_time_invalid");
    return;
  }
  gen3Fast_.reset();
  gen3Full_ = std::make_unique<gen3::V23FullSession>(
      uint8_t(gen3Board_), gen3Key1_,
      std::string(serial_.begin(), serial_.end()));
  gen3FullResult(gen3Full_->start(uint32_t(wall_ / 1000), randomNonce8()));
}
void Session::gen3FullResult(const gen3::FullResult& result) {
  switch (result.action) {
    case gen3::FullAction::Send:
      deadline_ = now_ + 10000;
      state("wait_gen3_full",
            "full_transaction_step_" + std::to_string(int(result.state)));
      sendWire(result.wire, true);
      return;
    case gen3::FullAction::NeedChallenge:
      if (wall_ <= 0 || wall_ / 1000 > UINT32_MAX) {
        error("gen3_challenge_time_invalid");
        return;
      }
      gen3FullResult(
          gen3Full_->challenge(uint32_t(wall_ / 1000), randomNonce8()));
      return;
    case gen3::FullAction::AwaitUserConfirmation:
      deadline_ = now_ + 60000;
      state("wait_gen3_vehicle_confirmation",
            "press_vehicle_confirmation_wait_6007");
      return;
    case gen3::FullAction::NeedProvider: {
      gen3ProviderOperation_ = result.request.operation;
      const auto requestNonce = randomNonce8();
      gen3ProviderId_ = "v3-" +
                        hex(Bytes(requestNonce.begin(), requestNonce.end())) +
                        "-" + std::to_string(nextId_++);
      auto request = cJSON_CreateObject();
      put(request, "sn", result.request.sn);
      put(request, "pn", result.request.pn);
      put(request, "useCase", int64_t(1));
      put(request, "version", std::string("3"));
      if (gen3ProviderOperation_ == "challenge") {
        auto levels = cJSON_CreateArray();
        for (auto level : result.request.levels)
          cJSON_AddItemToArray(levels, cJSON_CreateNumber(level));
        cJSON_AddItemToObject(request, "levels", levels);
      } else if (gen3ProviderOperation_ == "response") {
        put(request, "level", int64_t(result.request.level));
        put(request, "nonceHex", result.request.nonceHex);
        put(request, "deviceSignHex", result.request.deviceSignHex);
        if (!result.request.pkDeviceTempHex.empty())
          put(request, "pkDeviceTempHex", result.request.pkDeviceTempHex);
        if (!result.request.clientCertHex.empty())
          put(request, "clientCertHex", result.request.clientCertHex);
      } else if (gen3ProviderOperation_ == "verify") {
        put(request, "nonceHex", result.request.nonceHex);
        put(request, "deviceEncHex", result.request.deviceEncHex);
        put(request, "aadHex", result.request.aadHex);
      } else {
        cJSON_Delete(request);
        error("gen3_provider_operation_invalid");
        return;
      }
      deadline_ = now_ + 25000;
      state("wait_gen3_provider",
            "authorized_provider_" + gen3ProviderOperation_);
      auto event = typed("auth_provider");
      put(event, "operation", gen3ProviderOperation_);
      put(event, "requestId", gen3ProviderId_);
      put(event, "sensitive", true);
      cJSON_AddItemToObject(event, "request", request);
      action(event);
      return;
    }
    case gen3::FullAction::Established:
      while (!gen3PreverifyFrames_.empty()) {
        gen3::V23Message message;
        const bool valid =
            gen3Full_->decrypt(gen3PreverifyFrames_.front(), &message);
        gen3PreverifyFrames_.pop_front();
        if (!valid) {
          error("gen3_preverify_integrity_or_replay_rejected");
          return;
        }
        auto facts = typed("gen3_preverify_control");
        put(facts, "source", int64_t(message.board));
        put(facts, "group", int64_t(message.group));
        put(facts, "index", int64_t(message.index));
        put(facts, "bytes", int64_t(message.payload.size()));
        action(facts);
      }
      gen3PreverifyBytes_ = 0;
      gen3FullProofPending_ = true;
      deadline_ = 0;
      state("auth_key_selected",
            "provider_verified_waiting_encrypted_vehicle_reply");
      beginPreparation(false);
      return;
    case gen3::FullAction::Ignored:
      diagnostic("gen3_unrelated_full_auth_frame");
      return;
    case gen3::FullAction::InvalidFrame:
    case gen3::FullAction::InvalidPayload:
    case gen3::FullAction::Failed:
      error(result.error.empty() ? "gen3_full_auth_failed" : result.error);
      return;
  }
}
void Session::gen3ProviderResult(cJSON* request) {
  if (!gen3Full_ || gen3ProviderId_.empty() ||
      str(request, "requestId") != gen3ProviderId_ ||
      str(request, "operation") != gen3ProviderOperation_ ||
      state_ != "wait_gen3_provider") {
    diagnostic("gen3_stale_provider_result");
    return;
  }
  auto operation = gen3ProviderOperation_;
  gen3ProviderId_.clear();
  gen3ProviderOperation_.clear();
  if (deadline_ && now_ > deadline_) {
    error("gen3_provider_result_after_deadline");
    return;
  }
  if (!boolean(request, "success")) {
    error("gen3_authorized_provider_failed");
    return;
  }
  auto result = item(request, "result");
  if (!cJSON_IsObject(result) ||
      str(result, "sn") != std::string(serial_.begin(), serial_.end())) {
    error("gen3_provider_serial_binding_mismatch");
    return;
  }
  if (operation == "challenge") {
    auto levelField = item(result, "level");
    if (!cJSON_IsNumber(levelField) ||
        (levelField->valuedouble != 1 && levelField->valuedouble != 2 &&
         levelField->valuedouble != 4)) {
      error("gen3_provider_level_invalid");
      return;
    }
    const int level = int(levelField->valuedouble);
    gen3::FullChallengeMaterial material;
    material.level = uint8_t(level);
    material.nonceHex = str(result, "nonceHex");
    material.serverCertHex = str(result, "serverCertHex");
    material.pkCloudHex = str(result, "pkCloudHex");
    material.cloudSignHex = str(result, "cloudSignHex");
    gen3FullResult(gen3Full_->provideChallenge(material));
  } else if (operation == "response") {
    gen3::FullDistributionMaterial material;
    material.appEncHex = str(result, "appEncHex");
    material.deviceEncHex = str(result, "deviceEncHex");
    material.nonceHex = str(result, "nonceHex");
    material.aadHex = str(result, "aadHex");
    wipe(gen3Candidate_);
    gen3Candidate_ = material.appEncHex;
    gen3FullResult(gen3Full_->provideDistribution(material));
    wipe(material.appEncHex);
  } else if (operation == "verify") {
    gen3FullResult(gen3Full_->provideVerification(boolean(result, "verified")));
  }
}
void Session::acceptFrame(const Bytes& p) {
  int source = p[3], cmd = p[5], index = p[6];
  Bytes data(p.begin() + 7, p.end());
  auto raw = typed("control_frame");
  put(raw, "source", int64_t(source));
  put(raw, "command", int64_t(cmd));
  put(raw, "index", int64_t(index));
  put(raw, "payloadBytes", int64_t(data.size()));
  action(raw);
  if (state_ == "wait_precomm") {
    if (source != preTarget_ || cmd != 0x5b || data.size() < 30) {
      error("precomm_response_mismatch");
      return;
    }
    cipher_.auth = Bytes(data.begin(), data.begin() + 16);
    serial_ = Bytes(data.begin() + 16, data.begin() + 30);
    if (index == 0) {
      deadline_ = 0;
      state("unpaired", "vehicle_has_no_password_no_set_pwd_sent");
      return;
    }
    if (password_.empty()) {
      deadline_ = 0;
      state("credential_required", "existing_password_required");
      return;
    }
    cipher_.key1 = password_;
    cipher_.key2 = cipher_.auth;
    cipher_.txCounter = 1;
    cipher_.rxCounter = 0;
    deadline_ = now_ + 10000;
    state("wait_auth", "existing_password_authentication");
    sendControl(frame(authTarget_, 0x5d, 0, serial_));
    return;
  }
  if (state_ == "wait_auth") {
    if (source != authTarget_ || cmd != 0x5d || index != 1) {
      error("auth_response_rejected");
      return;
    }
    authenticated_ = true;
    deadline_ = 0;
    if (stopRequested_) {
      stopRequested_ = false;
      setMode(0);
      return;
    }
    state("authenticated",
          "vehicle_authentication_verified_preparation_not_assumed");
    return;
  }
  if (!authenticated_) {
    error("control_before_authentication");
    return;
  }
  if (state_ == "wait_prepare") {
    if (preparation_.empty() || source != preparation_.front().target ||
        cmd != 4 || index != preparation_.front().index ||
        data.size() != size_t(preparation_.front().length)) {
      error("preparation_read_response_mismatch");
      return;
    }
    auto facts = typed("preparation_read");
    put(facts, "target", int64_t(source));
    put(facts, "register", int64_t(index));
    put(facts, "bytes", int64_t(data.size()));
    action(facts);
    if ((source == 9 && index == 3 && !(le16(data, 0) & 4)) ||
        (source == 1 && index == 0xb2 && !(le16(data, 0) & 2048))) {
      auto reason = source == 9 ? "vehicle_command_condition_main_power_off"
                                : "vehicle_activation_not_verified";
      preparation_.clear();
      deadline_ = 0;
      prepared_ = false;
      state("authenticated", reason);
      error(reason, false);
      return;
    }
    if (source == 1 && index == 0x10 && !serial_.empty()) {
      Bytes sn = data, expected = serial_;
      while (!sn.empty() && sn.back() == 0) sn.pop_back();
      while (!expected.empty() && expected.back() == 0) expected.pop_back();
      if (sn != expected) {
        error("preparation_serial_mismatch");
        return;
      }
    }
    preparation_.pop_front();
    if (stopRequested_) {
      preparation_.clear();
      stopRequested_ = false;
      setMode(0);
    } else
      nextPreparation();
    return;
  }
  if (state_ == "wait_config_begin") {
    if (source != 0x23 || cmd != 0x82 || data.size() != 4) {
      error("config_begin_response_mismatch");
      return;
    }
    configLength_ = le32(data, 0);
    if (!configLength_ || configLength_ > 256 * 1024) {
      error("config_length_range");
      return;
    }
    configBytes_.clear();
    configBytes_.reserve(configLength_);
    configOffset_ = 0;
    configGroup_ = configPacket_ = 0;
    configPacketCount_ = int((configLength_ + 127) / 128);
    requestConfigGroup();
    return;
  }
  if (state_ == "wait_config_data") {
    if (source != 0x23 || cmd != 0x83 || index != configSmall_ ||
        data.empty() || data.size() > 128 ||
        data.size() > configLength_ - configOffset_) {
      error("config_data_sequence_or_size");
      return;
    }
    configBytes_.insert(configBytes_.end(), data.begin(), data.end());
    configOffset_ += data.size();
    ++configSmall_;
    ++configPacket_;
    deadline_ = now_ + 10000;
    if (configPacket_ == configPacketCount_) {
      if (configOffset_ != configLength_) {
        error("config_final_size_mismatch");
        return;
      }
      state("wait_config_verify", "request_raw_file_md5");
      sendControl(frame(0x23, 0x84, 0));
    } else if (configSmall_ == 8) {
      if (configGroup_ >= 255) {
        error("config_group_overflow");
        return;
      }
      ++configGroup_;
      requestConfigGroup();
    }
    return;
  }
  if (state_ == "wait_config_verify") {
    if (source != 0x23 || cmd != 0x84 || index != 0 || data.size() != 16) {
      error("config_md5_response_mismatch");
      return;
    }
    uint8_t digest[16];
    if (mbedtls_md5(configBytes_.data(), configBytes_.size(), digest)) {
      error("md5_failed");
      return;
    }
    uint8_t diff = 0;
    for (int i = 0; i < 16; i++) diff |= digest[i] ^ data[i];
    if (diff) {
      error("config_md5_mismatch");
      return;
    }
    size_t n = configBytes_.size();
    if (n && configBytes_[n - 1] == 0) --n;
    std::string text(configBytes_.begin(), configBytes_.begin() + n);
    if (!utf8(text)) {
      error("config_utf8_invalid");
      return;
    }
    auto cfg = parse(text);
    if (!cJSON_IsObject(cfg.get())) {
      error("config_not_object");
      return;
    }
    configureMedia(cfg.get());
    configVerified_ = true;
    deadline_ = 0;
    if (stopRequested_) {
      stopRequested_ = false;
      setMode(0);
      return;
    }
    state("authenticated", "cast_config_md5_verified");
    emitConfig();
    return;
  }
  if (state_ == "wait_mode") {
    if (source != 0x23 || cmd != 0x71 || index != 0) {
      error("mode_response_mismatch");
      return;
    }
    deadline_ = 0;
    const int acknowledgedMode = expectedMode_;
    expectedMode_ = -1;
    auto a = typed("mode_ack");
    put(a, "mode", int64_t(acknowledgedMode));
    action(a);
    if (stopRequested_ && acknowledgedMode != 0) {
      stopRequested_ = false;
      setMode(0);
      return;
    }
    stopRequested_ = false;
    if (acknowledgedMode == 2) {
      // Wi-Fi discovery may only become possible after this BLE reply.
      modeReady_ = channel_ == 0;
      state(modeReady_ ? "casting_ready" : "cast_mode_ready",
            "mode_ack_not_display_confirmation");
    } else {
      modeReady_ = false;
      state("authenticated", "stop_mode_ack");
    }
    return;
  }
  if (readTarget_ >= 0) {
    if (source == readTarget_ && cmd == 4 && index == readIndex_) {
      telemetry(p);
      readTarget_ = readIndex_ = -1;
      readLength_ = 0;
      deadline_ = 0;
      if (stopRequested_) {
        stopRequested_ = false;
        setMode(0);
        return;
      }
      state(modeReady_ ? "casting_ready" : "authenticated",
            "read_response_verified");
      nextRead();
    } else
      diagnostic("unmatched_control_response", cmd);
    return;
  }
  diagnostic("unsolicited_control_response", cmd);
}
void Session::beginConfig() {
  if (!verifiedModel(modelId_)) {
    error("cast_command_table_unverified_for_model", false);
    return;
  }
  configVerified_ = false;
  configBytes_.clear();
  deadline_ = now_ + 10000;
  state("wait_config_begin", "read_type1_file_length");
  sendControl(frame(0x23, 0x82, 1, {1, 8, 0}));
}
void Session::beginPreparation(bool readConfig) {
  prepared_ = false;
  if (!verifiedModel(modelId_)) {
    error("verify_config_unavailable_for_model", false);
    return;
  }
  // Frozen VerifyConfig for N3/Mmax2/Mz: require main power and activation,
  // then read identification once. Mz (600015500) has no N3 rInfoBool2/0x37.
  // This local, stricter preparation does not perform account HTTP checks.
  configAfterPreparation_ = readConfig;
  reads_.clear();
  preparation_ = {
      {9, 3, 2, 0}, {1, 0xb2, 2, 0}, {1, 0x10, 14, 0}, {9, 0x28, 14, 0}};
  if (modelId_ == "14357") preparation_.push_back({1, 0x37, 2, 0});
  preparation_.push_back({1, 0x8a, 2, 0});
  preparation_.push_back({1, 0x8c, 2, 0});
  auto facts = typed("preparation_contract");
  put(facts, "modelId", modelId_);
  put(facts, "scope", "local_read_only");
  put(facts, "accountBindingCheck", "not_performed");
  put(facts, "connectionInfoUpload", "not_performed");
  put(facts, "readCount", int64_t(preparation_.size()));
  action(facts);
  nextPreparation();
}
void Session::nextPreparation() {
  if (preparation_.empty()) {
    prepared_ = true;
    deadline_ = 0;
    state("authenticated", "verified_read_only_preparation_complete");
    if (configAfterPreparation_) {
      configAfterPreparation_ = false;
      beginConfig();
    }
    return;
  }
  const auto& read = preparation_.front();
  deadline_ = now_ + 3000;
  state("wait_prepare", "verify_config_read_" + std::to_string(read.target) +
                            "_" + std::to_string(read.index));
  sendControl(frame(uint8_t(read.target), 1, uint8_t(read.index),
                    {uint8_t(read.length)}));
}
void Session::requestConfigGroup() {
  configSmall_ = 0;
  deadline_ = now_ + 10000;
  state("wait_config_data", "read_type1_group_" + std::to_string(configGroup_));
  sendControl(frame(0x23, 0x83, uint8_t(configGroup_)));
}
void Session::configureMedia(cJSON* cfg) {
  std::string version = str(cfg, "version");
  try {
    size_t end = 0;
    int major = std::stoi(version, &end);
    if ((end == version.size() ||
         (end < version.size() && version[end] == '.')) &&
        major > 5)
      throw std::runtime_error("cast_config_version_unsupported");
  } catch (const std::invalid_argument&) {
  } catch (const std::out_of_range&) {
  }
  int route = integer(cfg, "rtpChannel", -1);
  if (route < 0 || route > 2) route = integer(cfg, "channel", -1);
  channel_ = route >= 0 && route <= 2 ? route : 0;
  codec_ = integer(cfg, "codecType", -1);
  if (codec_ < 0 || codec_ > 2) {
    auto name = str(cfg, "codecName");
    std::transform(name.begin(), name.end(), name.begin(),
                   [](unsigned char c) { return char(std::tolower(c)); });
    codec_ = name == "mpeg2"   ? 0
             : name == "h264"  ? 1
             : name == "mjpeg" ? 2
                               : -1;
  }
  width_ = item(cfg, "codecWidth") ? integer(cfg, "codecWidth", 0)
                                   : integer(cfg, "dimensionWidth", 0);
  height_ = item(cfg, "codecHeight") ? integer(cfg, "codecHeight", 0)
                                     : integer(cfg, "dimensionHeight", 0);
  fps_ = integer(cfg, "frameRate", 10);
  bitrate_ = integer(cfg, "bitRate", integer(cfg, "bitrate", 200000));
  if (width_ < 1 || width_ > 4096 || height_ < 1 || height_ > 4096 ||
      fps_ < 1 || fps_ > 60 || bitrate_ < 1000 || bitrate_ > 50000000)
    throw std::runtime_error("cast_config_encoder_parameters_invalid");
  transport_ = channel_ == 0 ? "ble" : channel_ == 1 ? "udp" : "kcp";
  if (rawConfig_) cJSON_Delete(rawConfig_);
  rawConfig_ = cJSON_Duplicate(cfg, 1);
}
void Session::emitConfig() {
  auto a = typed("config"), cfg = cJSON_CreateObject();
  put(cfg, "channel", int64_t(channel_));
  put(cfg, "codec", int64_t(codec_));
  put(cfg, "width", int64_t(width_));
  put(cfg, "height", int64_t(height_));
  put(cfg, "fps", int64_t(fps_));
  put(cfg, "bitrate", int64_t(bitrate_));
  if (rawConfig_)
    cJSON_AddItemToObject(cfg, "raw", cJSON_Duplicate(rawConfig_, 1));
  cJSON_AddItemToObject(a, "config", cfg);
  action(a);
}
void Session::setMode(int mode) {
  if (!verifiedModel(modelId_)) {
    error("cast_command_table_unverified_for_model", false);
    return;
  }
  expectedMode_ = mode;
  deadline_ = now_ + 10000;
  state("wait_mode", "request_mode_" + std::to_string(mode));
  sendControl(frame(0x23, 0x71, 0, {uint8_t(mode)}));
}
void Session::command(cJSON* request) {
  auto name = str(request, "name");
  auto args = item(request, "args");
  if (name == "auth_provider_result") {
    gen3ProviderResult(args);
    return;
  }
  if (name == "stop_read") {
    reads_.clear();
    continuousReads_ = false;
    diagnostic("vehicle_reads_stopped");
    return;
  }
  if (name == "stop_cast") {
    stopMedia();
    reads_.clear();
    continuousReads_ = false;
    if (externalMode_) {
      state("external_mode_ready", "local_media_stopped");
      return;
    }
    if (authenticated_ &&
        (state_ == "casting_ready" || state_ == "cast_mode_ready" || state_ == "authenticated"))
      setMode(0);
    else if (state_ == "wait_auth" || state_ == "wait_precomm" ||
             state_ == "wait_gen3_fast" || state_ == "wait_prepare" ||
             state_ == "wait_gen3_full" || state_ == "wait_gen3_provider" ||
             state_ == "wait_gen3_vehicle_confirmation" ||
             state_ == "wait_mode" || state_ == "wait_read" ||
             state_ == "wait_config_begin" || state_ == "wait_config_data" ||
             state_ == "wait_config_verify") {
      stopRequested_ = true;
      diagnostic("stop_pending_control_transaction");
    } else
      diagnostic("stop_no_active_cast_transaction");
    return;
  }
  if (externalMode_ && name == "start_cast") {
    if (state_ != "external_mode_ready" || width_ < 1 || height_ < 1) {
      error("external_mode_parameters_missing", false);
      return;
    }
    codec_ = 1;
    modeReady_ = true;
    firstPts_ = -1;
    waitForKeyframe_ = true;
    if (transport_ == "kcp") setupKcp(3);
    emitConfig();
    state("casting_ready", "external_mode_no_vehicle_authentication_claim");
    return;
  }
  if (!authenticated_) {
    error("vehicle_not_authenticated", false);
    return;
  }
  if (name == "start_wifi_media") {
    if (state_ != "cast_mode_ready") {
      error("wifi_cast_mode_not_ready", false);
      return;
    }
    if (transport_ == "kcp") setupKcp(wireProfile_ == "pro" ? 3 : 0);
    modeReady_ = true;
    state("casting_ready", "wifi_ready_after_mode_ack_not_display_confirmation");
    return;
  }
  if (state_ != "authenticated" && state_ != "casting_ready" &&
      state_ != "cast_mode_ready") {
    error("control_operation_busy", false);
    return;
  }
  if (name == "read_cast_config") {
    if (modeReady_ || state_ == "cast_mode_ready") {
      error("stop_cast_before_reconfigure", false);
      return;
    }
    reads_.clear();
    if (prepared_)
      beginConfig();
    else
      beginPreparation(true);
  } else if (name == "prepare_connection") {
    if (modeReady_ || state_ == "cast_mode_ready") {
      error("stop_cast_before_preparation", false);
      return;
    }
    beginPreparation(false);
  } else if (name == "start_cast") {
    if (modeReady_ || state_ == "cast_mode_ready") {
      error("cast_already_started", false);
      return;
    }
    if (!prepared_) {
      error("verified_connection_preparation_required", false);
      return;
    }
    if (!configVerified_) {
      error("verified_cast_config_required", false);
      return;
    }
    if ((channel_ == 0 && codec_ != 0) || (channel_ != 0 && codec_ != 1)) {
      error("codec_transport_unsupported_43_excluded", false);
      return;
    }
    if (channel_ == 0 && attMtu_ <= 30) {
      error("ble_media_mtu_unusable", false);
      return;
    }
    reads_.clear();
    firstPts_ = -1;
    waitForKeyframe_ = true;
    mpegHeaderSeen_ = false;
    setMode(2);
  } else if (name == "read_vehicle")
    readVehicle(args);
  else
    error("command_unknown", false);
}
void Session::readVehicle(cJSON* args) {
  reads_.clear();
  readCursor_ = 0;
  continuousReads_ = boolean(args, "continuous");
  reads_ = {{1, 0x26, 2, 500, now_},  {1, 0xbd, 2, 250, now_},
            {1, 0x5e, 2, 500, now_},  {1, 0xba, 2, 1000, now_},
            {1, 0xb5, 2, 2500, now_}, {1, 0x25, 2, 2500, now_},
            {1, 0xb9, 2, 2500, now_}, {1, 0xb7, 4, 5000, now_}};
  if (boolean(args, "bms")) {
    int board = integer(args, "bmsTarget", 7);
    if (board != 7 && board != 0x22) {
      error("bms_target_not_whitelisted", false);
      reads_.clear();
      return;
    }
    reads_.push_back({board, 0x34, 2, 2500, now_});
    reads_.push_back({board, 0x32, 2, 2500, now_});
    reads_.push_back({board, 0x40, 20, 5000, now_});
  }
  nextRead();
}
void Session::nextRead() {
  if (reads_.empty() || readTarget_ >= 0 || !authenticated_ ||
      (state_ != "authenticated" && state_ != "casting_ready"))
    return;
  for (size_t count = 0; count < reads_.size(); count++) {
    size_t i = (readCursor_ + count) % reads_.size();
    auto& r = reads_[i];
    if (r.due > now_) continue;
    readTarget_ = r.target;
    readIndex_ = r.index;
    readLength_ = r.length;
    readCursor_ = (i + 1) % reads_.size();
    r.due = continuousReads_ ? now_ + r.interval : INT64_MAX;
    deadline_ = now_ + 3000;
    state("wait_read", "whitelisted_read");
    sendControl(
        frame(uint8_t(r.target), 1, uint8_t(r.index), {uint8_t(r.length)}));
    return;
  }
  if (!continuousReads_) {
    reads_.clear();
    diagnostic("vehicle_read_pass_complete");
  }
}
void Session::telemetry(const Bytes& p) {
  Bytes b(p.begin() + 7, p.end());
  int target = p[3], reg = p[6];
  auto j = typed("telemetry");
  put(j, "target", int64_t(target));
  put(j, "register", int64_t(reg));
  put(j, "hex", hex(b));
  bool valid = b.size() >= size_t(readLength_);
  std::string field = "raw", unit = "";
  double value = 0;
  if (valid && target == 1) {
    switch (reg) {
      case 0x26:
        field = "speedKmh";
        unit = "km/h";
        value = le16(b, 0) * 0.1;
        break;
      case 0xbd:
        field = "powerW";
        unit = "W";
        value = int16_t(le16(b, 0));
        break;
      case 0x5e:
        field = "nosPercent";
        unit = "%";
        value = std::clamp(le16(b, 0) - 256, 0, 100);
        break;
      case 0xba:
        field = "runningTimeSec";
        unit = "s";
        value = le16(b, 0);
        break;
      case 0xb5:
        field = "batteryPercent";
        unit = "%";
        value = b[0];
        valid = value <= 100;
        break;
      case 0x25:
        field = "rangeLeftKm";
        unit = "km";
        value = le16(b, 0) * 0.1;
        break;
      case 0xb9:
        field = "tripMileageKm";
        unit = "km";
        value = le16(b, 0) * 0.1;
        break;
      case 0xb7:
        field = "mileageKm";
        unit = "km";
        value = le32(b, 0) * 0.001;
        break;
    }
  } else if (valid && target == 7) {
    if (reg == 0x34) {
      field = "voltageV";
      unit = "V";
      int raw = le16(b, 0);
      valid = raw >= 3000 && raw <= 8000;
      value = raw / 100.0;
    } else if (reg == 0x32) {
      field = "bmsSocPercent";
      unit = "%";
      value = le16(b, 0);
      valid = value <= 100;
    } else if (reg == 0x40) {
      field = "temperaturesC";
      unit = "C";
      auto values = cJSON_CreateArray();
      valid = b.size() == 20;
      for (size_t i = 0; i + 1 < b.size(); i += 2) {
        int raw = le16(b, i);
        if (raw < 2000 || raw > 7000) valid = false;
        cJSON_AddItemToArray(values, cJSON_CreateNumber(raw / 100.0));
      }
      cJSON_AddItemToObject(j, "values", values);
    }
  }
  put(j, "field", field);
  put(j, "unit", unit);
  put(j, "valid", valid);
  if (field != "raw" && field != "temperaturesC")
    cJSON_AddNumberToObject(j, "value", value);
  put(j, "receivedElapsedMs", now_);
  action(j);
}
void Session::setupKcp(uint32_t conv) {
  if (kcp_) ikcp_release(kcp_);
  kcpPending_.clear();
  kcp_ = ikcp_create(conv, this);
  if (!kcp_) throw std::runtime_error("kcp_allocation_failed");
  bool pro = wireProfile_ == "pro";
  ikcp_setoutput(kcp_, kcpOutput);
  ikcp_setmtu(kcp_, pro ? 1400 : 1444);
  ikcp_wndsize(kcp_, pro ? 256 : 10240, pro ? 256 : 10240);
  ikcp_nodelay(kcp_, pro ? 1 : 2, pro ? 10 : 100, 2, 1);
  kcp_->rx_minrto = 30;
  lastKcpProgress_ = now_;
  lastKcpWaiting_ = 0;
}
bool Session::inspectKcpBacklog() {
  if (!kcp_) return false;
  std::map<const IKCPSEG*, KcpPending> current;
  int64_t oldest = now_;
  uint64_t queued = 0;
  auto scan = [&](IQUEUEHEAD* head, bool sent) {
    for (auto node = head->next; node != head; node = node->next) {
      auto segment = iqueue_entry(node, IKCPSEG, node);
      auto found = kcpPending_.find(segment);
      KcpPending pending{now_, segment->len, segment->sn, segment->xmit, sent};
      if (found != kcpPending_.end()) pending.enqueued = found->second.enqueued;
      current.emplace(segment, pending);
      queued += segment->len;
      oldest = std::min(oldest, pending.enqueued);
      if (sent && (found == kcpPending_.end() ||
                   pending.transmissions != found->second.transmissions)) {
        auto facts = typed(pending.transmissions > 1 ? "kcp_retransmit"
                                                     : "kcp_segment_sent");
        put(facts, "sn", int64_t(segment->sn));
        put(facts, "bytes", int64_t(segment->len));
        put(facts, "xmit", int64_t(segment->xmit));
        put(facts, "rtoMs", int64_t(segment->rto));
        put(facts, "fastAck", int64_t(segment->fastack));
        put(facts, "ageMs", now_ - pending.enqueued);
        action(facts);
      }
    }
  };
  scan(&kcp_->snd_buf, true);
  scan(&kcp_->snd_queue, false);
  for (const auto& old : kcpPending_) {
    if (current.find(old.first) != current.end() || !old.second.sent) continue;
    auto facts = typed("kcp_segment_acked");
    put(facts, "sn", int64_t(old.second.sequence));
    put(facts, "bytes", int64_t(old.second.bytes));
    put(facts, "xmit", int64_t(old.second.transmissions));
    put(facts, "ageMs", now_ - old.second.enqueued);
    put(facts, "srttMs", int64_t(kcp_->rx_srtt));
    action(facts);
  }
  kcpPending_ = std::move(current);
  if (queued > 1024 * 1024 || (!kcpPending_.empty() && now_ - oldest > 1000)) {
    auto facts = typed("kcp_backlog_limit");
    put(facts, "bytes", int64_t(queued));
    put(facts, "oldestAgeMs", now_ - oldest);
    put(facts, "segments", int64_t(kcpPending_.size()));
    action(facts);
    stopMedia();
    error(queued > 1024 * 1024 ? "kcp_backlog_over_1mib"
                               : "kcp_oldest_unacked_over_1s");
    return false;
  }
  return true;
}
int Session::kcpOutput(const char* b, int size, ikcpcb*, void* user) {
  auto* s = static_cast<Session*>(user);
  s->outputDatagram(Bytes(b, b + size), "udp_media");
  return 0;
}
void Session::outputDatagram(const Bytes& b, const std::string& channel) {
  if (datagramPending_.size() >= 512) {
    error("udp_local_completion_backlog");
    return;
  }
  Tx t;
  t.channel = channel;
  t.bytes = b;
  emitTx(std::move(t));
}
void Session::datagram(const Bytes& bytes, bool control) {
  if (bytes.size() > 65507) {
    error("datagram_oversized", false);
    return;
  }
  if (control) {
    auto j = typed("control_feedback");
    put(j, "hex", hex(bytes));
    put(j, "bytes", int64_t(bytes.size()));
    bool pro = wireProfile_ == "pro";
    bool match = pro && bytes.size() == 20 && bytes[0] == 0x85 &&
                 bytes[1] == 0xcc && bytes[2] == 0 && bytes[3] == 4 &&
                 bytes[4] == 0 && bytes[5] == 0 && bytes[6] == 0 &&
                 bytes[7] == 1 && bytes[8] == 0 && bytes[9] == 0 &&
                 bytes[10] == 0 && bytes[11] == 0 && le32(bytes, 16) == 2;
    put(j, "recognizedProReply", match);
    if (match) {
      auto sent = heartbeatSent_.find(le32(bytes, 12));
      if (sent != heartbeatSent_.end()) {
        put(j, "rttMs", now_ - sent->second);
        heartbeatSent_.erase(sent);
      }
    }
    action(j);
    if (wireProfile_ == "official" && bytes.size() >= 16 &&
        (bytes[0] & 0xe0) == 0x80 && bytes[1] == 0xcc &&
        size_t((bytes[2] << 8 | bytes[3]) + 1) * 4 == bytes.size() &&
        bytes[8] == 'm' && bytes[9] == 'p' && bytes[10] == 'v' &&
        bytes[11] == 0) {
      int subtype = bytes[0] & 31;
      auto feedback = typed("official_feedback");
      put(feedback, "subtype", int64_t(subtype));
      if (subtype == 2 || subtype == 3 || subtype == 4)
        put(feedback,
            subtype == 2   ? "fps"
            : subtype == 3 ? "bitrate"
                           : "mode",
            int64_t(le32(bytes, 12)));
      if (subtype == 5) {
        put(feedback, "index", int64_t(le32(bytes, 12)));
        if (bytes.size() >= 20) put(feedback, "mode", int64_t(le32(bytes, 16)));
        if (bytes.size() >= 24) {
          uint32_t conv = le32(bytes, 20);
          put(feedback, "conv", int64_t(conv));
          if (transport_ == "kcp" && kcp_ && conv != kcp_->conv) {
            if (ikcp_waitsnd(kcp_) == 0) {
              setupKcp(conv);
              diagnostic("kcp_conv_from_feedback", conv);
            } else
              error("kcp_conv_changed_with_pending_media");
          }
        }
      }
      action(feedback);
    }
    return;
  }
  if (kcp_) {
    if (!inspectKcpBacklog()) return;
    int previous = ikcp_waitsnd(kcp_);
    int rc = ikcp_input(kcp_, reinterpret_cast<const char*>(bytes.data()),
                        long(bytes.size()));
    if (rc) {
      diagnostic("kcp_input_rejected", rc);
      return;
    }
    if (ikcp_waitsnd(kcp_) < previous) lastKcpProgress_ = now_;
    if (!inspectKcpBacklog()) return;
    Bytes receive(65536);
    while (ikcp_recv(kcp_, reinterpret_cast<char*>(receive.data()),
                     int(receive.size())) >= 0)
      diagnostic("kcp_inbound_message_consumed");
    ikcp_update(kcp_, uint32_t(now_));
    inspectKcpBacklog();
  }
}
void Session::submit(const Bytes& source, bool key, int64_t pts) {
  if (!modeReady_) {
    error(state_ == "cast_mode_ready" ? "wifi_transport_not_ready"
                                     : "mode_not_acknowledged", false);
    return;
  }
  if (kcp_ && !inspectKcpBacklog()) return;
  if (source.empty() || source.size() > 1024 * 1024) {
    error("frame_size_range", false);
    return;
  }
  if (waitForKeyframe_ && !key) {
    ++droppedFrames_;
    diagnostic("drop_waiting_for_keyframe", droppedFrames_);
    return;
  }
  if ((transport_ == "ble" &&
       (!media_.empty() || gattPending_.channel == "ble_media")) ||
      (kcp_ && ikcp_waitsnd(kcp_) > 256)) {
    ++droppedFrames_;
    waitForKeyframe_ = true;
    diagnostic("frame_dropped_transport_backpressure", droppedFrames_);
    auto j = typed("request_keyframe");
    action(j);
    return;
  }
  Bytes bytes = source;
  bool pro = wireProfile_ == "pro";
  uint32_t timestamp = uint32_t(wall_);
  if (codec_ == 1) {
    bytes = normalizeAnnexB(source);
    key = key || hasNal(bytes, 5);
    if (key && (!hasNal(bytes, 7) || !hasNal(bytes, 8))) {
      error("keyframe_missing_sps_pps", false);
      waitForKeyframe_ = true;
      return;
    }
    if (firstPts_ < 0) firstPts_ = pts;
    timestamp = pro ? uint32_t(std::max<int64_t>(0, (pts - firstPts_) / 1000))
                    : uint32_t(wall_);
  } else if (codec_ == 0) {
    for (size_t i = 0; i + 6 < bytes.size(); i++)
      if (bytes[i] == 0 && bytes[i + 1] == 0 && bytes[i + 2] == 1 &&
          bytes[i + 3] == 0xb3) {
        int w = bytes[i + 4] << 4 | bytes[i + 5] >> 4;
        int h = (bytes[i + 5] & 15) << 8 | bytes[i + 6];
        if (w != width_ || h != height_) {
          error("mpeg2_sequence_dimensions_mismatch");
          return;
        }
        mpegHeaderSeen_ = true;
      }
    if (!mpegHeaderSeen_) {
      error("mpeg2_initial_sequence_header_required", false);
      return;
    }
  } else {
    error("unsupported_media_codec", false);
    return;
  }
  if (transport_ == "ble" && bytes.size() > size_t(attMtu_ - 30) * 8192) {
    error("frame_fragment_limit", false);
    return;
  }
  size_t estimate = (bytes.size() + (pro ? 1379 : 1399)) / (pro ? 1380 : 1400);
  if (pro && key && repeatKeyframe_) estimate *= 2;
  if (kcp_ && size_t(ikcp_waitsnd(kcp_)) + estimate * 2 > 512) {
    ++droppedFrames_;
    waitForKeyframe_ = true;
    diagnostic("frame_dropped_kcp_capacity", droppedFrames_);
    action(typed("request_keyframe"));
    return;
  }
  auto packets =
      transport_ == "ble"
          ? packetizeBle(bytes, key, attMtu_, timestamp, ssrc_, &sequence_)
          : packetizeH264(bytes, key, timestamp, ssrc_, &sequence_, pro,
                          pro && repeatKeyframe_);
  if (packets.size() > 8192) {
    error("frame_fragment_limit", false);
    return;
  }
  waitForKeyframe_ = false;
  ++submittedFrames_;
  submittedBytes_ += bytes.size();
  if (transport_ == "ble") {
    for (auto& packet : packets) {
      Tx t;
      t.channel = "ble_media";
      t.bytes = std::move(packet);
      t.writeType = 1;
      media_.push_back(std::move(t));
    }
  } else if (kcp_) {
    if (ikcp_waitsnd(kcp_) == 0) lastKcpProgress_ = now_;
    for (auto& packet : packets) {
      if (ikcp_send(kcp_, reinterpret_cast<const char*>(packet.data()),
                    int(packet.size())) < 0) {
        error("kcp_send_failed");
        return;
      }
      if (!inspectKcpBacklog()) return;
    }
    ikcp_update(kcp_, uint32_t(now_));
    if (!inspectKcpBacklog()) return;
  } else
    for (auto& packet : packets) outputDatagram(packet, "udp_media");
  auto j = typed("frame_submitted");
  put(j, "frame", int64_t(submittedFrames_));
  put(j, "bytes", int64_t(bytes.size()));
  put(j, "packets", int64_t(packets.size()));
  put(j, "keyframe", key);
  put(j, "ptsUs", pts);
  put(j, "timestamp", int64_t(timestamp));
  put(j, "submittedBytes", int64_t(submittedBytes_));
  action(j);
}
void Session::transportResult(cJSON* args) {
  auto id = item(args, "id");
  if (!cJSON_IsNumber(id) || id->valuedouble < 1 ||
      id->valuedouble > 9007199254740991.0) {
    error("transport_result_id_invalid", false);
    return;
  }
  int64_t key = int64_t(id->valuedouble);
  int status = integer(args, "status", -1);
  if (gattPending_.id == key) {
    auto channel = gattPending_.channel;
    gattPending_ = Tx{};
    if (status) {
      error("gatt_write_failed_status_" + std::to_string(status));
      return;
    }
    ++completedPackets_;
    pumpGatt();
  } else {
    auto found = datagramPending_.find(key);
    if (found == datagramPending_.end()) {
      diagnostic("stale_transport_result", key);
      return;
    }
    datagramPending_.erase(found);
    if (status) {
      error("udp_send_failed_status_" + std::to_string(status));
      return;
    }
    ++completedPackets_;
  }
}
void Session::tick() {
  if (gattPending_.id && now_ - gattPending_.created > 6000) {
    error("gatt_write_callback_timeout");
    return;
  }
  for (auto& tx : datagramPending_)
    if (now_ - tx.second.created > 6000) {
      error("udp_send_callback_timeout");
      return;
    }
  if (deadline_ && now_ > deadline_) {
    if (readTarget_ >= 0) {
      auto j = typed("read_timeout");
      put(j, "target", int64_t(readTarget_));
      put(j, "register", int64_t(readIndex_));
      action(j);
      readTarget_ = readIndex_ = -1;
      deadline_ = 0;
      if (stopRequested_) {
        stopRequested_ = false;
        setMode(0);
        return;
      }
      state(modeReady_ ? "casting_ready" : "authenticated",
            "read_timeout_recorded");
    } else {
      error("stage_timeout_" + state_);
      return;
    }
  }
  if (modeReady_ && transport_ != "ble" && now_ - lastHeartbeat_ >= 1000) {
    lastHeartbeat_ = now_;
    heartbeatSent_[++heartbeatIndex_] = now_;
    while (heartbeatSent_.size() > 8)
      heartbeatSent_.erase(heartbeatSent_.begin());
    outputDatagram(heartbeat(heartbeatIndex_, ssrc_, wireProfile_ == "pro"),
                   "udp_control");
  }
  if (kcp_ && modeReady_) {
    if (!inspectKcpBacklog()) return;
    int waiting = ikcp_waitsnd(kcp_);
    if (waiting < lastKcpWaiting_) lastKcpProgress_ = now_;
    lastKcpWaiting_ = waiting;
    if (waiting && now_ - lastKcpProgress_ > 15000) {
      error("kcp_no_ack_progress_timeout");
      return;
    }
    ikcp_update(kcp_, uint32_t(now_));
    if (!inspectKcpBacklog()) return;
    if (kcp_->state == uint32_t(-1)) {
      error("kcp_dead_link");
      return;
    }
  }
  nextRead();
}
void Session::stopMedia() {
  const bool hadMedia = modeReady_ || kcp_ != nullptr;
  modeReady_ = false;
  media_.clear();
  waitForKeyframe_ = true;
  firstPts_ = -1;
  if (kcp_) {
    ikcp_release(kcp_);
    kcp_ = nullptr;
  }
  kcpPending_.clear();
  if (transport_ != "ble" && hadMedia)
    outputDatagram({0x80, 0xcb, 0, 0}, "udp_control");
  auto j = typed("media_summary");
  put(j, "submittedFrames", int64_t(submittedFrames_));
  put(j, "droppedFrames", int64_t(droppedFrames_));
  put(j, "submittedBytes", int64_t(submittedBytes_));
  put(j, "completedPackets", int64_t(completedPackets_));
  put(j, "displayConfirmed", false);
  action(j);
}
}  // namespace motexa::cast
