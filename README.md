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
* **Or no library at all:** [Directory view](#directory-view) lets you browse your folders like a file explorer and
  open comic files directly.
* **Several series folders**, merged into one library.
* **An optional folder for covers and metadata**, so the series folders stay untouched.
* **Background music** from audio files inside a chapter.
* Library updates rescan your folders and run without a network connection.

## Getting started

1. Install the APK and open Myhon.
2. During setup, choose how to open your comics: with the **Library** (the default) or in
   [**Directory view**](#directory-view). For directory view, also allow access to all files when asked. Then pick a
   **storage location**: any folder you like, for example `Documents/Myhon`. Myhon creates a `local` folder in it.
3. In library view, put each series in its own folder inside `local`, with one chapter per entry (directory view
   reads your comics wherever they are, so you can skip this step):

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
4. In library view, open the Library. Your series appear on their own; pull down to update the library after you add
   chapters. In directory view, open **Folders** and browse to a comic file instead.

## Storage

The storage location chosen during setup holds:

* `local`: the default series folder.
* `autobackup`: automatic backups.

You can change it in **Settings → Data and storage → Storage location**. Series are read in place; Myhon never moves
your files.

Inside a series folder, Myhon may write:

* `cover.jpg`, taken from the first chapter when the series has no cover image. Setting a cover writes the new image
  there too, replacing an existing `cover.*` image.
* `ComicInfo.xml`, when it saves the metadata of an archive, or when it converts an old `details.json`, which it then
  deletes.
* `.noxml` and a `.nomedia` file.

To keep series folders free of these changes, see [Covers and metadata folder](#covers-and-metadata-folder).

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

## Directory view

Directory view replaces the library with a file explorer: you browse your device and open comic files directly.
Choose it during setup, or turn on **Settings → Data and storage → Directory view**. The first tab is then
**Folders** instead of **Library**.

* The top level lists the storage of your device, like a file explorer: internal storage first, then SD cards and USB
  drives by name, each with its free and total space. Pull down to list them again after you insert or remove one.
* To browse the whole device, Myhon needs **All files access** on Android 11 and later (the system folder picker
  refuses the root of internal storage), or the storage permission on Android 8 to 10. Myhon only reads your files.
  Allow it during setup, with **Allow access** in **Folders**, or in **Settings → Data and storage → Access to all
  files**; on Android 11 and later these open the system setting. On Android 8 to 10 you can also grant it in
  **App info → Permissions → Storage**. Some folders stay hidden from apps even then, such as `Android/data` and
  `Android/obb`; opening them says the folder couldn't be read.
* A folder shows its subfolders, then its comic files, each by name in natural order (`Chapter 2` before
  `Chapter 10`). Comic files are archives (`zip`, `cbz`, `rar`, `cbr`, `7z`, `cb7`, `tar`, `cbt`) and `epub`.
  Chapters saved as folders of images are not supported: folders are only browsed. Other files, and names that start
  with a dot, are not shown.
* Back goes up one folder, tapping **Folders** again goes back to the top, and pulling down lists the folder again.
* Each file shows a thumbnail of its first page. Thumbnails are kept in a cache of the app, never in your folders. The
  cache holds up to 64 MB of them, drops the least recently used ones when it is full, and Android may clear it when
  storage runs low.
* Directory view only reads your folders. It writes no `cover.jpg`, `ComicInfo.xml`, `.noxml` or `.nomedia`.
* Files you started show the page you stopped on, and finished ones are marked **Read**.
* There are no series pages. In the reader, the next and previous chapters are the next and previous files of the same
  folder, so you can read on through a folder; files in subfolders are not included. Background music works as in the
  library. The title doesn't open a series page, and files can't be bookmarked or set as a cover. The reading mode and
  orientation you choose in the reader are remembered for each folder.
* **History** lists the files you read rather than series: newest first and grouped by day, each file once, by its
  file name with its extension (`Akira v01.cbz`), with its first page, the page you stopped on or **Read**, and when
  you read it. No folder, series or chapter is shown. Tapping an entry opens the file where you left off (a finished
  file from its first page); if the folder can't be read or the file is gone, Myhon says so. Search finds files by
  name. The delete icon of an entry removes it and **Clear history** removes them all; both also forget how far those
  files were read, so **Folders** no longer marks them.
* At the top level, **Resume** in the bottom right corner goes on from the file you read last: that file if you didn't
  finish it, otherwise the next file of its folder. Tapping **History** again while it is shown does the same. The
  button is there whenever History lists a file.
* Directory view's history and the reading modes of folders are not part of backups.

Switching to directory view keeps your library as it is, with its categories and reading progress, but the library is
no longer shown, updated or matched with your folders: library updates, automatic ones included, don't run, and
**Categories** in More, the **Series folders** settings with **Folder for covers and details**, and **Refresh library
covers** are hidden. When you switch back, the library catches up with your folders. Each mode keeps its own History,
and switching keeps both: files read in directory view are never listed in the library's.

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
