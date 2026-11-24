#include "cast_protocol.h"

#include <algorithm>
#include <cstring>
#include <stdexcept>

#include "mbedtls/aes.h"
#include "mbedtls/platform_util.h"
#include "mbedtls/sha1.h"

namespace motexa::cast {
namespace {
using Block = std::array<uint8_t, 16>;
void wipe(Bytes& v) {
  if (!v.empty()) mbedtls_platform_zeroize(v.data(), v.size());
  v.clear();
}
Block padded(const Bytes& bytes) {
  Block b{};
  std::copy_n(bytes.begin(), std::min<size_t>(16, bytes.size()), b.begin());
  return b;
}
Block derive(const Bytes& a, const Bytes& b) {
  std::array<uint8_t, 32> input{};
  auto aa = padded(a), bb = padded(b);
  std::copy(aa.begin(), aa.end(), input.begin());
  std::copy(bb.begin(), bb.end(), input.begin() + 16);
  std::array<uint8_t, 20> digest{};
  if (mbedtls_sha1(input.data(), input.size(), digest.data()))
    throw std::runtime_error("sha1_failed");
  Block k{};
  std::copy_n(digest.begin(), 16, k.begin());
  mbedtls_platform_zeroize(input.data(), input.size());
  mbedtls_platform_zeroize(digest.data(), digest.size());
  return k;
}
Block aes(const Block& key, const Block& in) {
  mbedtls_aes_context ctx;
  mbedtls_aes_init(&ctx);
  Block out{};
  int rc = mbedtls_aes_setkey_enc(&ctx, key.data(), 128);
  if (!rc)
    rc =
        mbedtls_aes_crypt_ecb(&ctx, MBEDTLS_AES_ENCRYPT, in.data(), out.data());
  mbedtls_aes_free(&ctx);
  if (rc) throw std::runtime_error("aes_failed");
  return out;
}
Block nonceBlock(uint32_t count, const Bytes& auth, uint8_t flag,
                 uint16_t last) {
  Block n{};
  n[0] = flag;
  n[1] = count >> 24;
  n[2] = count >> 16;
  n[3] = count >> 8;
  n[4] = count;
  std::copy_n(auth.begin(), std::min<size_t>(8, auth.size()), n.begin() + 5);
  n[14] = last >> 8;
  n[15] = last;
  return n;
}
Block tag(const Block& k, const Bytes& plain, uint32_t counter,
          const Bytes& auth) {
  auto x = aes(k, nonceBlock(counter, auth, 0x59, uint16_t(plain.size() - 3)));
  Block aad{};
  std::copy_n(plain.begin(), 3, aad.begin());
  for (int i = 0; i < 16; i++) aad[i] ^= x[i];
  x = aes(k, aad);
  for (size_t offset = 3; offset < plain.size(); offset += 16) {
    Block b{};
    size_t n = std::min<size_t>(16, plain.size() - offset);
    std::copy_n(plain.begin() + offset, n, b.begin());
    for (int i = 0; i < 16; i++) b[i] ^= x[i];
    x = aes(k, b);
  }
  return x;
}
Bytes ctr(const Block& key, const Bytes& input, uint32_t count,
          const Bytes& auth) {
  Bytes out(input.size());
  for (size_t p = 0; p < input.size(); p += 16) {
    auto stream = aes(key, nonceBlock(count, auth, 1, uint8_t(1 + p / 16)));
    for (size_t i = 0; i < std::min<size_t>(16, input.size() - p); i++)
      out[p + i] = input[p + i] ^ stream[i];
  }
  return out;
}
uint16_t checksum(const Bytes& b, size_t start = 0) {
  uint32_t sum = 0;
  for (size_t i = start; i < b.size(); i++) sum += b[i];
  return uint16_t(~sum);
}
void be16(Bytes& b, size_t o, uint16_t x) {
  b[o] = x >> 8;
  b[o + 1] = x;
}
void be32(Bytes& b, size_t o, uint32_t x) {
  b[o] = x >> 24;
  b[o + 1] = x >> 16;
  b[o + 2] = x >> 8;
  b[o + 3] = x;
}
void le32(Bytes& b, size_t o, uint32_t x) {
  b[o] = x;
  b[o + 1] = x >> 8;
  b[o + 2] = x >> 16;
  b[o + 3] = x >> 24;
}
}  // namespace
const Bytes& firmwareKey() {
  static const Bytes k = {0x97, 0xcf, 0xb8, 2,    0x84, 0x41, 0x43, 0xde,
                          0x56, 0,    0x2b, 0x3b, 0x34, 0x78, 0x0a, 0x5d};
  return k;
}
std::string hex(const Bytes& v) {
  static const char* h = "0123456789abcdef";
  std::string s(v.size() * 2, '0');
  for (size_t i = 0; i < v.size(); i++) {
    s[2 * i] = h[v[i] >> 4];
    s[2 * i + 1] = h[v[i] & 15];
  }
  return s;
}
Bytes unhex(const std::string& s) {
  if (s.size() % 2) throw std::invalid_argument("hex_length");
  Bytes b(s.size() / 2);
  auto digit = [](char c) -> int {
    if (c >= '0' && c <= '9') return c - '0';
    if (c >= 'a' && c <= 'f') return c - 'a' + 10;
    if (c >= 'A' && c <= 'F') return c - 'A' + 10;
    return -1;
  };
  for (size_t i = 0; i < b.size(); i++) {
    int a = digit(s[2 * i]), c = digit(s[2 * i + 1]);
    if (a < 0 || c < 0) throw std::invalid_argument("invalid_hex");
    b[i] = (a << 4) | c;
  }
  return b;
}
Bytes frame(uint8_t target, uint8_t cmd, uint8_t index, const Bytes& payload) {
  if (payload.size() > 255) throw std::invalid_argument("payload_length");
  Bytes b = {0x5a, 0xa5, uint8_t(payload.size()), 0x3e, target, cmd, index};
  b.insert(b.end(), payload.begin(), payload.end());
  return b;
}
Gen2Cipher::~Gen2Cipher() { clear(); }
void Gen2Cipher::clear() {
  wipe(key1);
  wipe(key2);
  wipe(auth);
  txCounter = rxCounter = 0;
}
Bytes Gen2Cipher::encrypt(const Bytes& plain) {
  if (plain.size() < 7 || plain[0] != 0x5a || plain[1] != 0xa5 ||
      plain.size() != size_t(7 + plain[2]))
    throw std::invalid_argument("plain_frame");
  auto key = derive(key1, key2);
  Bytes out(plain.begin(), plain.begin() + 3);
  Bytes body(plain.begin() + 3, plain.end());
  if (txCounter) {
    txCounter = std::max(txCounter, rxCounter);
    if (txCounter >= 65535) throw std::runtime_error("counter_exhausted");
    ++txCounter;
    auto t = tag(key, plain, txCounter, auth);
    auto a0 = aes(key, nonceBlock(txCounter, auth, 1, 0));
    auto encrypted = ctr(key, body, txCounter, auth);
    out.insert(out.end(), encrypted.begin(), encrypted.end());
    for (int i = 0; i < 4; i++) out.push_back(t[i] ^ a0[i]);
    out.push_back(txCounter >> 8);
    out.push_back(txCounter);
  } else {
    auto stream = aes(key, padded(firmwareKey()));
    uint16_t sum = checksum(body);
    for (size_t i = 0; i < body.size(); i++)
      out.push_back(body[i] ^ stream[i % 16]);
    out.insert(out.end(), {0, 0, uint8_t(sum), uint8_t(sum >> 8), 0, 0});
  }
  mbedtls_platform_zeroize(key.data(), key.size());
  return out;
}
int Gen2Cipher::decrypt(const Bytes& wire, Bytes* plain) {
  if (!plain || wire.size() < 13 || wire[0] != 0x5a || wire[1] != 0xa5 ||
      wire.size() != size_t(wire[2] + 13))
    return 1;
  auto key = derive(key1, key2);
  size_t tail = wire.size() - 6;
  uint32_t count = (wire[tail + 4] << 8) | wire[tail + 5];
  Bytes out(wire.begin(), wire.begin() + 3),
      body(wire.begin() + 3, wire.begin() + tail);
  int rc = 0;
  if (count) {
    if (count <= rxCounter) {
      mbedtls_platform_zeroize(key.data(), key.size());
      return 2;
    }
    auto decoded = ctr(key, body, count, auth);
    out.insert(out.end(), decoded.begin(), decoded.end());
    auto t = tag(key, out, count, auth),
         a0 = aes(key, nonceBlock(count, auth, 1, 0));
    uint8_t diff = 0;
    for (int i = 0; i < 4; i++) diff |= uint8_t(t[i] ^ a0[i] ^ wire[tail + i]);
    if (diff)
      rc = 1;
    else
      rxCounter = count;
  } else {
    auto stream = aes(key, padded(firmwareKey()));
    for (size_t i = 0; i < body.size(); i++)
      out.push_back(body[i] ^ stream[i % 16]);
    if (checksum(out, 3) != uint16_t(wire[tail + 2] | wire[tail + 3] << 8))
      rc = 1;
  }
  mbedtls_platform_zeroize(key.data(), key.size());
  if (!rc) *plain = std::move(out);
  return rc;
}
std::vector<Bytes> packetizeBle(const Bytes& f, bool key, int mtu,
                                uint32_t time, uint32_t ssrc, uint16_t* seq) {
  if (f.empty() || !seq || mtu <= 30 || mtu > 517)
    throw std::invalid_argument("media_mtu_unusable");
  std::vector<Bytes> out;
  size_t limit = mtu - 30;
  for (size_t p = 0; p < f.size(); p += limit) {
    size_t n = std::min(limit, f.size() - p);
    Bytes b(20 + n);
    b[0] = 0x90;
    b[1] = p + n == f.size() ? 0x80 : 0;
    be16(b, 2, (*seq)++);
    be32(b, 4, time);
    be32(b, 8, ssrc);
    be32(b, 12, 1);
    be32(b, 16, key ? 0x10000 : 0x20000);
    std::copy_n(f.begin() + p, n, b.begin() + 20);
    out.push_back(std::move(b));
  }
  return out;
}
std::vector<Bytes> packetizeH264(const Bytes& f, bool key, uint32_t time,
                                 uint32_t ssrc, uint16_t* seq, bool pro,
                                 bool repeat) {
  if (f.empty() || !seq) throw std::invalid_argument("empty_frame");
  std::vector<Bytes> out;
  size_t limit = pro ? 1380 : 1400;
  for (int copy = 0; copy < (key && repeat ? 2 : 1); copy++)
    for (size_t p = 0; p < f.size(); p += limit) {
      size_t n = std::min(limit, f.size() - p);
      bool last = p + n == f.size();
      Bytes b(20 + n);
      b[0] = 0x90;
      b[1] = 0x20 | (last ? 0x80 : 0);
      be16(b, 2, (*seq)++);
      be32(b, 4, time);
      be32(b, 8, pro ? 0 : ssrc);
      be32(b, 12, 1);
      if (pro) {
        b[17] = (key ? 1 : 2) | (p == 0 ? 0x10 : 0) | (last ? 8 : 0);
        uint16_t t = 0x400 | (time & 0x3ff);
        b[18] = t;
        b[19] = t >> 8;
      } else
        be32(b, 16, key ? 0x10000 : 0x20000);
      std::copy_n(f.begin() + p, n, b.begin() + 20);
      out.push_back(std::move(b));
    }
  return out;
}
Bytes heartbeat(uint32_t index, uint32_t ssrc, bool pro) {
  Bytes b(pro ? 20 : 16);
  b[0] = 0x85;
  b[1] = 0xcc;
  b[3] = pro ? 4 : 3;
  be32(b, 4, pro ? 0 : ssrc);
  if (pro) {
    le32(b, 12, index);
    b[16] = 2;
  } else {
    b[8] = 'm';
    b[9] = 'p';
    b[10] = 'v';
    le32(b, 12, index);
  }
  return b;
}
Bytes normalizeAnnexB(const Bytes& in) {
  if (in.empty()) throw std::invalid_argument("empty_frame");
  if (in.size() >= 4 && in[0] == 0 && in[1] == 0 &&
      (in[2] == 1 || (in[2] == 0 && in[3] == 1)))
    return in;
  Bytes out;
  for (size_t p = 0; p < in.size();) {
    if (in.size() - p < 4) throw std::invalid_argument("nal_length_truncated");
    uint32_t n = (uint32_t(in[p]) << 24) | (uint32_t(in[p + 1]) << 16) |
                 (uint32_t(in[p + 2]) << 8) | in[p + 3];
    p += 4;
    if (!n || n > in.size() - p)
      throw std::invalid_argument("nal_length_invalid");
    out.insert(out.end(), {0, 0, 0, 1});
    out.insert(out.end(), in.begin() + p, in.begin() + p + n);
    p += n;
  }
  return out;
}
bool hasNal(const Bytes& b, uint8_t type) {
  for (size_t i = 0; i + 3 < b.size(); i++)
    if (b[i] == 0 && b[i + 1] == 0) {
      if (b[i + 2] == 1 && (b[i + 3] & 31) == type) return true;
      if (i + 4 < b.size() && b[i + 2] == 0 && b[i + 3] == 1 &&
          (b[i + 4] & 31) == type)
        return true;
    }
  return false;
}
}  // namespace motexa::cast
