#include "ninebot_gen3.h"

#include <algorithm>
#include <array>
#include <limits>
#include <mbedtls/aes.h>
#include <mbedtls/ccm.h>
#include <mbedtls/platform_util.h>
#include <mbedtls/sha1.h>

namespace motexa::cast::gen3 {
namespace {

int hexNibble(uint8_t value) {
    if (value >= '0' && value <= '9') return value - '0';
    if (value >= 'a' && value <= 'f') return value - 'a' + 10;
    if (value >= 'A' && value <= 'F') return value - 'A' + 10;
    return -1;
}

constexpr std::array<uint8_t, 16> firmware{{0x97, 0xcf, 0xb8, 0x02,
    0x84, 0x41, 0x43, 0xde, 0x56, 0, 0x2b, 0x3b, 0x34, 0x78, 0x0a, 0x5d}};

size_t headerSize(const std::vector<uint8_t>& data) {
    if (data.size() < 3) return 0;
    if (data[0] == 0x5a && data[1] == 0xa5) return 3;
    if (data.size() >= 4 && data[0] == 0xaa && data[1] == 0x55) return 4;
    return 0;
}

bool validFrameLength(const std::vector<uint8_t>& data, size_t header,
                      bool encrypted) {
    const size_t tail = encrypted ? 6 : 0;
    const size_t fields = header == 4 ? 5 : 4;
    if (header == 0 || data.size() < header + fields + tail) return false;
    const size_t payload = header == 4 ?
        static_cast<size_t>(data[2]) | (static_cast<size_t>(data[3]) << 8) : data[2];
    // The SDK CTR block index is one byte; reject before reusing a mask.
    return data.size() == header + fields + payload + tail && fields + payload <= 4080;
}

struct Aes {
    mbedtls_aes_context context;
    explicit Aes(const std::array<uint8_t, 16>& key) {
        mbedtls_aes_init(&context);
        ok = mbedtls_aes_setkey_enc(&context, key.data(), 128) == 0;
    }
    ~Aes() { mbedtls_aes_free(&context); }
    bool block(const std::array<uint8_t, 16>& input,
               std::array<uint8_t, 16>* output) {
        return ok && mbedtls_aes_crypt_ecb(&context, MBEDTLS_AES_ENCRYPT,
                                         input.data(), output->data()) == 0;
    }
    bool ok;
};

std::array<uint8_t, 16> counterBlock(uint32_t counter,
    const std::array<uint8_t, 16>& auth, uint8_t index) {
    std::array<uint8_t, 16> result{};
    result[0] = 1;
    for (size_t i = 0; i < 4; ++i)
        result[1 + i] = static_cast<uint8_t>(counter >> (24 - 8 * i));
    std::copy(auth.begin(), auth.begin() + 8, result.begin() + 5);
    result[15] = index;
    return result;
}

bool authenticatedTag(Aes& cipher, const std::vector<uint8_t>& plain,
    size_t header, uint32_t counter, const std::array<uint8_t, 16>& auth,
    std::array<uint8_t, 4>* tag) {
    auto initial = counterBlock(counter, auth, 0);
    initial[0] = 0x59;
    const size_t body = plain.size() - header;
    initial[14] = static_cast<uint8_t>(body >> 8);
    initial[15] = static_cast<uint8_t>(body);
    std::array<uint8_t, 16> state{};
    if (!cipher.block(initial, &state)) return false;
    auto mix = [&cipher, &state](const uint8_t* bytes, size_t length) {
        for (size_t i = 0; i < length; ++i) state[i] ^= bytes[i];
        std::array<uint8_t, 16> result{};
        if (!cipher.block(state, &result)) return false;
        state = result;
        return true;
    };
    if (!mix(plain.data(), header)) return false;
    for (size_t off = header; off < plain.size(); off += 16)
        if (!mix(plain.data() + off, std::min<size_t>(16, plain.size() - off)))
            return false;
    auto zeroBlock = counterBlock(counter, auth, 0);
    std::array<uint8_t, 16> mask{};
    if (!cipher.block(zeroBlock, &mask)) return false;
    for (size_t i = 0; i < tag->size(); ++i) (*tag)[i] = state[i] ^ mask[i];
    mbedtls_platform_zeroize(state.data(), state.size());
    mbedtls_platform_zeroize(mask.data(), mask.size());
    return true;
}

bool xorBody(Aes& cipher, const std::vector<uint8_t>& input, size_t header,
    size_t bodySize, uint32_t counter, const std::array<uint8_t, 16>& auth,
    std::vector<uint8_t>* output) {
    for (size_t off = 0; off < bodySize; off += 16) {
        const auto block = counterBlock(counter, auth, static_cast<uint8_t>(off / 16 + 1));
        std::array<uint8_t, 16> mask{};
        if (!cipher.block(block, &mask)) return false;
        for (size_t i = 0; i < std::min<size_t>(16, bodySize - off); ++i)
            (*output)[header + off + i] = input[header + off + i] ^ mask[i];
        mbedtls_platform_zeroize(mask.data(), mask.size());
    }
    return true;
}

bool validKeyHex(const std::string& text) {
    return text.size() == 32 && std::all_of(text.begin(), text.end(),
        [](unsigned char value) { return hexNibble(value) >= 0; });
}

void wipeString(std::string* text) {
    if (!text->empty()) mbedtls_platform_zeroize(text->data(), text->size());
    text->clear();
}

bool validUtf8(const std::vector<uint8_t>& value) {
    size_t pos = 0;
    while (pos < value.size()) {
        const uint8_t first = value[pos++];
        if (first < 0x80) continue;
        size_t count = 0;
        uint32_t code = 0, minimum = 0;
        if (first >= 0xc2 && first <= 0xdf) { count = 1; code = first & 0x1f; minimum = 0x80; }
        else if (first >= 0xe0 && first <= 0xef) { count = 2; code = first & 0xf; minimum = 0x800; }
        else if (first >= 0xf0 && first <= 0xf4) { count = 3; code = first & 7; minimum = 0x10000; }
        else return false;
        if (count > value.size() - pos) return false;
        for (size_t i = 0; i < count; ++i) {
            const uint8_t next = value[pos++];
            if ((next & 0xc0) != 0x80) return false;
            code = (code << 6) | (next & 0x3f);
        }
        if (code < minimum || code > 0x10ffff || (code >= 0xd800 && code <= 0xdfff)) return false;
    }
    return true;
}

bool completeTlv8(const std::vector<uint8_t>& payload) {
    size_t pos = 0;
    while (pos < payload.size()) {
        if (payload.size() - pos < 2) return false;
        const size_t length = payload[pos + 1];
        pos += 2;
        if (length > payload.size() - pos) return false;
        pos += length;
    }
    return true;
}

bool parseV23Message(const std::vector<uint8_t>& bytes, V23Message* out) {
    if (out == nullptr || bytes.size() < 7 || bytes[0] != 0x5a || bytes[1] != 0xa5 ||
        bytes.size() != static_cast<size_t>(bytes[2]) + 7 || bytes[4] != 0x3e) return false;
    out->board = bytes[3];
    out->group = bytes[5];
    out->index = bytes[6];
    out->payload.assign(bytes.begin() + 7, bytes.end());
    return true;
}

}  // namespace

bool buildChallengePayload(const uint8_t* keyHex32, size_t keyLen,
                           uint32_t unixSeconds, const uint8_t* nonce8,
                           size_t nonceLen, std::vector<uint8_t>* out) {
    if (out == nullptr) return false;
    out->clear();
    if (keyHex32 == nullptr || keyLen != 32 || nonce8 == nullptr || nonceLen != 8)
        return false;
    std::array<uint8_t, 16> key{};
    for (size_t i = 0; i < key.size(); ++i) {
        const int high = hexNibble(keyHex32[2 * i]);
        const int low = hexNibble(keyHex32[2 * i + 1]);
        if (high < 0 || low < 0) {
            mbedtls_platform_zeroize(key.data(), key.size());
            return false;
        }
        key[i] = static_cast<uint8_t>((high << 4) | low);
    }

    std::array<uint8_t, 6> plain{{0x0c, 0x04,
        static_cast<uint8_t>(unixSeconds),
        static_cast<uint8_t>(unixSeconds >> 8),
        static_cast<uint8_t>(unixSeconds >> 16),
        static_cast<uint8_t>(unixSeconds >> 24)}};
    std::array<uint8_t, 34> encoded{};
    encoded[0] = 0x13;
    encoded[1] = 0x16;
    encoded[24] = 0x02;
    encoded[25] = 0x08;
    std::copy(nonce8, nonce8 + nonceLen, encoded.begin() + 26);

    mbedtls_ccm_context cipher;
    mbedtls_ccm_init(&cipher);
    int result = mbedtls_ccm_setkey(&cipher, MBEDTLS_CIPHER_ID_AES,
                                   key.data(), 128);
    if (result == 0) {
        result = mbedtls_ccm_encrypt_and_tag(&cipher, plain.size(), nonce8,
            nonceLen, nullptr, 0, plain.data(), encoded.data() + 2,
            encoded.data() + 8, 16);
    }
    mbedtls_ccm_free(&cipher);
    mbedtls_platform_zeroize(key.data(), key.size());
    mbedtls_platform_zeroize(plain.data(), plain.size());
    if (result == 0) out->assign(encoded.begin(), encoded.end());
    mbedtls_platform_zeroize(encoded.data(), encoded.size());
    return result == 0;
}

bool parseTlv8(const uint8_t* bytes, size_t length, std::vector<Tlv>* out) {
    if (out == nullptr) return false;
    out->clear();
    if ((bytes == nullptr && length != 0) || length > 65535) return false;
    size_t offset = 0;
    while (length - offset >= 2) {
        const uint8_t type = bytes[offset++];
        const size_t size = bytes[offset++];
        if (size > length - offset) break;
        auto found = std::find_if(out->begin(), out->end(),
            [type](const Tlv& entry) { return entry.type == type; });
        if (found == out->end()) {
            out->push_back(Tlv{type, {bytes + offset, bytes + offset + size}});
        } else {
            found->value.assign(bytes + offset, bytes + offset + size);
        }
        offset += size;
    }
    return true;
}

CommonFrameCipher::CommonFrameCipher(const std::array<uint8_t, 16>& key1,
    const std::array<uint8_t, 16>& key2, const std::array<uint8_t, 16>& auth,
    uint32_t txCounter, uint32_t rxCounter)
    : auth_(auth), txCounter_(txCounter), rxCounter_(rxCounter) {
    std::array<uint8_t, 32> source{};
    std::array<uint8_t, 20> digest{};
    std::copy(key1.begin(), key1.end(), source.begin());
    std::copy(key2.begin(), key2.end(), source.begin() + 16);
    valid_ = mbedtls_sha1(source.data(), source.size(), digest.data()) == 0;
    if (valid_) std::copy(digest.begin(), digest.begin() + 16, key_.begin());
    mbedtls_platform_zeroize(source.data(), source.size());
    mbedtls_platform_zeroize(digest.data(), digest.size());
}

CommonFrameCipher::~CommonFrameCipher() {
    mbedtls_platform_zeroize(key_.data(), key_.size());
    mbedtls_platform_zeroize(auth_.data(), auth_.size());
    txCounter_ = rxCounter_ = 0;
    valid_ = false;
}

bool CommonFrameCipher::encrypt(const std::vector<uint8_t>& plain,
                                std::vector<uint8_t>* output) {
    if (output == nullptr) return false;
    output->clear();
    const size_t header = headerSize(plain);
    if (!valid_ || !validFrameLength(plain, header, false)) return false;
    Aes aes(key_);
    if (!aes.ok) return false;
    std::vector<uint8_t> result(plain.size() + 6);
    std::copy(plain.begin(), plain.begin() + header, result.begin());
    const size_t body = plain.size() - header;
    uint32_t nextCounter = txCounter_;
    if (nextCounter > 0) {
        nextCounter = std::max(nextCounter, rxCounter_);
        if (nextCounter == std::numeric_limits<uint32_t>::max()) return false;
        ++nextCounter;
        std::array<uint8_t, 4> tag{};
        if (!authenticatedTag(aes, plain, header, nextCounter, auth_, &tag) ||
            !xorBody(aes, plain, header, body, nextCounter, auth_, &result)) return false;
        std::copy(tag.begin(), tag.end(), result.begin() + plain.size());
        result[result.size() - 2] = static_cast<uint8_t>(nextCounter >> 8);
        result[result.size() - 1] = static_cast<uint8_t>(nextCounter);
    } else {
        std::array<uint8_t, 16> mask{};
        if (!aes.block(firmware, &mask)) return false;
        uint32_t sum = 0;
        for (size_t i = 0; i < body; ++i) {
            const uint8_t value = plain[header + i];
            sum += value;
            result[header + i] = value ^ mask[i % mask.size()];
        }
        const uint16_t checksum = static_cast<uint16_t>(~sum);
        result[result.size() - 4] = static_cast<uint8_t>(checksum);
        result[result.size() - 3] = static_cast<uint8_t>(checksum >> 8);
        mbedtls_platform_zeroize(mask.data(), mask.size());
    }
    txCounter_ = nextCounter;
    output->swap(result);
    return true;
}

bool CommonFrameCipher::decrypt(const std::vector<uint8_t>& input,
                                std::vector<uint8_t>* output) {
    if (output == nullptr) return false;
    output->clear();
    const size_t header = headerSize(input);
    if (!valid_ || !validFrameLength(input, header, true)) return false;
    const uint16_t lowCounter = static_cast<uint16_t>(input[input.size() - 2] << 8) |
                                input[input.size() - 1];
    uint32_t received = (rxCounter_ & 0xffff0000U) | lowCounter;
    if ((rxCounter_ & 0x8000U) && !(lowCounter & 0x8000U)) {
        if ((rxCounter_ & 0xffff0000U) == 0xffff0000U) return false;
        received += 0x10000U;
    }
    if (received != 0 && received <= rxCounter_) return false;
    // Never accept an unsequenced downgrade once an authenticated direction began.
    if (received == 0 && (rxCounter_ != 0 || txCounter_ != 0)) return false;
    Aes aes(key_);
    if (!aes.ok) return false;
    std::vector<uint8_t> result(input.begin(), input.end() - 6);
    const size_t body = result.size() - header;
    if (received == 0) {
        std::array<uint8_t, 16> mask{};
        if (!aes.block(firmware, &mask)) return false;
        uint32_t sum = 0;
        for (size_t i = 0; i < body; ++i) {
            result[header + i] ^= mask[i % mask.size()];
            sum += result[header + i];
        }
        mbedtls_platform_zeroize(mask.data(), mask.size());
        const uint16_t checksum = static_cast<uint16_t>(~sum);
        if (input[input.size() - 4] != static_cast<uint8_t>(checksum) ||
            input[input.size() - 3] != static_cast<uint8_t>(checksum >> 8)) return false;
    } else {
        if (!xorBody(aes, input, header, body, received, auth_, &result)) return false;
        std::array<uint8_t, 4> expected{};
        if (!authenticatedTag(aes, result, header, received, auth_, &expected)) return false;
        uint8_t difference = 0;
        for (size_t i = 0; i < expected.size(); ++i)
            difference |= expected[i] ^ input[result.size() + i];
        if (difference != 0) return false;
    }
    rxCounter_ = received;
    output->swap(result);
    return true;
}

bool encodeV23Plain(const V23Message& message, std::vector<uint8_t>* out) {
    if (out == nullptr) return false;
    out->clear();
    if (message.payload.size() > 255) return false;
    out->reserve(message.payload.size() + 9);
    *out = {0x5a, 0xa5, static_cast<uint8_t>(message.payload.size()), 0x3e,
            message.board, message.group, message.index};
    out->insert(out->end(), message.payload.begin(), message.payload.end());
    uint16_t checksum = 0;
    for (size_t i = 2; i < out->size(); ++i) checksum = static_cast<uint16_t>(checksum + (*out)[i]);
    checksum = static_cast<uint16_t>(~checksum);
    out->push_back(static_cast<uint8_t>(checksum));
    out->push_back(static_cast<uint8_t>(checksum >> 8));
    return true;
}

bool decodeV23Plain(const std::vector<uint8_t>& wire, V23Message* out) {
    if (out == nullptr || wire.size() < 9 || wire[0] != 0x5a || wire[1] != 0xa5 ||
        wire.size() != static_cast<size_t>(wire[2]) + 9 || wire[4] != 0x3e) return false;
    uint16_t sum = 0;
    for (size_t i = 2; i < wire.size() - 2; ++i) sum = static_cast<uint16_t>(sum + wire[i]);
    const uint16_t checksum = static_cast<uint16_t>(~sum);
    if (wire[wire.size() - 2] != static_cast<uint8_t>(checksum) ||
        wire.back() != static_cast<uint8_t>(checksum >> 8)) return false;
    return parseV23Message(std::vector<uint8_t>(wire.begin(), wire.end() - 2), out);
}

V23FastSession::V23FastSession(uint8_t board, const std::array<uint8_t, 16>& key1,
    std::string serial, std::string current, std::string next)
    : board_(board), key1_(key1), serial_(std::move(serial)),
      current_(std::move(current)), next_(std::move(next)) {
    if (!validKeyHex(current_)) wipeString(&current_);
    if (!validKeyHex(next_)) wipeString(&next_);
    selectedNext_ = !next_.empty();
}

V23FastSession::~V23FastSession() {
    cipher_.reset();
    mbedtls_platform_zeroize(key1_.data(), key1_.size());
    wipeString(&current_);
    wipeString(&next_);
}

bool V23FastSession::challenge(uint32_t seconds, const std::array<uint8_t, 8>& nonce,
                             std::vector<uint8_t>* wire) {
    if (wire == nullptr) return false;
    wire->clear();
    const std::string& key = selectedNext_ ? next_ : current_;
    if (cipher_ != nullptr || serial_.empty() || !validKeyHex(key)) return false;
    V23Message message;
    message.board = board_;
    message.group = 0x60;
    message.index = 5;
    if (!buildChallengePayload(reinterpret_cast<const uint8_t*>(key.data()), key.size(),
        seconds, nonce.data(), nonce.size(), &message.payload) || !encodeV23Plain(message, wire)) return false;
    challengePending_ = true;
    return true;
}

FastReplyResult V23FastSession::accept(const std::vector<uint8_t>& wire) {
    FastReplyResult result;
    if (!challengePending_ || cipher_ != nullptr) return result;
    V23Message message;
    if (!decodeV23Plain(wire, &message)) {
        result.result = FastReply::InvalidFrame;
        return result;
    }
    if (message.board != board_ || message.group != 0x60 || message.index != 6) return result;
    std::vector<Tlv> entries;
    if (!completeTlv8(message.payload) || !parseTlv8(message.payload.data(), message.payload.size(), &entries)) {
        result.result = FastReply::InvalidPayload;
        return result;
    }
    const Tlv* keyState = nullptr;
    const Tlv* serial = nullptr;
    for (const auto& entry : entries) {
        if (entry.type == 0x0f) keyState = &entry;
        else if (entry.type == 0x14) serial = &entry;
    }
    if (keyState == nullptr || keyState->value.size() != 1 || serial == nullptr ||
        serial->value.empty() || !validUtf8(serial->value)) {
        result.result = FastReply::InvalidPayload;
        return result;
    }
    if (std::string(serial->value.begin(), serial->value.end()) != serial_) {
        result.result = FastReply::SerialMismatch;
        return result;
    }
    result.keyState = keyState->value[0];
    switch (result.keyState) {
        case 0:
        case 1: {
            const std::string& keyHex = selectedNext_ ? next_ : current_;
            std::array<uint8_t, 16> key{};
            for (size_t i = 0; i < key.size(); ++i)
                key[i] = static_cast<uint8_t>((hexNibble(keyHex[2 * i]) << 4) | hexNibble(keyHex[2 * i + 1]));
            cipher_ = std::make_unique<CommonFrameCipher>(key1_, key, key, 1, 1);
            mbedtls_platform_zeroize(key.data(), key.size());
            challengePending_ = false;
            result.result = FastReply::Accepted;
            result.promoteNext = selectedNext_;
            // Caller must persist current successfully before removing next.
            result.removeNext = false;
            result.backgroundRefreshSuggested = result.keyState == 1;
            return result;
        }
        case 2:
        case 4:
            challengePending_ = false;
            if (selectedNext_ && !current_.empty()) {
                selectedNext_ = false;
                result.result = FastReply::RetryCurrent;
                result.removeNext = true;
            } else result.result = FastReply::NeedAuthentication;
            return result;
        case 3:
            challengePending_ = false;
            result.result = FastReply::NeedAuthentication;
            return result;
        default:
            challengePending_ = false;
            result.result = FastReply::InvalidKeyState;
            return result;
    }
}

bool V23FastSession::encrypt(uint8_t group, uint8_t index,
    const std::vector<uint8_t>& payload, std::vector<uint8_t>* wire) {
    return encryptTo(board_, group, index, payload, wire);
}

bool V23FastSession::encryptTo(uint8_t board, uint8_t group, uint8_t index,
    const std::vector<uint8_t>& payload, std::vector<uint8_t>* wire) {
    if (wire == nullptr) return false;
    wire->clear();
    if (!cipher_ || payload.size() > 255) return false;
    std::vector<uint8_t> frame{0x5a, 0xa5, static_cast<uint8_t>(payload.size()),
        0x3e, board, group, index};
    frame.insert(frame.end(), payload.begin(), payload.end());
    return cipher_->encrypt(frame, wire);
}

bool V23FastSession::decrypt(const std::vector<uint8_t>& wire, V23Message* message) {
    if (message == nullptr || !cipher_) return false;
    std::vector<uint8_t> plain;
    return cipher_->decrypt(wire, &plain) && parseV23Message(plain, message);
}

namespace {

bool decodeHexBytes(const std::string& text, std::vector<uint8_t>* bytes) {
    bytes->clear();
    if (text.size() % 2 != 0 || text.size() > 510) return false;
    bytes->reserve(text.size() / 2);
    for (size_t pos = 0; pos < text.size(); pos += 2) {
        const int high = hexNibble(text[pos]), low = hexNibble(text[pos + 1]);
        if (high < 0 || low < 0) { bytes->clear(); return false; }
        bytes->push_back(static_cast<uint8_t>((high << 4) | low));
    }
    return true;
}

std::string upperHex(const std::vector<uint8_t>& bytes) {
    constexpr char digits[] = "0123456789ABCDEF";
    std::string result(bytes.size() * 2, '0');
    for (size_t i = 0; i < bytes.size(); ++i) {
        result[2 * i] = digits[bytes[i] >> 4];
        result[2 * i + 1] = digits[bytes[i] & 15];
    }
    return result;
}

bool appendTlv(std::vector<uint8_t>* payload, uint8_t type,
               const std::vector<uint8_t>& value) {
    if (value.size() > 255 || payload->size() + value.size() + 2 > 255) return false;
    payload->push_back(type);
    payload->push_back(static_cast<uint8_t>(value.size()));
    payload->insert(payload->end(), value.begin(), value.end());
    return true;
}

const std::vector<uint8_t>* findTlv(const std::vector<Tlv>& tlvs, uint8_t type) {
    auto item = std::find_if(tlvs.begin(), tlvs.end(), [type](const Tlv& tlv) {
        return tlv.type == type;
    });
    return item == tlvs.end() ? nullptr : &item->value;
}

bool tlvText(const std::vector<Tlv>& tlvs, uint8_t type, std::string* text) {
    const auto* bytes = findTlv(tlvs, type);
    if (bytes == nullptr || bytes->empty() || !validUtf8(*bytes)) return false;
    text->assign(bytes->begin(), bytes->end());
    return true;
}

}  // namespace

V23FullSession::V23FullSession(uint8_t board, const std::array<uint8_t, 16>& key1,
                             std::string serialNumber)
    : board_(board), key1_(key1), serial_(std::move(serialNumber)) {}

V23FullSession::~V23FullSession() {
    cipher_.reset();
    mbedtls_platform_zeroize(key1_.data(), key1_.size());
    wipeString(&appKey_);
    wipeString(&distributionNonce_);
    wipeString(&serial_);
    wipeString(&pn_);
}

FullResult V23FullSession::result(FullAction action, const char* error) const {
    FullResult result;
    result.action = action;
    result.state = state_;
    result.keyState = lastKeyState_;
    result.error = error;
    return result;
}

FullResult V23FullSession::fail(const char* error) {
    state_ = FullState::Failed;
    cipher_.reset();
    wipeString(&appKey_);
    wipeString(&distributionNonce_);
    return result(FullAction::Failed, error);
}

FullResult V23FullSession::send(uint8_t index, const std::vector<uint8_t>& payload,
                              FullState state) {
    auto output = result(FullAction::Send);
    if (!encodeV23Plain({board_, 0x60, index, payload}, &output.wire))
        return fail("gen3_full_frame_size");
    state_ = state;
    output.state = state_;
    return output;
}

FullResult V23FullSession::start(uint32_t seconds, const std::array<uint8_t, 8>& nonce) {
    if (state_ != FullState::Idle || serial_.empty())
        return fail("gen3_full_start_state");
    // Original initial challenge Vec is sixteen zero bytes (0x473a98).
    const std::string initialKey(32, '0');
    std::vector<uint8_t> payload;
    if (!buildChallengePayload(reinterpret_cast<const uint8_t*>(initialKey.data()),
            initialKey.size(), seconds, nonce.data(), nonce.size(), &payload))
        return fail("gen3_full_initial_challenge");
    return send(5, payload, FullState::InitialChallengeSent);
}

FullResult V23FullSession::requestChallenge() {
    state_ = FullState::ChallengeRequested;
    auto output = result(FullAction::NeedProvider);
    output.request.operation = "challenge";
    output.request.sn = serial_;
    output.request.pn = pn_;
    output.request.levels = levels_;
    return output;
}

FullResult V23FullSession::accept(const std::vector<uint8_t>& wire) {
    V23Message message;
    if (!decodeV23Plain(wire, &message))
        return result(FullAction::InvalidFrame, "gen3_full_checksum_or_frame");
    if (message.board != board_ || message.group != 0x60)
        return result(FullAction::Ignored);
    if (message.index == 7) {
        if (state_ != FullState::AwaitUserConfirmation)
            return result(FullAction::Ignored, "gen3_full_unexpected_confirmation");
        return requestChallenge();
    }
    if ((message.index != 2 || state_ != FullState::HandshakeSent) &&
        (message.index != 4 || state_ != FullState::KeyDistributionSent) &&
        (message.index != 6 || (state_ != FullState::InitialChallengeSent &&
                                state_ != FullState::EncryptedChallengeSent)))
        return result(FullAction::Ignored);
    std::vector<Tlv> tlvs;
    if (!completeTlv8(message.payload) ||
        !parseTlv8(message.payload.data(), message.payload.size(), &tlvs))
        return result(FullAction::InvalidPayload, "gen3_full_truncated_tlv");
    if (message.index == 2) {
        const auto* signature = findTlv(tlvs, 7);
        const auto* nonce = findTlv(tlvs, 2);
        const auto* pk = findTlv(tlvs, 6);
        const auto* cert = findTlv(tlvs, 5);
        if (signature == nullptr || signature->empty() || nonce == nullptr || nonce->empty() ||
            (level_ > 1 && (pk == nullptr || pk->empty())) ||
            (level_ > 3 && (cert == nullptr || cert->empty())))
            return result(FullAction::InvalidPayload, "gen3_full_6002_fields");
        state_ = FullState::ResponseRequested;
        auto output = result(FullAction::NeedProvider);
        output.request.operation = "response";
        output.request.sn = serial_;
        output.request.pn = pn_;
        output.request.level = level_;
        output.request.nonceHex = upperHex(*nonce);
        output.request.deviceSignHex = upperHex(*signature);
        if (level_ > 1) output.request.pkDeviceTempHex = upperHex(*pk);
        if (level_ > 3) output.request.clientCertHex = upperHex(*cert);
        return output;
    }
    const auto* status = findTlv(tlvs, 15);
    if (status == nullptr || status->size() != 1)
        return result(FullAction::InvalidPayload, "gen3_full_key_state");
    lastKeyState_ = (*status)[0];
    if (message.index == 4) {
        if (lastKeyState_ != 0) return fail("gen3_full_distribution_rejected");
        state_ = FullState::KeyDistributionReceived;
        auto output = result(FullAction::NeedChallenge);
        output.persistCurrent = true;
        return output;
    }
    std::string serial, pn;
    if (!tlvText(tlvs, 20, &serial) || serial != serial_)
        return result(FullAction::InvalidPayload, "gen3_full_serial_mismatch");
    if (state_ == FullState::InitialChallengeSent) {
        if (lastKeyState_ < 2 || lastKeyState_ > 4)
            return fail("gen3_full_initial_key_state");
        const auto* levels = findTlv(tlvs, 1);
        const auto* capabilities = findTlv(tlvs, 25);
        if (!tlvText(tlvs, 22, &pn) || levels == nullptr || levels->size() != 1 ||
            ((*levels)[0] & 7) == 0 ||
            (capabilities != nullptr && capabilities->size() != 1))
            return result(FullAction::InvalidPayload, "gen3_full_initial_fields");
        pn_ = std::move(pn);
        levels_.clear();
        for (uint8_t level : {1, 2, 4})
            if ((*levels)[0] & level) levels_.push_back(level);
        if (capabilities != nullptr && (*capabilities)[0] == 1)
            return requestChallenge();
        state_ = FullState::AwaitUserConfirmation;
        return result(FullAction::AwaitUserConfirmation);
    }
    if (lastKeyState_ > 1) return fail("gen3_full_encrypted_challenge_rejected");
    const auto* deviceEnc = findTlv(tlvs, 24);
    const auto* aad = findTlv(tlvs, 23);
    if (!tlvText(tlvs, 22, &pn) || deviceEnc == nullptr || deviceEnc->empty() || aad == nullptr)
        return result(FullAction::InvalidPayload, "gen3_full_verify_fields");
    std::vector<uint8_t> key;
    if (!decodeHexBytes(appKey_, &key) || key.size() != 16)
        return fail("gen3_full_missing_session_key");
    std::array<uint8_t, 16> decoded{};
    std::copy(key.begin(), key.end(), decoded.begin());
    cipher_ = std::make_unique<CommonFrameCipher>(key1_, decoded, decoded, 1, 1);
    mbedtls_platform_zeroize(decoded.data(), decoded.size());
    mbedtls_platform_zeroize(key.data(), key.size());
    state_ = FullState::VerifyRequested;
    auto output = result(FullAction::NeedProvider);
    output.request.operation = "verify";
    output.request.sn = serial_;
    output.request.pn = pn;
    output.request.nonceHex = distributionNonce_;
    output.request.deviceEncHex = upperHex(*deviceEnc);
    output.request.aadHex = upperHex(*aad);
    return output;
}

FullResult V23FullSession::provideChallenge(const FullChallengeMaterial& material) {
    if (state_ != FullState::ChallengeRequested)
        return fail("gen3_full_challenge_provider_state");
    if (std::find(levels_.begin(), levels_.end(), material.level) == levels_.end())
        return fail("gen3_full_challenge_level");
    std::vector<uint8_t> nonce, cert, pk, signature, payload;
    if (!decodeHexBytes(material.nonceHex, &nonce) || nonce.empty() ||
        !appendTlv(&payload, 1, {material.level}) || !appendTlv(&payload, 2, nonce))
        return fail("gen3_full_challenge_nonce");
    if (material.level == 2) {
        if (!decodeHexBytes(material.serverCertHex, &cert) || cert.empty() ||
            !appendTlv(&payload, 8, cert)) return fail("gen3_full_server_certificate");
    } else if (material.level == 4) {
        if (!decodeHexBytes(material.pkCloudHex, &pk) || pk.empty() ||
            !decodeHexBytes(material.cloudSignHex, &signature) || signature.empty() ||
            !appendTlv(&payload, 3, pk) || !appendTlv(&payload, 4, signature))
            return fail("gen3_full_cloud_signature");
    }
    level_ = material.level;
    return send(1, payload, FullState::HandshakeSent);
}

FullResult V23FullSession::provideDistribution(const FullDistributionMaterial& material) {
    if (state_ != FullState::ResponseRequested)
        return fail("gen3_full_response_provider_state");
    std::vector<uint8_t> deviceEnc, aad, nonce, payload;
    if (!validKeyHex(material.appEncHex) ||
        !decodeHexBytes(material.deviceEncHex, &deviceEnc) || deviceEnc.empty() ||
        !decodeHexBytes(material.aadHex, &aad) ||
        !decodeHexBytes(material.nonceHex, &nonce) || nonce.empty() ||
        !appendTlv(&payload, 16, deviceEnc) || !appendTlv(&payload, 23, aad) ||
        !appendTlv(&payload, 2, nonce)) return fail("gen3_full_distribution_fields");
    wipeString(&appKey_);
    appKey_ = material.appEncHex;
    wipeString(&distributionNonce_);
    distributionNonce_ = upperHex(nonce);
    return send(3, payload, FullState::KeyDistributionSent);
}

FullResult V23FullSession::challenge(uint32_t seconds, const std::array<uint8_t, 8>& nonce) {
    if (state_ != FullState::KeyDistributionReceived || !validKeyHex(appKey_))
        return fail("gen3_full_key_challenge_state");
    std::vector<uint8_t> payload;
    if (!buildChallengePayload(reinterpret_cast<const uint8_t*>(appKey_.data()),
            appKey_.size(), seconds, nonce.data(), nonce.size(), &payload))
        return fail("gen3_full_key_challenge");
    return send(5, payload, FullState::EncryptedChallengeSent);
}

FullResult V23FullSession::provideVerification(bool verified) {
    if (state_ != FullState::VerifyRequested || !verified)
        return fail("gen3_full_provider_verification");
    state_ = FullState::Established;
    return result(FullAction::Established);
}

bool V23FullSession::encryptTo(uint8_t board, uint8_t group, uint8_t index,
    const std::vector<uint8_t>& payload, std::vector<uint8_t>* wire) {
    if (wire == nullptr) return false;
    wire->clear();
    if (!established() || !cipher_ || payload.size() > 255) return false;
    std::vector<uint8_t> frame{0x5a, 0xa5, static_cast<uint8_t>(payload.size()),
        0x3e, board, group, index};
    frame.insert(frame.end(), payload.begin(), payload.end());
    return cipher_->encrypt(frame, wire);
}

bool V23FullSession::decrypt(const std::vector<uint8_t>& wire, V23Message* message) {
    if (message == nullptr || !cipher_) return false;
    std::vector<uint8_t> plain;
    return cipher_->decrypt(wire, &plain) && parseV23Message(plain, message);
}

}  // namespace motexa::cast::gen3
