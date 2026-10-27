# Configuration schema revisioning and recovery

This topic owns marker serialization, version comparison, migration and
recovery behavior shared by the client and server configuration files. It
describes the behavior of the configuration handler, not a release-compatibility
promise.

## Marker and serialization

Existing configuration files use the shared
[version-marker format](../../config/client.md#version-marker). Marker validation
precedes version comparison; an invalid marker follows
[invalid-file recovery](#invalid-file-recovery-differs-by-config-type). A missing
configuration file is instead initialized with defaults and written through the
normal serialization path.

Whenever that path actually serializes a configuration—new defaults, an ordinary
save, a reset/recovery write, or a migration write—it stamps
`pingforit-version` with the running mod version. An unchanged current file is
loaded and validated; it is not rewritten merely because it was loaded.

## Version-string grammar and comparison

The shared [version-marker grammar](../../config/client.md#version-marker)
defines valid input for comparison.

The original spelling is retained. For comparison, the `pfi` namespace is
removed and `major.minor.patch-qualifier` is compared with Maven-compatible
version ordering; this is neither lexicographic comparison nor SemVer. For equal numeric cores,
`beta2 < beta10`, `rc1 < final`, `final == release`, and
`01.002.000-pfi-beta1 == 1.2.0-pfi-beta1`.

An invalid grammar follows the invalid-file recovery path, not future-version
protection. Only a successfully parsed valid version that compares newer than
the running version installs future-version protection. The asymmetric client
backup/default-reset and server default-save outcomes are defined in
[invalid-file recovery](#invalid-file-recovery-differs-by-config-type).

## Load-result matrix

The version relationship below is evaluated only after the marker and its value
have been parsed successfully. “Older” and “newer” are comparisons with the
running version.

| Existing source state | Client configuration | Server configuration | Disk consequence |
| --- | --- | --- | --- |
| Same supported version | Deserialize and validate. | Deserialize and validate. | No migration write is scheduled solely for a same-version load. |
| Older supported version | Deserialize the migrated root and validate it. | Deserialize the migrated root and validate it. | Queue the migration, then attempt its guarded writeback immediately. |
| Newer version | Use defaults in memory and install future-version protection. | Use defaults in memory and install the same future-version protection. | Preserve the source; no save or reset path may replace it while protected. |
| Invalid JSON/root/marker/version, deserialization, or validation | Use the client recovery flow below. | Use legacy server recovery below. | The invalid-file behavior is intentionally asymmetric. |

An older configuration still receives a migration result and current version
marker even when no schema-specific transformation applies to that config type.
If a transformed document then fails deserialization or validation, it follows
the invalid row rather than being treated as a usable migration.

## Ordered migration and writeback

Migration operates on a copy of the raw JSON root before the typed config is
deserialized. A step applies when the stored version is older than the step's
target version and the running version includes that target.

The server step at `0.3.0-pfi-beta1`:

- if `syncDuration` is absent and `pingDuration` is present, copy the raw
  `pingDuration` value to `syncDuration`;
- if `syncDuration` is already present, retain it rather than overwriting it;
- remove `pingDuration` in either case.

The client step at `0.5.0-pfi-beta1` retires the persisted wheel radius and
timeout keys and translates the transparent-block selection toggle:

- remove `wheelInnerRadius` and `wheelOuterRadius`;
- remove `wheelTimeoutMillis`;
- when `markTransparentBlocks` is absent, invert the decoded legacy
  `passThroughTransparentBlocks` value so the block-shape selection effect is
  preserved; an absent or `null` legacy value retains its old disabled meaning
  and translates to `markTransparentBlocks: true`;
- an existing `markTransparentBlocks` entry wins over the legacy entry; remove
  `passThroughTransparentBlocks` in either case. When translation is needed, an
  invalid legacy representation is left invalid for typed deserialization and
  recovery rather than silently accepted as a boolean;
- never derive spatial-selector values from retired radii, derive a replacement
  lifetime from the retired timeout, or inject selector defaults in the raw
  step; the config model initializes missing selector members;
- retain unrelated entries, the persisted wheel appearance keys, and unknown
  data.

After the ordered steps, the migration stamps the current marker. Before the
handler writes it, it compares the current on-disk bytes with the bytes read for
that migration. A changed or unreadable source discards the pending migration
and reloads the current disk state instead of overwriting an external edit.

The write uses the transformed raw root as its preservation base: serialized
known configuration fields replace their corresponding entries, other retained
raw entries survive (including unknown members nested inside the client
`spatialSelector` object), and the current marker is stamped again. If that
write fails, the valid loaded configuration remains in memory and the migration
stays pending for a later save attempt; it is not converted into invalid-file
recovery.

Client serialization at or after `0.5.0-pfi-beta1` also removes the retired
wheel radius and timeout keys and `passThroughTransparentBlocks` from the
document it writes, including the preservation base of a guarded writeback, so
a later save cannot resurrect them. A same-version load is not rewritten merely
because retired keys are present; the removal takes effect when that
configuration is next serialized.

## Same-version shape normalization

Load-time normalization is separate from ordered version migration and also
runs for a same-version or older load, composed after the ordered steps and
before typed deserialization. It reconciles the persisted server shapes and the
explicitly conflicting client preference relations described below, failing
closed on a malformed presentation shape:

- A server document whose `presentation.targetTypes` map is present keeps that
  map and drops the obsolete flat `presentation.white`, `presentation.black`,
  and `presentation.whitelistOnly` members. When the map is absent, the flat
  members' values are copied to every one of the five target-type rule sets and
  the flat members are removed.
- A malformed `presentation` object or malformed `targetTypes` map normalizes to
  a deny-all rule for every target type. A malformed individual rule object
  denies only its own target type. A malformed global permission override
  normalizes to a global deny-all policy.
- Unrelated server settings and every well-formed target-type rule survive
  normalization; an existing new-shape map wins over the obsolete flat members.
- An integer numeric scalar at the server inventory's pending-memory member is
  wrapped as a finite limit object before typed validation. The member's grammar is
  owned by [server configuration](../../config/server.md#inventory-policy-object).
  This compatibility normalization applies only to that member: it does not
  reinterpret scalar forms of other limit objects. An existing object keeps its
  mode and value, and missing or null members use normal model defaulting rather
  than triggering a shape write by themselves. Typed validation still applies
  the owning field's bounds.
- A client document drops the obsolete `presentationReceive` and
  `presentationDisplay` keys.
- The explicitly conflicting client configs are normalized under the same writeback
  guard described above; the conflict meanings and their key dependencies are
  owned by [client configuration](../../config/client.md).

A document with neither a normalizable shape nor those explicit
preference conflicts is not rewritten for normalization alone. Normalization
shares the [guarded migration writeback](#ordered-migration-and-writeback) and
[future-version protection](#future-version-protection).

## Invalid-file recovery differs by config type

The client configuration opts into recovery. For an invalid client source, the
handler backs up the original bytes before it attempts to reset defaults. When that
backup and reset write succeed, the replacement begins with exactly these three
one-line comments followed by the serialized defaults:

```text
// Previous config had an error.
// Error reason: ...
// Backup file: ...
```

The reason is reduced to a safe one-line comment. If the backup cannot be made,
the handler leaves defaults in memory and installs `INVALID_FILE` save
protection without attempting the reset write. If the reset write fails after a
successful backup, it also installs that protection. While it is installed,
ordinary saves and guarded saves decline to write. A reset to defaults is not
skipped wholesale: it replaces the in-memory config with a newly constructed
default, then its forced persistence attempt is declined by the protection,
leaving the protected disk source untouched. A later successful load can clear
the protection. On a later client load, the recognized generated recovery
header is removed before the replacement file is parsed. This client flow
covers parsing and strict persisted block-list validation alike.

The server configuration does not use that backup-and-lock flow for invalid
input. It creates defaults and attempts a normal save, with no broken-file
backup and no `INVALID_FILE` protection. A server save failure is reported by
the normal save path and leaves defaults in memory; it does not acquire the
client's preservation lock.

## Future-version protection

For either config type, a successfully parsed marker newer than the running
version is not a malformed file. The handler keeps the file on disk, uses
defaults in memory, and installs `FUTURE_VERSION` protection. Ordinary saves and
guarded saves decline to write while it is installed. A normal reset to defaults
still constructs fresh defaults in memory before its forced save is declined;
future-version protection therefore prevents the disk write, rather than
early-returning the entire reset operation. If a reset first
discovers that a pending migration's source changed and that reload installs
protection, it returns after restoring defaults and without attempting that
forced save. Neither route supplies a downgrade write.

A later load re-evaluates the file currently on disk. If it remains a future
version, protection remains; if it has been replaced or removed, normal
initialization, version handling, or the appropriate invalid-file path applies.
There is no implicit downgrade path.

The client configuration entry point is
[client configuration](../../config/client.md). The configuration-UI workflow is
[configuration UI](../../UI/settings-screen.md). Server timing values and marker
lifetime behavior are owned separately by
[marker lifecycle](../authority/marker_lifecycle.md).
