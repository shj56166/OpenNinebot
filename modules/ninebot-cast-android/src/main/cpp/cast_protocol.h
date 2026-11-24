#pragma once
#include <array>
#include <cstdint>
#include <string>
#include <vector>

namespace motexa::cast {
using Bytes = std::vector<uint8_t>;
std::string hex(const Bytes& bytes);
Bytes unhex(const std::string& text);
Bytes frame(uint8_t target, uint8_t command, uint8_t index,
            const Bytes& payload = {});
struct Gen2Cipher {
  Bytes key1, key2, auth;
  uint32_t txCounter = 0;
  uint32_t rxCounter = 0;
  ~Gen2Cipher();
  Bytes encrypt(const Bytes& plain);
  // 0=success, 1=integrity/format failure, 2=replay, 3=counter exhausted.
  int decrypt(const Bytes& wire, Bytes* plain);
  void clear();
};
const Bytes& firmwareKey();
std::vector<Bytes> packetizeBle(const Bytes&, bool key, int attMtu,
                                uint32_t wallMs, uint32_t ssrc,
                                uint16_t* sequence);
std::vector<Bytes> packetizeH264(const Bytes&, bool key, uint32_t timestamp,
                                 uint32_t ssrc, uint16_t* sequence,
                                 bool proProfile, bool repeatKeyframe);
Bytes heartbeat(uint32_t index, uint32_t ssrc, bool proProfile);
Bytes normalizeAnnexB(const Bytes& source);
bool hasNal(const Bytes&, uint8_t type);
}  // namespace motexa::cast
