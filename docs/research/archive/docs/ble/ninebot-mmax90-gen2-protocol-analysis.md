# Ninebot MMAX90 Gen2 BLE protocol analysis

## Scope and source

This document records the read-only integration path for the Ninebot MMAX90.
It is based on the local reference repository at
`tools/third_party/segway-ninebot-ble-app`, commit
`d26b181c1d7eda8e11248af53a6a622334746d9a` (MIT), and on the HCI capture in
`tools/hci-export-windows/exports/hci-20260715-073316`.

The reference Flutter app is protocol research material, not a dependency of
the Android application. Do not copy its vehicle controls, raw register UI,
plaintext credential database, or session-counter probing logic.

## Evidence from MMAX90 capture

- Advertised name and serial: `NBDBD2318J0101`.
- Transport is Nordic UART Service (NUS):
  - Service: `6E400001-B5A3-F393-E0A9-E50E24DCCA9E`
  - Write without response: `6E400002-B5A3-F393-E0A9-E50E24DCCA9E`
  - Notify: `6E400003-B5A3-F393-E0A9-E50E24DCCA9E`
- The application frames use the Gen2 sync bytes `5A A5`.
- The captured PRE_COMM request decrypts to `5A A5 00 3E 09 5B 00`.
  MMAX90 therefore uses `0x09` as the observed PRE_COMM target.

The supplied HCI trace proves scanning, GATT discovery, notification
subscription, MTU negotiation, secure framing, and PRE_COMM for this vehicle.
It does not contain a clean first-pairing SET_PWD/AUTH sequence, so that part
must be validated against the vehicle before being considered supported.

## Transport and frame format

The reference implementation is in:

- `lib/core/ble/ble_transport.dart`
- `lib/core/crypto/nb_crypto.dart`
- `lib/core/protocol/nb_protocol.dart`

For encrypted Gen2 frames, the transport reassembles the following layout:

```text
5A A5 LEN | encrypted body (LEN + 4 bytes) | encrypted CBC-MAC (4) | counter (BE u16)
```

The plaintext request format is:

```text
5A A5 LEN 3E TARGET CMD INDEX DATA...
```

Responses use the source-board byte before `3E`. Requests and notifications
can be fragmented at the ATT MTU boundary, so the backend must buffer by
frame length instead of assuming one callback equals one frame.

For Gen2 bootstrap encryption:

```text
FW_DATA = 97CFB802844143DE56002B3B34780A5D
K0 = SHA1(pad16(deviceName) || FW_DATA)[0:16]
```

After authentication, the session key is:

```text
sessionKey = SHA1(pad16(password) || pad16(auth))[0:16]
```

The authenticated mode uses AES-CTR for the body and a four-byte CBC-MAC.
Its nonce contains a big-endian session counter and the first eight bytes of
`auth`. A response must pass MAC verification before its command, register, or
payload is parsed. A counter or MAC failure ends the session and requires a
fresh handshake.

## Handshake profile

```text
Connect and discover NUS
  -> enable notifications
  -> PRE_COMM with K0
  -> receive dynamic auth (16 bytes) and serial number
  -> AUTH with stored password, or explicit SET_PWD then AUTH
  -> encrypted read-only register polling
```

`auth` changes on every session. It is not a password and cannot be reused as
one. The 16-byte `password` is the persistent credential needed to derive
each session key.

### Credential policy

- Connecting and PRE_COMM require no persistent password.
- Authenticated reads require a valid password previously created or imported
  for this vehicle.
- Existing encrypted HCI traffic cannot feasibly recover an old password.
  The captured dynamic `auth` values do not reveal it.
- A first pairing creates a new password and may require physical confirmation
  on the vehicle. Its effect on the official Ninebot App pairing is not yet
  verified for MMAX90.
- Never send SET_PWD automatically. It requires a user-owned vehicle,
  an explicit warning/confirmation, and observed physical confirmation.
- Generate new credentials with `SecureRandom`, not the reference app's
  time-seeded `java.util.Random` compatibility routine.
- Persist only the password using Android Keystore protection, keyed by the
  stable serial number. Do not use the BLE MAC as the credential key and do
  not store password/auth/ident in SQLite or JavaScript-visible state.
- On Android, always run PRE_COMM then AUTH on reconnect. Do not import the
  reference app's saved-auth and counter-offset probing optimization.

## Read-only telemetry candidates

These are candidates from the reference register tables, not MMAX90-validated
facts. Send one read at a time and compare the decrypted value with the
official-app display under controlled conditions before exposing it to users.

| Signal | Target/register | Candidate decoding | Initial cadence | Status |
| --- | --- | --- | --- | --- |
| Speed | DIS `0x01:0x26` | `u16 * 0.1 km/h` | 250 ms | strong candidate |
| Power | DIS `0x01:0xBD` | signed `i16`, W | 1 s | strong candidate |
| Battery | DIS `0x01:0xB5` | percentage | 2 s | candidate |
| Remaining range | DIS `0x01:0x25` | `u16 * 0.1 km` | 2 s | candidate |
| Total mileage | DIS `0x01:0xB7` | `u32 * 0.001 km` | 2 s | candidate |
| Trip mileage | DIS `0x01:0xB9` | `u16 * 0.01 km` | 1 s | candidate |
| Running time | DIS `0x01:0xBA` | seconds | 1 s | candidate |
| NOS | DIS `0x01:0x5E` | `u16`, scaling unknown | 1 s | strong candidate |

## Validation gates

1. Add offline unit tests for encryption, MAC verification, frame
   reassembly, and the known MMAX90 PRE_COMM request.
2. Build a diagnostic mode that stops after PRE_COMM and reports SN,
   discovered UUIDs, and whether a protected local credential exists. It must
   not send SET_PWD or any register write.
3. Only after a controlled, user-confirmed first-pairing test, add SET_PWD
   and AUTH. Capture the whole sequence and verify both directions.
4. Validate each read register against a stationary display and a short ride;
   record byte length, endianness, scaling, and response board.
5. Enable ride recording only after speed remains stable through reconnects
   and every accepted response passes MAC verification.

No power, seat, lock, mode, limit, raw-register, or cloud-ident command is in
the MMAX90 integration scope.
