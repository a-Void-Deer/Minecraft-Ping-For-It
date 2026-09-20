# Configuration schema revisioning and recovery

This topic owns the JSON schema marker, version comparison, migration and
recovery behavior shared by `ClientConfig` and `ServerConfig`. It describes the
behavior of the configuration handler, not a release-compatibility promise.

## Marker and serialization

An existing configuration file must parse to a JSON object containing
`"pingforit-version"` as a string. A missing marker, `null`, a non-string
primitive, an object/array marker, or a blank string is invalid before version
parsing begins. The string must then match the Ping For It version grammar. A
missing configuration file is instead initialized with defaults and written
through the normal serialization path.

Whenever that path actually serializes a configuration—new defaults, an ordinary
save, a reset/recovery write, or a migration write—it stamps
`pingforit-version` with the running mod version. An unchanged current file is
loaded and validated; it is not rewritten merely because it was loaded.

## Version-string grammar and comparison

The full-string grammar is:

```text
\A([0-9]+)\.([0-9]+)\.([0-9]+)-pfi-([A-Za-z0-9]+)\z
```

It has three nonnegative decimal digit sequences (each permits leading zeros and
arbitrary width), the lowercase literal `-pfi-`, and a nonempty ASCII
alphanumeric qualifier. There is no trimming: whitespace or any other extra
character makes the string invalid. For example:

| Valid | Invalid |
| --- | --- |
| `0.3.0-pfi-beta1` | `0.3.0` |
|  | `0.3.0-beta1` |
|  | `0.3.0-PFI-beta1` |
|  | `0.3.0-pfi-beta.1` |

The original spelling is retained. For comparison, the `pfi` namespace is
removed and `major.minor.patch-qualifier` is compared with the repository's
`MavenComparableVersion`; this is neither lexicographic comparison nor SemVer.
For equal numeric cores, `beta2 < beta10`, `rc1 < final`, `final == release`,
and `01.002.000-pfi-beta1 == 1.2.0-pfi-beta1`.

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
deserialized. The current server-only step applies when the stored version is
older than `0.3.0-pfi-beta1` and the running version includes that migration
target:

- if `syncDuration` is absent and `pingDuration` is present, copy the raw
  `pingDuration` value to `syncDuration`;
- if `syncDuration` is already present, retain it rather than overwriting it;
- remove `pingDuration` in either case.

After the ordered steps, the migration stamps the current marker. Before the
handler writes it, it compares the current on-disk bytes with the bytes read for
that migration. A changed or unreadable source discards the pending migration
and reloads the current disk state instead of overwriting an external edit.

The write uses the transformed raw root as its preservation base: serialized
known configuration fields replace their corresponding entries, other retained
raw entries survive, and the current marker is stamped again. If that write
fails, the valid loaded configuration remains in memory and the migration stays
pending for a later save attempt; it is not converted into invalid-file recovery.

## Invalid-file recovery differs by config type

`ClientConfig` opts into recovery. For an invalid client source, the handler
backs up the original bytes before it attempts to reset defaults. When that
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
`save` and `saveSafely` decline to write. `resetToDefaults` is not skipped
wholesale: it replaces the in-memory config with a newly constructed default,
then its forced persistence attempt is declined by the protection, leaving the
protected disk source untouched. A later successful `load` path can clear the
protection. This client flow covers parsing and strict persisted block-list
validation alike.

`ServerConfig` does not use that backup-and-lock flow for invalid input. It
creates defaults and attempts a normal save, with no broken-file backup and no
`INVALID_FILE` protection. A server save failure is reported by the normal save
path and leaves defaults in memory; it does not acquire the client's preservation
lock.

## Future-version protection

For either config type, a successfully parsed marker newer than the running
version is not a malformed file. The handler keeps the file on disk, uses
defaults in memory, and installs `FUTURE_VERSION` protection. `save` and
`saveSafely` decline to write while it is installed. A normal
`resetToDefaults` call still constructs fresh defaults in memory before its
forced save is declined; future-version protection therefore prevents the disk
write, rather than early-returning the entire reset operation. If a reset first
discovers that a pending migration's source changed and that reload installs
protection, it returns after restoring defaults and without attempting that
forced save. Neither route supplies a downgrade write.

A later `load` re-evaluates the file currently on disk. If it remains a future
version, protection remains; if it has been replaced or removed, normal
initialization, version handling, or the appropriate invalid-file path applies.
There is no implicit downgrade path.

The client configuration entry point is
[client configuration](../../config/client.md). The configuration-UI workflow is
[configuration UI](../../UI/config.md). Server timing values and marker
lifetime behavior are owned separately by
[marker lifecycle](../authority/marker_lifecycle.md).
