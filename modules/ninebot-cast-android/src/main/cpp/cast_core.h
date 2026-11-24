#pragma once
#include <deque>
#include <map>
#include <memory>

#include "cJSON.h"
#include "cast_protocol.h"
#include "ninebot_gen3.h"
#include "vendor/kcp/ikcp.h"

namespace motexa::cast {
class Session {
 public:
  Session(const std::string& options, const std::string& credentials);
  ~Session();
  std::string dispatch(const std::string& operation,
                       const std::string& arguments, const Bytes& bytes,
                       bool flag, int64_t value, int64_t elapsedMillis,
                       int64_t wallMillis);

 private:
  struct Tx {
    int64_t id = 0;
    std::string channel;
    Bytes bytes;
    int writeType = 1;
    int64_t created = 0;
    bool sensitive = false;
  };
  struct Read {
    int target, index, length, interval;
    int64_t due = 0;
  };
  std::string deviceName_, profileId_, modelId_, transport_, wireProfile_,
      state_ = "idle";
  int authVersion_ = 2, preTarget_ = 4, authTarget_ = 4, attMtu_ = 23,
      width_ = 0, height_ = 0, fps_ = 10, bitrate_ = 200000;
  int channel_ = 0, codec_ = -1, controlWriteType_ = 2;
  bool externalMode_ = false, authenticated_ = false, configVerified_ = false,
       modeReady_ = false, prepared_ = false, configAfterPreparation_ = false;
  bool repeatKeyframe_ = true, closed_ = false, waitForKeyframe_ = true,
       mpegHeaderSeen_ = false, stopRequested_ = false;
  int64_t now_ = 0, wall_ = 0, deadline_ = 0, nextId_ = 1, firstPts_ = -1,
          lastHeartbeat_ = 0, lastKcpProgress_ = 0;
  uint16_t sequence_ = 0;
  uint32_t ssrc_ = 0, heartbeatIndex_ = 0;
  uint64_t submittedFrames_ = 0, droppedFrames_ = 0, submittedBytes_ = 0,
           completedPackets_ = 0;
  Gen2Cipher cipher_;
  std::unique_ptr<gen3::V23FastSession> gen3Fast_;
  std::unique_ptr<gen3::V23FullSession> gen3Full_;
  std::array<uint8_t, 16> gen3Key1_{};
  gen3::FastReplyResult gen3KeyState_;
  bool gen3ProofPending_ = false, gen3RemoveNext_ = false;
  bool gen3ProviderAvailable_ = false, gen3FullProofPending_ = false;
  std::string gen3ProviderOperation_, gen3ProviderId_, gen3Candidate_;
  std::deque<Bytes> gen3PreverifyFrames_;
  size_t gen3PreverifyBytes_ = 0;
  int gen3Board_ = -1;
  Bytes password_, serial_, controlBuffer_, configBytes_;
  size_t configLength_ = 0, configOffset_ = 0;
  int configGroup_ = 0, configSmall_ = 0, configPacket_ = 0,
      configPacketCount_ = 0, expectedMode_ = -1;
  std::deque<Tx> controls_, media_;
  Tx gattPending_;
  std::map<int64_t, Tx> datagramPending_;
  std::map<uint32_t, int64_t> heartbeatSent_;
  std::vector<Read> reads_;
  std::deque<Read> preparation_;
  bool continuousReads_ = false;
  size_t readCursor_ = 0;
  int readTarget_ = -1, readIndex_ = -1, readLength_ = 0;
  ikcpcb* kcp_ = nullptr;
  struct KcpPending {
    int64_t enqueued;
    uint32_t bytes, sequence, transmissions;
    bool sent;
  };
  std::map<const IKCPSEG*, KcpPending> kcpPending_;
  int lastKcpWaiting_ = 0;
  cJSON* actions_ = nullptr;
  cJSON* rawConfig_ = nullptr;
  void action(cJSON*);
  void state(const std::string&, const std::string&);
  void error(const std::string&, bool fatal = true);
  void diagnostic(const std::string&, int64_t value = 0);
  void configure(cJSON*, bool initial = false);
  void start();
  void startGen3Challenge();
  void acceptGen3(const Bytes&);
  void startGen3Full();
  void gen3FullResult(const gen3::FullResult&);
  void gen3ProviderResult(cJSON*);
  void command(cJSON*);
  void acceptControl(const Bytes&);
  void acceptFrame(const Bytes&);
  void sendControl(const Bytes&);
  void sendWire(const Bytes&, bool sensitive = false);
  void pumpGatt();
  void emitTx(Tx);
  void datagram(const Bytes&, bool);
  void outputDatagram(const Bytes&, const std::string&);
  void configureMedia(cJSON*);
  void emitConfig();
  void beginConfig();
  void beginPreparation(bool readConfig);
  void nextPreparation();
  void requestConfigGroup();
  void setMode(int);
  void readVehicle(cJSON*);
  void nextRead();
  void telemetry(const Bytes&);
  void tick();
  void submit(const Bytes&, bool, int64_t);
  void transportResult(cJSON*);
  void setupKcp(uint32_t);
  bool inspectKcpBacklog();
  void stopMedia();
  static int kcpOutput(const char*, int, ikcpcb*, void*);
};
}  // namespace motexa::cast
