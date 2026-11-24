# V2_3 full-auth continuation, 2026-11-16

The frozen official ARM64 ELF SHA-256 remains
`2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`.
All inputs are synthetic. No account, cloud, vehicle or Bluetooth request has
been made. Original instruction segments are distinguished from production
host flows throughout this report.

## Original instruction evidence

Run `python -B -X utf8 artifacts/ninebot-connection-repro-20261115/gen3-auth/emulate_v23_full_auth.py --write`.
The output `v23-full-auth-boundary-validation.json` has 49 passing boundaries:

- Six original `0x2f1830..0x2f1f90` 6001 payload-builder executions for levels
  1/2/4 and two nonce lengths. No TLV/hex/concatenation helper is hooked.
- Three original `0x2bd884` byte-to-uppercase-hex conversions.
- Three original 6002 parser/request-builder segments followed by complete
  `0x2ba96c` JSON serialization. The serialized values are compared with
  independent literal expectations.
- One original `0x473a98(0,16)` vector constructor proves the initial full
  challenge key is sixteen zero bytes.
- Three original initial 6006 parser/state/capability segments. Capability
  byte `1` advances FSM 2→3→4 without an event; `0` and `2` advance 2→3 and
  emit process `1` (user confirmation required). Level bitmap `07` becomes
  `[1,2,4]`; SN `ABC` and PN `PN` are passed to the challenge request.
- One original final-6006 verify-material segment followed by complete
  `0x2baae0` JSON serialization proves the tag/field mapping below.
- Three original 6004 parser/storage/state segments. Key state 0 writes cur
  (flag 0) and advances 7→8. States 1/4 take the error branch without storage
  or advancement. External keyring storage is captured, not performed.
- Seven inbound frame fixtures accepted by the original receive dispatcher,
  and nine outbound frame fixtures produced by the original message
  constructor and dispatcher. The two 6005 payloads use the original complete
  CCM challenge builder with synthetic clock/RNG sources.
- Thirteen original 6007 metadata/state-guard executions. Only FSM 3 is
  accepted and advances to 4. The branch does not inspect a payload.

Runtime hooks are allocation/reserve/drop, diagnostic/error objects, the
HashMap seed, synthetic clock/RNG source, external timer scheduling/cancel,
callback capture and keyring capture. AES, CCM, hex, TLV, HashMap, serializer
and FSM guard instructions are original. Segments stop before unrelated
async IO or success side effects. They do not constitute a whole original SDK
connection run. `emulate_fast_auth.py` reserve hooks now correctly avoid growing
when capacity already suffices; this fixes the runtime harness, not wire logic.

## Verified wire fields

Plain framing is `[5A A5 length 3E board 60 index payload checksumLE]` outbound,
with inbound board/3E reversed. Checksum is the one's complement of the byte
sum from length through payload. `board` is explicit device configuration.

| Message | Fields and behavior |
|---|---|
| Initial 6005 | Existing 34-byte CCM challenge with zero16 key, checksummed plain frame |
| Initial 6006 | `0f` one-byte key state; 2/3/4 start authentication. `14` UTF-8 SN, `16` UTF-8 PN, `01` one-byte supported-level bitmap, optional `19` capability. Exactly capability 1 skips button confirmation |
| 6007 | Group 60/index 7, only accepted from InitialChallengeReceived state 3; no payload requirement |
| 6001 | `01=level`, `02=hexDecode(nonce)`; level 2 adds `08=serverCert raw`; level 4 adds `03=pkCloudTemp raw`, `04=cloudSign raw` |
| 6002 | `07=deviceSign raw`, `02=nonce raw`; levels 2/4 require `06=pkDeviceTemp raw`; level 4 also requires `05=clientCert raw` |
| 6003 | `10=hexDecode(deviceEnc)`, `17=AAD raw`, `02=hexDecode(nonce)` |
| 6004 | `0f=keyState` length 1. Only 0 advances to the app-key challenge |
| Final 6005 | Same CCM challenge using the newly distributed appEnc16; checksummed plain frame |
| Final 6006 | States 0/1 accepted in FSM 9; `14=SN`, `16=PN`, **`18=deviceEnc raw`, `17=AAD raw`**. Verify nonce is retained from the distribution response, not read from a new TLV |

The official 6002 request serializer emits
`{useCase:1,version:"3",level:[selectedLevel],sn,pn,nonce:uppercaseHex,deviceSign:uppercaseHex,pkDeviceTemp:byteArrayOrNull,clientCert:byteArrayOrNull}`.
The final verify serializer emits
`{useCase:1,sn,pn,nonce:retainedDistributionNonce,deviceEnc:uppercaseHex(tag18),aad:uppercaseHex(tag17)}`.
The apparent diagnostic names around 6002 tags 5/6 are ambiguous; the executed
serializer is authoritative for field assignment.

The original full FSM formatter names states:
0 Initial, 1 Started, 2 InitialChallengeSent, 3 InitialChallengeReceived,
4 UserConfirmed, 5 HandshakeSent, 6 HandshakeReceived, 7 KeyDistributionSent,
8 KeyDistributionReceived, 9 EncryptedChallengeSent,
10 EncryptedChallengeReceived, 11 Success, 12 Failed.

## Production API and authorized provider

`V23FullSession` in shared production C++ implements the transaction with
explicit board, 16-byte key1 and SN. The provider contract is an application
adapter; it is not a replacement claim for the official HTTP API. The adapter
must own the authorized account, request headers and server signature checking.
No official token, endpoint or credential is embedded in production code.

All successful provider results carry the exact bound `sn`. Host action IDs,
operation and connection generation must also match. Hex is even-length,
strict ASCII hex, without whitespace. A field and the final short-TLV payload
must fit the protocol byte lengths. Adapter requests carry useCase 1 and
version string "3" as context.

| Operation | Adapter request | Adapter result |
|---|---|---|
| challenge | sn, pn, levels | sn, level 1/2/4, nonceHex; serverCertHex for level 2; pkCloudHex/cloudSignHex for level 4 |
| response | sn, pn, selected level, nonceHex, deviceSignHex; pkDeviceTempHex and clientCertHex when required | sn, appEncHex32, deviceEncHex, nonceHex, aadHex |
| verify | sn, pn, retained nonceHex, deviceEncHex, aadHex | sn, verified true |

Production deliberately waits until 6007 before requesting the challenge,
whereas the SDK may fetch it concurrently and gate the actual 6001 send.
Production also waits for provider verification before enabling business
requests; the SDK invokes local success before launching verify in the
background. Both decisions strengthen the visible test workflow while
preserving verified wire schemas. Complete TLVs, nonempty required fields,
SN equality, and exact capability length are stricter than the SDK's permissive
parser/diagnostic boundaries. These policies are not presented as original
SDK equality checks.

An initial/full 6006 checksum alone is not encrypted peer proof. Candidate keys
must remain uncommitted until a valid encrypted peer response. The integrated
host must handle encrypted unsolicited traffic that can arrive while verify
is pending: `cipherReady()` becomes true after final 6006, before
`established()`. It must use encrypted frame lengths, bound the pending input,
and drain it without treating an unsolicited packet as a preparation response.

Explicit import fields are credentials.v3.authBoard (integer 0..255) and
authKey1Hex (exactly 32 hex digits). They are passed into native options with
the same names. Key1 is never guessed from a Gen2 password or model/screen
name. Original key1 preparation selects a DeviceInfo optional byte-vector or
advertised-name fallback, pads on the right to 16 and takes the last 16 bytes;
the exact JSON property mapping and legitimate per-device configuration
still require live evidence.

## Production verification and limits

`python -B -X utf8 tools/ninebot-ble-test/scripts/test_gen3_native.py` compiles
the actual production C++ with mbedTLS and passes 265 scenarios. Six complete
synthetic state-machine flows cover level 1/2/4 × button required/skipped.
Outgoing 6001/3/5 packets are compared with frozen original instruction
outputs. The flows verify provider field mapping, no early established state,
misordered confirmation rejection, rejected distribution, authenticated cipher
business frames, MAC tamper and replay. Expected wire bytes are not generated
by the C++ implementation being tested. `.tmp/gen3-native-host-check` contains
the generated host build and validation JSON; it is not a release artifact.

The host flows emulate successful external provider operations and do not
validate official HTTP signing, certificates, real SDK activation, vehicle
GATT timing, actual acceptance, or whether a configured provider is authorized.
Key-state-1 background refresh semantics are signaled separately. These remain
live acceptance requirements; synthetic passing counts must not be described
as vehicle connection counts.
