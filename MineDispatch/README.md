# Mine Dispatch – Android wrapper

1. Save your HTML file as:  app/src/main/assets/index.html   (unchanged)
2. Open this folder in Android Studio (Koala or newer) and let Gradle sync.
3. Build > Build APK(s), or Run on a device.
   Release APK: Build > Generate Signed Bundle / APK.

Requires Android 8.0 (API 26)+.

Notes
- Data lives in the app's WebView IndexedDB. Uninstalling the app or clearing
  its storage deletes it. Use Reports > Export backup regularly. Backups and
  CSVs are saved to the phone's Downloads folder.
- The app loads the page from https://appassets.androidplatform.net/ (local
  assets only, no internet needed).

## Build the APK without Android Studio (GitHub Actions)
1. Put your HTML at app/src/main/assets/index.html
2. Create a private GitHub repo and push this whole folder (including .github/).
3. Open the repo's Actions tab > "Build APK" > wait ~5 min.
4. Download the "MineDispatch-apk" artifact, unzip, copy app-debug.apk to the phone.
5. On the phone allow "Install unknown apps" for your file manager/browser, then tap the APK.
