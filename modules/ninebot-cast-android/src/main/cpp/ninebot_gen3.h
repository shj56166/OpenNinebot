#pragma once

#include <cstddef>
#include <cstdint>
#include <array>
#include <memory>
#include <string>
#include <vector>

namespace motexa::cast::gen3 {

struct Tlv {
    uint8_t type;
    std::vector<uint8_t> value;
};

// Encodes the verified 34-byte V2_3 6005 payload, not a complete BLE frame.
// The caller owns obtaining an authorized appEnc and a fresh random nonce.
// Does not obtain credentials, send packets, or authenticate a device.
bool buildChallengePayload(const uint8_t* keyHex32, size_t keyLen,
                           uint32_t unixSeconds, const uint8_t* nonce8,
                           size_t nonceLen, std::vector<uint8_t>* out);

// Original short-TLV semantics: retain the complete prefix, stop at truncation,
// and let a complete later entry replace an earlier value with the same type.
// This is a decoder primitive; successful parsing is not authentication.
bool parseTlv8(const uint8_t* bytes, size_t length, std::vector<Tlv>* out);

// Verified common SDK cipher primitive, not an authenticator on its own.
// The cached-key/full sessions establish the key, counters and frame phase.
class CommonFrameCipher {
public:
    CommonFrameCipher(const std::array<uint8_t, 16>& key1,
                      const std::array<uint8_t, 16>& key2,
                      const std::array<uint8_t, 16>& auth,
                      uint32_t txCounter = 0, uint32_t rxCounter = 0);
    ~CommonFrameCipher();
    CommonFrameCipher(const CommonFrameCipher&) = delete;
    CommonFrameCipher& operator=(const CommonFrameCipher&) = delete;
    bool encrypt(const std::vector<uint8_t>& plaintext,
                 std::vector<uint8_t>* encrypted);
    bool decrypt(const std::vector<uint8_t>& encrypted,
                 std::vector<uint8_t>* plaintext);
    uint32_t txCounter() const { return txCounter_; }
    uint32_t rxCounter() const { return rxCounter_; }

private:
    std::array<uint8_t, 16> key_{};
    std::array<uint8_t, 16> auth_{};
    bool valid_ = false;
    uint32_t txCounter_;
    uint32_t rxCounter_;
};

struct V23Message {
    uint8_t board = 0;
    uint8_t group = 0;
    uint8_t index = 0;
    std::vector<uint8_t> payload;
};

// V2_3 starts with checksummed 5AA5 frames. group/index are distinct fields:
// 6005 is group=0x60, index=5, with an explicitly selected target board.
bool encodeV23Plain(const V23Message& message, std::vector<uint8_t>* out);
bool decodeV23Plain(const std::vector<uint8_t>& wire, V23Message* out);

enum class FastReply {
    Ignored,
    InvalidFrame,
    InvalidPayload,
    SerialMismatch,
    Accepted,
    RetryCurrent,
    NeedAuthentication,
    InvalidKeyState
};

struct FastReplyResult {
    FastReply result = FastReply::Ignored;
    uint8_t keyState = 0xff;
    bool promoteNext = false;
    bool removeNext = false;
    bool backgroundRefreshSuggested = false;
};

// Cached-key V2_3 connection only. Full 6001/2/3 distribution and cloud trust
// are separate responsibilities. key1 must come from the device configuration
// established for this vehicle; it is never inferred from a model or Gen2 key.
// Caller supplies a fresh CSPRNG nonce and real-time seconds per challenge.
class V23FastSession {
public:
    V23FastSession(uint8_t board, const std::array<uint8_t, 16>& key1,
                   std::string serialNumber, std::string currentKeyHex,
                   std::string nextKeyHex);
    ~V23FastSession();
    V23FastSession(const V23FastSession&) = delete;
    V23FastSession& operator=(const V23FastSession&) = delete;
    bool challenge(uint32_t unixSeconds, const std::array<uint8_t, 8>& nonce,
                   std::vector<uint8_t>* wire);
    FastReplyResult accept(const std::vector<uint8_t>& wire);
    bool encrypt(uint8_t group, uint8_t index, const std::vector<uint8_t>& payload,
                 std::vector<uint8_t>* wire);
    bool encryptTo(uint8_t board, uint8_t group, uint8_t index,
                   const std::vector<uint8_t>& payload, std::vector<uint8_t>* wire);
    bool decrypt(const std::vector<uint8_t>& wire, V23Message* message);
    bool established() const { return cipher_ != nullptr; }
    bool selectedNext() const { return selectedNext_; }

private:
    uint8_t board_;
    std::array<uint8_t, 16> key1_;
    std::string serial_;
    std::string current_;
    std::string next_;
    bool selectedNext_ = false;
    bool challengePending_ = false;
    std::unique_ptr<CommonFrameCipher> cipher_;
};

enum class FullState {
    Idle, InitialChallengeSent, AwaitUserConfirmation, ChallengeRequested,
    HandshakeSent, ResponseRequested, KeyDistributionSent, KeyDistributionReceived,
    EncryptedChallengeSent, VerifyRequested, Established, Failed
};

enum class FullAction {
    Ignored, InvalidFrame, InvalidPayload, AwaitUserConfirmation,
    NeedProvider, Send, NeedChallenge, Established, Failed
};

// Application adapter contract. The authorized provider owns the official
// account/token/header validation and converts these explicit hex byte fields.
// Never log this object or serialize it into a diagnostic export.
struct FullProviderRequest {
    std::string operation;
    std::string sn;
    std::string pn;
    std::vector<uint8_t> levels;
    uint8_t level = 0;
    std::string nonceHex;
    std::string deviceSignHex;
    std::string pkDeviceTempHex;
    std::string clientCertHex;
    std::string deviceEncHex;
    std::string aadHex;
};

struct FullChallengeMaterial {
    uint8_t level = 0;
    std::string nonceHex;
    std::string serverCertHex;
    std::string pkCloudHex;
    std::string cloudSignHex;
};

struct FullDistributionMaterial {
    std::string appEncHex;
    std::string deviceEncHex;
    std::string nonceHex;
    std::string aadHex;
};

struct FullResult {
    FullAction action = FullAction::Ignored;
    FullState state = FullState::Idle;
    uint8_t keyState = 0xff;
    std::vector<uint8_t> wire;
    FullProviderRequest request;
    std::string error;
    // Candidate only. Persist the provider key after encrypted peer proof.
    bool persistCurrent = false;
};

// Full V2_3 6001..6007 transaction. All transport, provider IO, correlation IDs,
// deadlines and CSPRNG sources stay in the host. No IO occurs in this class.
// This implementation waits for provider verification before Established;
// the original SDK launches that verification in the background.
class V23FullSession {
public:
    V23FullSession(uint8_t board, const std::array<uint8_t, 16>& key1,
                   std::string serialNumber);
    ~V23FullSession();
    V23FullSession(const V23FullSession&) = delete;
    V23FullSession& operator=(const V23FullSession&) = delete;
    FullResult start(uint32_t unixSeconds, const std::array<uint8_t, 8>& nonce);
    FullResult accept(const std::vector<uint8_t>& wire);
    FullResult provideChallenge(const FullChallengeMaterial& material);
    FullResult provideDistribution(const FullDistributionMaterial& material);
    FullResult challenge(uint32_t unixSeconds, const std::array<uint8_t, 8>& nonce);
    FullResult provideVerification(bool verified);
    bool encryptTo(uint8_t board, uint8_t group, uint8_t index,
                   const std::vector<uint8_t>& payload, std::vector<uint8_t>* wire);
    bool decrypt(const std::vector<uint8_t>& wire, V23Message* message);
    bool established() const { return state_ == FullState::Established; }
    bool cipherReady() const { return cipher_ != nullptr; }
    FullState state() const { return state_; }

private:
    FullResult result(FullAction action, const char* error = "") const;
    FullResult fail(const char* error);
    FullResult requestChallenge();
    FullResult send(uint8_t index, const std::vector<uint8_t>& payload, FullState state);
    uint8_t board_;
    std::array<uint8_t, 16> key1_;
    std::string serial_;
    std::string pn_;
    std::vector<uint8_t> levels_;
    uint8_t level_ = 0;
    uint8_t lastKeyState_ = 0xff;
    std::string appKey_;
    std::string distributionNonce_;
    FullState state_ = FullState::Idle;
    std::unique_ptr<CommonFrameCipher> cipher_;
};

}  // namespace motexa::cast::gen3
