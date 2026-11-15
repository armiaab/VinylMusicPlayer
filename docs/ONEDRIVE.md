# OneDrive

Vinyl can browse the music in your **OneDrive** and play it. Songs stream as soon as you tap
them and, once played, are saved to `Music/OneDrive` on the phone, so they work offline from then
on and appear in the library like any other song.

- [What it does](#what-it-does)
- [Setting up OneDrive](#setting-up-onedrive)
- [Building with the client id](#building-with-the-client-id)
- [How it works](#how-it-works)
- [Limitations](#limitations)

## What it does

- **In your library.** Once you sign in, every music file of the drive is listed in *Songs*,
  *Albums*, *Artists* and *Genres* (and search), next to your local songs; tapping one streams it.
  Untagged files are named like MediaStore names local ones: the folder is the album, the
  artist is "Unknown artist". The drive is scanned right after signing in, rescanned for changes
  at most every 15 minutes when the library syncs, and fully again on *Reset discography*.
  *OneDrive → ⋮ → Refresh* rescans on demand.
- **Browse.** *Navigation drawer → OneDrive* lists the folders of your drive and the music files
  in them. Formats are recognised by extension: MP3, M4A/M4B, AAC, OGG/OGA, Opus, FLAC, WAV, MKA,
  3GP, AMR/AWB and MIDI. Other files are hidden. The same extension list also makes the local
  *Folders* view show `.opus`, `.ogg`, `.m4a`... on devices whose MIME table lacks them.
- **Play right away.** Tap a song to play the folder from there, or use ⋮ → *Play next* / *Add to
  playing queue*. A folder's ⋮ menu plays or queues its songs. The toolbar has *Shuffle all* and
  *Play all* for the open folder.
- **Offline after playing.** When a song has been downloaded completely while playing, it is
  copied to `Music/OneDrive/<OneDrive folder>/<file>`. MediaStore indexes it, and it shows up in
  Songs, Albums, Artists and Folders with its own tags, cover art and ReplayGain. From then on the
  song plays from that copy, without network. Songs saved offline get a check mark in the browser.
- **Sound quality.** Nothing is transcoded. The original file bytes go to Android's own decoders
  (the same ones that play local files), so lossless files stay lossless. Gapless playback keeps
  working: the next OneDrive song is downloaded ahead and prepared before the current one ends.
- **Ogg recordings with video in them** (e.g. a screen recording with Theora video and Vorbis
  audio) play too. Android's Ogg reader refuses such a file as a whole because its first stream is
  video, so Vinyl gives the player only the audio stream's pages (`OggAudioOnly`): untouched audio,
  no re-encoding. The whole file has to download first, and the offline copy keeps only the audio
  (a one-hour screen recording: 23.9 MB instead of 240 MB). Local files like this play the same way.
- **While a song loads** it is shown at once (title, cover; "buffering" on the lock screen and in
  media controls) and the previous song stops. A notification shows the progress (percent and
  MB), and a short message repeats in the app every few seconds; a recording with video in it says
  why the whole file is downloaded first. Nothing is shown when a song starts within a second.
- **Light on resources.** No player engine or HTTP library is added; Microsoft Graph is called
  with `HttpURLConnection`. One download thread per song in use (the playing one and the next
  one); a song skipped before it finished downloading stops downloading after 20 seconds and its
  partial file is deleted.
- **Least privilege.** Vinyl asks for `Files.Read` (plus `offline_access`, `openid`, `profile`):
  it only reads the drive, it never changes or deletes anything in OneDrive.

## Setting up OneDrive

OneDrive sign-in is an OAuth 2.0 authorization-code flow with PKCE for a public client (no client
secret), run in a Custom Tab through [AppAuth](https://github.com/openid/AppAuth-Android). It is
the same setup as Sabangau's cloud sync, so an existing Sabangau app registration can be reused by
adding Vinyl's package to it (step 2), or you can create a separate one so the consent screen
shows the right app name.

1. In the [Azure portal](https://portal.azure.com/): *Microsoft Entra ID → App registrations →
   New registration*.
   - Name: `Vinyl Music Player` (users see it on the consent screen)
   - Supported account types: **Accounts in any organizational directory and personal Microsoft
     accounts**
2. *Authentication → Add a platform → Android*, once per package name and signing key:
   - Package name: `com.poupa.vinylmusicplayer` (debug builds: `com.poupa.vinylmusicplayer.debug`,
     CI builds: `com.poupa.vinylmusicplayer.ci`)
   - Signature hash: Base64 of the SHA-1 of the signing certificate, for example for the debug key
     ```sh
     keytool -exportcert -alias androiddebugkey -keystore ~/.android/debug.keystore \
       -storepass android | openssl sha1 -binary | openssl base64
     ```
     Use the release keystore and alias for release builds. Azure then lists the redirect URI
     `msauth://<package name>/<URL-encoded signature hash>`, which is the one the app sends: it
     computes the hash of the certificate it was installed with. A build signed with a key that is
     not registered fails sign-in with a redirect URI mismatch (`AADSTS50011`).
3. *API permissions → Microsoft Graph → Delegated*: `Files.Read`, `offline_access`, `openid`,
   `profile`. (A registration made for Sabangau already lists `Files.ReadWrite`; that is fine, Vinyl
   only requests `Files.Read`.)
4. Copy the **Application (client) ID** (a GUID) from *Overview*.

A build without a client id works as before: the OneDrive entry is simply not shown. It is also
hidden on Android 5 and older.

## Building with the client id

Pass the client id in any of these ways (first match wins):

```sh
./gradlew assembleDebug -Pvinyl.onedriveClientId=00000000-0000-0000-0000-000000000000
```

```sh
export VINYL_ONEDRIVE_CLIENT_ID=00000000-0000-0000-0000-000000000000
```

```properties
# local.properties (not committed)
vinyl.onedriveClientId=00000000-0000-0000-0000-000000000000
```

The build rejects values that are not a GUID.

## How it works

All OneDrive code lives in `app/src/main/java/com/poupa/vinylmusicplayer/onedrive/`, the browser in
`ui/fragments/mainactivity/onedrive/`.

| Piece | Role |
|---|---|
| `OneDriveAuth`, `OAuthTokens`, `TokenStore`, `Http` | Sign-in and token refresh (adapted from Sabangau). Tokens are AES-GCM encrypted with an Android Keystore key |
| `OneDriveClient`, `DriveItem` | Microsoft Graph: list a folder (with the audio metadata Graph extracts), resolve a download URL |
| `AudioFormats` | Which extensions are playable, and their MIME types |
| `OneDriveScanner` | Library scan with Microsoft Graph's delta listing (as the OneDrive sync clients do): the first scan pages through the whole drive 1000 items at a time keeping only folders and music; later scans fetch only the changes since the saved delta link |
| `OneDriveLibrary`, `LibraryNaming` | `onedrive.db`: the drive's music files and folder tree, their song ids, whether they are in the library and where their offline copy is; library naming that follows MediaStore's conventions |
| `FormatSniffer` | Works out which bytes the decoder reads while preparing: ID3v2 tags and cover art, FLAC metadata blocks, Ogg header packets and last page, the MP4 `moov` index (before or after the audio), the WAV header |
| `OneDriveStream`, `OneDriveStreams`, `OneDriveDataSource` | Downloads a song into the cache in that order (head, tail, rest) with HTTP range requests, retries with backoff and fresh URLs, and feeds it to `MediaPlayer` through a `MediaDataSource` |
| `OfflineSaver` | Copies a complete download to `Music/OneDrive` (MediaStore on Android 10+, the shared Music folder on 6–9, app storage as a last resort); an identical earlier copy is reused, not duplicated |
| `OneDrive` | Entry point for the player, the library and the menus |

**Song ids.** OneDrive songs have ids below −1,000,000,000, so they never collide with MediaStore
ids. The playing queue, history and play counts keep them across restarts (`Discography` resolves
them through `OneDriveLibrary`).

**Library.** `Discography` holds OneDrive songs in memory only (`replaceOneDriveSongs`); they are
never treated as MediaStore orphans. They are put back on start-up and after every library sync,
and a song whose offline copy MediaStore has indexed is left out, so it is never listed twice.

**Playback.** `MusicService` prepares tracks while holding its lock, and preparing reads the file.
So a OneDrive song is never handed to the player before the bytes the decoder needs are on the
phone: the playback thread waits for them first (`playSongAtImpl`), and the next track is
prefetched and only set up for gapless playback once it is ready (`prepareNextImpl`). Reads past
the downloaded part (a seek ahead) wait for the download.

**Menus.** Actions that need a MediaStore file (add to playlist, favorite, tag editor, share,
ringtone, delete, details, go to album/artist) use the song's offline copy once it exists; before
that they explain that the song first has to be played.

**Privacy and backups.** The encrypted tokens (`onedrive_tokens.xml`) and `onedrive.db` are
excluded from Android backup and device transfer (`res/xml/backup_rules.xml`,
`res/xml/data_extraction_rules.xml`). Signing out deletes the tokens on the device; songs already
saved offline stay in the library. Microsoft offers no per-app revocation for public clients;
users can remove the app at
[account.microsoft.com → Privacy → App access](https://account.microsoft.com/privacy/app-access).

## Limitations

- Read-only browsing of your own drive; "Shared with me" is not listed.
- Seeking ahead of the downloaded part waits until the download reaches that point.
- A song that changes on OneDrive after it was saved keeps playing from the saved copy.
- Downloads run while the music service runs; a partial download does not resume after the app
  is closed (it starts again on the next play).
- If the library whitelist is on and excludes `Music/OneDrive`, saved songs still play from the
  OneDrive browser but do not appear in the library.
- The OneDrive strings are English only (`res/values/strings_onedrive.xml`).
