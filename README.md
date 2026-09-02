# SMB Documents Provider

Exposes an SMB share to Android's Storage Access Framework, so it appears as a location
inside the system Files app and in every app's file picker. There is no browsing UI of its
own — that is the point. The one screen it has collects credentials.

Built and verified against an ASUS TUF-BE9400 router sharing a USB stick, from a Pixel 10
(Android 17).

## Build

```sh
export JAVA_HOME=/opt/homebrew/opt/openjdk@17/libexec/openjdk.jdk/Contents/Home
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

`local.properties` points at `~/Library/Android/sdk`.

## Layout

| File | Role |
|---|---|
| `SmbDocumentsProvider.kt` | The provider: listing, read, write, create, delete, rename, thumbnails |
| `SmbConnection.kt` | Holds one live `DiskShare`, reconnects when the router drops it |
| `SmbSettings.kt` | Credentials in `EncryptedSharedPreferences` |
| `SettingsActivity.kt` | Host / share / user / password, with a connection test |

Document ids are the path inside the share, leading-slash normalised: `/` is the share
root, `/Photos/a.png` a file. `smbPath()` is the only place that becomes a backslash path.

## Four things that are easy to get wrong

Each of these was a real bug found on-device; they are the reason the code looks the way it
does.

**Binder hands you the caller's StrictMode policy.** DocumentsUI runs with
`detectNetwork()`, and that policy rides along into the provider's binder thread — so doing
SMB I/O on the incoming thread throws `NetworkOnMainThreadException`, even though it is not
the main thread. Every SMB call is therefore submitted to an executor thread this app owns,
which sets its own policy.

**A short read is taken as EOF.** The framework asks a proxy file descriptor for 128 KB;
one SMB read returns at most the negotiated maximum, 64 KB here. Returning that short count
ends the file. `onRead` loops until the requested range is filled. Symptom before the fix:
every file silently truncated to its first 64 KB, which looks like a half-drawn image
rather than an error.

**`setNotificationUri` is not optional.** Without it on the cursor from
`queryChildDocuments`, DocumentsUI never registers an observer, `notifyChange()` reaches
nobody, and the listing keeps handing back document ids that a rename or delete already
invalidated — so the *next* operation fails on a stale path.

**`FLAG_SUPPORTS_THUMBNAIL` obliges you to implement `openDocumentThumbnail`.** Declaring
the flag alone throws on every image row.

## How you actually reach the share

Measured on a Pixel 10, because the answer is not what the docs imply:

| Route | Works? |
|---|---|
| Another app's file picker (attach, open, save) | Yes — the root sits in the drawer as `//host/share` |
| `OpenShareActivity` — the app icon, or its long-press "共有" shortcut | Yes — opens DocumentsUI at the share root |
| Files by Google → その他のストレージ → this app | No — it launches this app rather than browsing inline |
| DocumentsUI on its own | No launcher icon on Pixel; it cannot be started from the home screen |

That last pair is why `OpenShareActivity` exists. Without it the share is reachable only
from inside another app's picker, which is a poor way to browse a NAS.

## Deliberate limits

- Reads stream through `StorageManager.openProxyFileDescriptor`; a large video does not get
  copied into the cache first. Writes do buffer to a cache file and upload on close, since
  SMB cannot hand out a writable fd, and a half-finished upload would otherwise truncate the
  file on the share.
- Thumbnails need the whole image in memory, so files over 32 MB keep their generic icon.
- One share at a time.
- Throughput is bounded by the router, not by this app.
