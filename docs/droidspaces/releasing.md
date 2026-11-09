# Publishing a DroidDeckSpace release

The in-app updater reads one JSON document from the `main` branch:

```
https://raw.githubusercontent.com/fkun1754/DroidDeckSpace/main/catalog.json
```

Only the **stable** channel is offered. The catalog root is validated strictly; it must contain:

```json
{
  "schema": 1,
  "sourceRepo": "fkun1754/DroidDeckSpace",
  "ciRepo": "fkun1754/DroidDeckSpace",
  "stable": { ... },
  "preview": null,
  "tests": [],
  "recentPreviews": [],
  "checkedAt": 1794240477000
}
```

`stable` is the same shape the app caches:

```json
{
  "tag": "0.7.0",
  "title": "DroidDeckSpace 0.7.0",
  "summary": "Short one-liner",
  "commit": "<40-char commit the APK was built from>",
  "pr": 0,
  "version": "0.7.0",
  "versionCode": 18,
  "publishedAt": 1794239386377,
  "url": "https://github.com/fkun1754/DroidDeckSpace/releases/tag/0.7.0",
  "apks": {
    "com.droiddeckspace.launcher": {
      "name": "DroidDeckSpace-0.7.0.apk",
      "url": "https://github.com/fkun1754/DroidDeckSpace/releases/download/0.7.0/DroidDeckSpace-0.7.0.apk",
      "size": 103318975,
      "sha256": "<sha256 of the APK>",
      "packageName": "com.droiddeckspace.launcher",
      "versionCode": 18,
      "signerSha256": "a40da80a59d170caa950cf15c18c454d47a39b26989d8b640ecd745ba71bf5dc"
    }
  }
}
```

Rules the app enforces:

- `schema` must be `1`, `sourceRepo`/`ciRepo` must match the repository the app was built for.
- `stable.commit` must be the commit the APK was built from (the APK shows it in the update page and
  carries it in `BuildConfig.SOURCE_COMMIT`), otherwise the build is reported as ahead/behind.
- The APK `signerSha256` must equal the signer the app trusts (`keystore/release-signer.sha256`),
  and the download URL must start with
  `https://github.com/fkun1754/DroidDeckSpace/releases/download/`.
- `versionCode` in the APK entry must equal the release `versionCode`.

Release checklist:

1. Commit the code, build the APK from that commit.
2. Create the release and upload `DroidDeckSpace-<version>.apk`.
3. Update `catalog.json` (commit/sha256/size/versionCode/tag), commit and push `main`.
4. Sanity check: the raw catalog URL returns the new JSON and the release asset digest matches
   `sha256`.
