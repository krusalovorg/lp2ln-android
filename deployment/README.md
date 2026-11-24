# Private deployment

Environment-specific Dockerfiles, Compose files, node options and operational
notes belong in `deployment/private/` (ignored by Git). Do not place SSH keys,
tokens, real server addresses or copies of node databases in tracked files.

The Android app receives its initial bootstrap address from the local
`LP2LN_BOOTSTRAP` environment variable or the `lp2lnBootstrap` Gradle property.
Without one, configure the address in the app's Network screen. A user-entered
address takes precedence and persists across app updates.

```powershell
$env:LP2LN_BOOTSTRAP = "seed.example.org:18080"
.\gradlew.bat assembleDebug -PrustAbis=arm64-v8a
```

The compiled APK contains this address; this mechanism keeps infrastructure
details out of the public source tree, not out of the shipped application.

Before publishing, inspect `git diff --cached` and use `git check-ignore` for
private deployment files. `.gitignore` does not remove anything already tracked
or erase information from earlier Git history.

Runtime deployment should use a dedicated data directory, bounded resources,
rotating logs and a restart policy. Expose only peer transport ports. Keep health,
debug and application IPC endpoints private. Validate configuration using
`lp2lnd config check -o <options-file>` before starting the service.

The patches in `patches/` record targeted changes for the separate LP2LN core
repository. They do not change the pinned Git dependency automatically. Apply
them in the core checkout and publish/pin a tested revision when updating the
Android dependency. The application channel `0x4c46` carries encrypted file
invitations, chat messages and their delivery acknowledgments.

`allow-reliable-upgrades.patch` lets a peer that already has only UDP sessions
add a reliable session without counting as a new peer. It bypasses the new-peer
admission redirect for this upgrade while still rejecting duplicate reliable
sessions. Otherwise an apparently connected mobile peer can be unable to open
the TCP channel needed to download a file.

`prefer-reliable-file-messages.patch` prefers an available reliable session for
this channel on every relay hop, while preserving a UDP fallback. This is a
temporary delivery policy.
