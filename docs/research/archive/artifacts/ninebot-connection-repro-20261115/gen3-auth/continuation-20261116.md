# V2_3 fast-session continuation (2026-11-16)

This note records the continuation after the original Gen3 / V2_3 review. It
uses the frozen official ARM64 library only with synthetic memory and payloads;
it does not contact a vehicle, BLE peripheral, cloud endpoint, or account.

## New execution evidence

`emulate_v23_session.py --write` executes the original functions and writes
`v23-session-boundary-validation.json`. The report contains 49 boundary cases:

- The protocol constructor maps version `3` to native discriminant `3`
  (`V2_3`), while versions `0,1,2,4` take different constructors. The newly
  created profile has cipher flag bytes `+0x40..+0x42 = 00 00 00`.
- The real message constructor plus dispatcher emits a checksummed 5AA5 frame
  while this flag is clear. The fields are `[5A A5, payloadLen, 3E,
  targetBoard, group, index, payload, checksumLE]`. The 6005 call is
  `group=0x60, index=5`; the target board is selected from device configuration
  and cannot be inferred from screen size.
- The original receive dispatcher accepts a valid 6006 checksum and rejects a
  one-bit checksum mutation. The 6006 metadata is `group=0x60, index=6`.
- The original TLV map and UTF-8 validation reach the key-state branch for
  states 0 through 4. Missing/short TLVs, malformed UTF-8, wrong group/index,
  and bad checksum are rejected before the success side effects. Exact serial
  equality is an additional production policy; this original execution segment
  checks UTF-8 and does not prove an equality check.
- The original success helper enables the common cipher for discriminants 2/3
  and leaves it disabled for 0/1/4. The counter initializer sets TX and RX to
  `1`. The following common frame vectors execute with the original AES/MAC
  implementation and an independent Python oracle.
- The original 6003 payload builder emits TLVs `0x10=hexDecode(deviceEnc)`,
  `0x17=raw AAD`, and `0x02=hexDecode(nonce)`. This is a payload boundary, not
  proof of cloud request signing or a valid session.

The production host check now passes 265 scenarios, including six full synthetic
6001..6007 flows. It compiles the actual
`modules/ninebot-cast-android/src/main/cpp/ninebot_gen3.cpp` and links bundled
mbedTLS. The generated validation is under `.tmp/` and is not a package
artifact.

## Cached-key implementation boundary

`V23FastSession` is an explicit cached-key session primitive. The caller must
provide the selected device board, a 16-byte `key1`, the serial, and validated
32-character hex current/next app keys. It provides:

1. 6005 challenge generation with a caller-supplied realtime second and CSPRNG
   nonce, then checksummed 5AA5 framing.
2. 6006 validation of frame metadata, complete TLVs, key state and serial.
3. Cur/next retry semantics for states 2/4, `NeedAuthentication` for missing
   or exhausted keys, and a promotion hint only after a successful next-key
   challenge.
4. Common encrypted business-frame encrypt/decrypt after success, with the
   explicit key1/key2/auth inputs and replay counters.

The implementation does not derive key1 from a model name, screen size or a
Gen2 password. It does not accept a successful parser result as authentication;
the caller still owns the physical GATT state and the SDK/application success
event. It does not persist or print secret material.

## Full-auth continuation

`emulate_v23_full_auth.py --write` now supplies 49 further original-instruction
boundary cases. See `full-auth-continuation-20261116.md` and its frozen JSON for
the complete stage/field mapping, authorized-provider adapter contract, and the
limits of synthetic segment execution. Production `V23FullSession` implements
6001..6007 with level 1/2/4, actual button-confirmation metadata, distribution,
final key challenge, provider verification, and common encrypted business
frames. The host must still prove a valid encrypted peer reply before declaring
authentication or committing a current key.

## Remaining live validation requirements

The following are still outside the evidence and must remain a visible failure
state in the test app:

- Official cloud header algorithms (`X-Ctx-Hash`, `X-Nonce`, `X-Timestamp`,
  `X-Jti`) and authorization are delegated to an explicit authorized provider;
  the adapter has no built-in official endpoint or token.
- The mapping from a real advertised device to the selected board and key1,
  including the official SDK activation/token and legal key distribution.
- The key-state-1 background future and live cloud refresh/verify outcomes.
- Device-side acceptance and real HCI/GATT ordering for a bound vehicle.

The provider endpoint contract is an application adapter, not the official
cloud API. Absence of the explicit board/key1, valid current/next key or complete
provider configuration remains a visible failure. Local passing results do not
claim a real bound-vehicle acceptance test.
