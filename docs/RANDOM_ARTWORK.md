# Random artwork

Songs that have no artwork of their own get a random photo from [Unsplash](https://unsplash.com)
when they are played.

## What it does

- **Only songs without artwork.** Vinyl first looks for a cover embedded in the file, then the
  album art MediaStore has, then a `cover.jpg` / `folder.jpg` / `album.jpg` next to the file. Only
  when all of these are missing is a photo fetched.
- **Only the song being played** triggers a download, and only as *Settings → Images → Auto
  download images* allows (Wi-Fi only by default). The request asks Unsplash for a random squarish
  "music" photo; nothing about the song is sent.
- **One photo per song, kept.** The photo is saved on the device (`files/random_artwork`, newest
  400 kept), so the song keeps the same photo, also offline, and it shows wherever the song does:
  player, notification, lock screen, widgets and lists. It is keyed by title, album and artist, so a
  OneDrive song and its offline copy share it.
- **Light.** One 720×720 JPEG (about 100 KB) per song. Failures back off (10 minutes per song, 15
  minutes for Unsplash as a whole after a refusal or a network error), so the many cover requests of
  the playing song never pile up.
- *Settings → Images → Random artwork* turns it off.

## Setting up Unsplash

1. Create an application at [unsplash.com/oauth/applications](https://unsplash.com/oauth/applications).
   A new application is in *Demo* mode: 50 requests per hour. Each new photo uses two (the photo,
   and the download report the [API guidelines](https://help.unsplash.com/en/articles/2511245-unsplash-api-guidelines)
   ask for), so about 25 new songs per hour; photos already saved cost nothing.
2. Pass its **Access Key** to the build, in any of these ways (first match wins):

   ```sh
   ./gradlew assembleDebug -Pvinyl.unsplashAccessKey=YOUR_ACCESS_KEY
   ```

   ```sh
   export VINYL_UNSPLASH_ACCESS_KEY=YOUR_ACCESS_KEY
   ```

   ```properties
   # local.properties (not committed)
   vinyl.unsplashAccessKey=YOUR_ACCESS_KEY
   ```

Only the Access Key is used. The **Secret Key** is for Unsplash's user sign-in, which Vinyl does
not use: never put it in the build (anything built into an app can be extracted from it).

A build without an access key has no random artwork; everything else works as before.

## How it works

`glide/audiocover/RandomArtwork.java`, called last by `SongCoverFetcher` (the loader behind every
song cover). `MusicService` tells it which song is playing before the cover is redrawn. Photos are
hotlinked from Unsplash's image CDN at the size needed (`fit=crop`), then `download_location` is
called to credit the photographer, as the guidelines require.
