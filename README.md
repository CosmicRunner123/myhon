<div align="center">

<img src="./.github/assets/logo.png" alt="Myhon logo" title="Myhon logo" width="80"/>

# Myhon

### A reader for the manga and webtoons on your device, with background music

[![License: Apache-2.0](https://img.shields.io/badge/license-Apache--2.0-0877d2?labelColor=27303D)](/LICENSE)

*Requires Android 8.0 or higher.*

</div>

Myhon is a fork of [Mihon](https://github.com/mihonapp/mihon). It keeps Mihon's reader, library, categories, history,
trackers and backups, and changes the rest:

* **Local series only.** Extensions, online sources, Browse, global search, migration, downloads and WebView are gone.
* **The library fills itself.** Every series in your series folders is added to the library, which is the first
  screen; there is no "add to library" step.
* **Several series folders**, merged into one library.
* **An optional folder for covers and metadata**, so the series folders stay untouched.
* **Background music** from audio files inside a chapter.
* Library updates rescan your folders and run without a network connection.

## Getting started

1. Install the APK and open Myhon.
2. During setup, pick a **storage location**: any folder you like, for example `Documents/Myhon`. Myhon creates a
   `local` folder in it.
3. Put each series in its own folder inside `local`, with one chapter per entry:

   ```
   Myhon/
   └── local/
       └── My Series/
           ├── cover.jpg            (optional)
           ├── ComicInfo.xml        (optional)
           ├── Chapter 001.cbz
           ├── Chapter 002.zip
           └── Chapter 003/
               ├── 001.jpg
               └── 002.png
   ```

   A chapter can be a folder of images, an archive (`zip`, `cbz`, `rar`, `cbr`, `7z`, `cb7`, `tar`, `cbt`) or an
   `epub`.
4. Open the Library. Your series appear on their own; pull down to update the library after you add chapters.

## Storage

The storage location chosen during setup holds:

* `local`: the default series folder.
* `autobackup`: automatic backups.

You can change it in **Settings → Data and storage → Storage location**. Series are read in place; Myhon never moves
or deletes your files.

Inside a series folder, Myhon may write `cover.jpg` (when you set a cover), `ComicInfo.xml` (when it converts an old
`details.json` or saves the metadata of an archive), `.noxml` and a `.nomedia` file. To keep series folders free of
these files, see [Covers and metadata folder](#covers-and-metadata-folder).

## Series folders

Besides `local`, you can add any number of other folders in **Settings → Data and storage → Series folders → Add
folder**. Each one holds one folder per series, like `local` does.

* Series with the same folder name in several series folders are **merged** into one entry; its chapters are the
  union of the chapters in all of them.
* If the same chapter file name exists in more than one folder, the copy in the first folder is used (`local`
  first, then the added folders in the order shown in settings).
* The cover and `ComicInfo.xml` come from the first folder that has them.
* Removing a folder from the list deletes no files; series that existed only there leave the library.
* If a folder is temporarily unavailable (for example an SD card that is not inserted), its series stay in the
  library until the folder is reachable again.

## Covers and metadata folder

**Settings → Data and storage → Folder for covers and details** lets you pick a separate folder. When it is set,
Myhon writes nothing into your series folders. Everything it would have written there goes to `<that folder>/<series folder
name>/` instead (`cover.jpg`, `ComicInfo.xml`, `.noxml`), with a single `.nomedia` at the top.

Covers and `ComicInfo.xml` files that are already in your series folders keep working; the covers folder is checked
first. Choosing the default again moves nothing back.

## Background music

Put audio files inside a chapter (in the chapter folder, or anywhere inside the archive) and Myhon plays them as
background music while you read that chapter. Most audio formats work, as long as the device can play them:
`mp3`, `m4a`, `aac`, `ogg`, `opus`, `flac`, `wav`, `wma`, `mid`, `aiff` and more.

The numbers in the file name decide when a track plays. Pages are counted from 1, and ranges include both ends.

| File name | Numbers | Plays |
| --- | --- | --- |
| `theme.mp3` | none | the whole chapter |
| `07 theme.mp3` | 1: track number | the whole chapter |
| `bgm 5-12.ogg` | 2: start, stop | from page 5 to page 12 |
| `02 battle 13 40.flac` | 3: track, start, stop | from page 13 to page 40 |

* Only the numbers in the file name count: not the extension (the `3` of `.mp3` is ignored) and not the names of
  folders inside an archive. Any other characters can separate them. A fourth number and later ones are ignored.
* When several tracks cover the current page, the one that starts latest wins, so a ranged track overrides a
  whole-chapter one.
* Several tracks that start on the same page, or several whole-chapter tracks, form a playlist: they play one after
  the other in track-number order (tracks without one last), then by name.
* Music keeps playing while you turn pages inside its range, and stops when you reach a page that no track covers.

Music and looping are both on by default. Turn them off in **Settings → Reader → Music**, or from the reader's
settings sheet. With looping on, a track (or playlist) starts over when it ends; with looping off, it plays once.

## Coming from Mihon

Myhon installs next to Mihon; it does not replace it. You can restore a Mihon backup in **Settings → Data and
storage → Restore backup**. Only local series are restored; every series from another source is skipped and listed in
the restore log. Categories, read progress, history, tracking and app settings are restored as usual.

## Building

```
./gradlew assembleRelease
```

The APKs are written to `app/build/outputs/apk/release/`. Run `./gradlew check` before sending changes.

## License

<pre>
Copyright © 2015 Javier Tomás
Copyright © 2024 Mihon Open Source Project

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
</pre>
