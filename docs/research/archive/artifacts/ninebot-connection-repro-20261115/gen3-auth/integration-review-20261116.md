# Gen3 integration review, 2026-11-16

This review covers the shared `ninebot_gen3` transaction/cipher, its host in
`cast_core`, and the independent test app's authorized-provider callback and
credential-persistence paths. It uses synthetic inputs only. No account,
cloud, Bluetooth, or vehicle request was made.

## Confirmed boundaries

- Initial and final checksummed 6006 packets do not establish the integrated
  host's authenticated state and cannot save a candidate application key.
- Full authentication waits for vehicle confirmation when required, binds
  provider callbacks to the request ID, operation, selected serial, engine,
  and connection generation, and waits for provider verification.
- `cipherReady()` is distinct from `established()`: encrypted traffic may
  arrive during pending verification. The host uses encrypted frame lengths,
  bounds pending data to 4,096 bytes / 16 frames, and drains it as diagnostics
  without claiming a preparation response or promoting a candidate key.
- The app passes credentials to the provider and native engine through the
  explicit sensitive action path. Diagnostic exports retain stage, sizes,
  hashes, failure category, and correlation IDs without raw auth materials.
- Provider shutdown, connection generation checks, and per-action engine
  ownership prevent an old callback/action batch from updating a new session.
  A persistence failure releases the session and retains the original profile.
- Imported board/key1 remain explicit per-vehicle configuration. Neither is
  guessed from a Gen2 password, model, screen size, or a research sample.

## Confirmed integration fixes

1. Recheck wall-clock range before the post-6004 fresh challenge as well as at
   initial start. A long confirmation/provider wait can span a clock change.
2. Require a JSON numeric supported level (1, 2, or 4) at the provider boundary.
   The shared integer helper already rejects fractional values, but permits a
   numeric string; protocol material should not depend on that coercion.
3. Require the first encrypted proof to match the current preparation request's
   source, command 4, register, and exact payload length before authentication
   or key promotion. A fresh unrelated encrypted notification should be logged
   without being counted as the solicited preparation response.

All three fixes are present in the final reviewed host source. The core
regression cases explicitly reject fractional/string levels and withhold
promotion for an unsolicited encrypted notification before the matching
preparation response. The core test owner reports 3,422 passing checks in the
final frozen suite; this review separately reran the 265-check Gen3 host suite
below after reading the final changes. No unresolved integration blocker was
found within this review's scope.

## Final protocol/evidence rerun

All six original-ELF scripts completed successfully after the reserve-hook
correction: secret 275, strict hex 12, fast challenge/TLV 47, common wire 27,
cached session 49, full authentication 49. These are 459 original instruction
boundary cases with overlapping synthetic inputs, not 459 connections.

`test_gen3_native.py` compiled the production C++ and passed 265 checks,
including six level 1/2/4 × required/skipped-confirmation full flows against
frozen original payload/frame vectors. The frozen ELF SHA-256 is
`2ce24100a8c525b575abbf2d53e0f7eee3562d29fec9ad3fe54431bcd9604188`.
`validation-manifest.json` records current evidence and production source hashes.

Real vehicle timing/acceptance, authorized cloud response signing and account
headers, official activation, and legitimate per-device key1 configuration
remain live acceptance requirements. The HTTPS adapter contract delegates those
account/server responsibilities to an explicitly configured authorized service.
