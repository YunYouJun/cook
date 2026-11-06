# Swift release public keys

`swift-release-keys.asc` is an unmodified snapshot of the official
[Swift release key bundle](https://www.swift.org/keys/all-keys.asc), retrieved on
2026-11-06 UTC. It contains public keys, not credentials.

- SHA-256: `67abcc2cff49a9dd4536b12bc4a7c2b82c434ddaa42b90516c9734f4dd5481af`
- Size: 23,666 bytes
- Previously verified Swiftly signer: Swift Automatic Signing Key #4,
  `E813C892820A6FA13755B268F167DF1ACF9CE069`

CI imports this local bundle before the pinned setup-swift action installs Swift
6.2.2. GPG download-signature verification stays enabled. A missing or rotated
key must fail verification; do not set `skip-verify-signature`.

This avoids depending on a live key-download response during every build. Two
Linux runs received content that GPG rejected before installing or testing
Swift. Follow the [official Swiftly instructions](https://www.swift.org/install/linux/swiftly/)
when refreshing the bundle: download from the official HTTPS source, inspect
the public-key fingerprints with an isolated GPG home, verify the installer
signature, and update the checksum and date here. The current snapshot's GPG
import and signature verification are validated by CI; GPG is not installed on
the local validation machine.
