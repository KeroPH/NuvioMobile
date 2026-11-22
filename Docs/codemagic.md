# Building an unsigned iOS IPA on Codemagic

`codemagic.yaml` (repo root) contains the workflow **`ios-unsigned-ipa`**, a port of
`.github/workflows/ios-test-build.yml` with the Release configuration: Xcode 27.1, Java 17,
"full" distribution, unsigned. It produces `build/ios-ipa/nuvio-<MARKETING_VERSION>-full-release.ipa`
(the version comes from `iosApp/Configuration/Version.xcconfig`, e.g.
`nuvio-1.1.0-beta.2-full-release.ipa`).

## 1. Push the branch to your own fork

`origin` points at `NuvioMedia/NuvioMobile` (upstream), which you cannot push to, so push to a fork.

Before pushing, commit everything the build needs. Codemagic only sees committed files, so
untracked files such as the new stream-cache sources will be missing otherwise:

```bash
git add -A            # review with `git status` first
git commit -m "Stream cache + Codemagic workflow"
```

With the GitHub CLI:

```bash
gh repo fork --remote --remote-name fork   # creates <you>/NuvioMobile and adds remote "fork"
git push -u fork feature/stream-cache
```

Without the GitHub CLI: fork on github.com, then

```bash
git remote add fork https://github.com/<you>/NuvioMobile.git
git push -u fork feature/stream-cache
```

## 2. Add the app to Codemagic

1. Sign in at <https://codemagic.io> with GitHub and choose **Add application**.
2. Pick GitHub and select `<you>/NuvioMobile`. Choose **codemagic.yaml** as the configuration type
   (not the Flutter workflow editor).
3. Codemagic reads `codemagic.yaml` from the branch you build, so the file must be on
   `feature/stream-cache`.

## 3. (Optional) runtime secrets

Without secrets the app still builds, but Supabase, TMDB and Sentry values are empty.
To use the same secret as the GitHub workflow:

1. On macOS or Linux, run `base64 < local.properties | tr -d '\n'`. On Windows, use
   `[Convert]::ToBase64String([IO.File]::ReadAllBytes("local.properties"))`.
2. In Codemagic go to **App settings > Environment variables** and add `NUVIO_LOCAL_PROPERTIES_BASE64`
   with that value. Mark it **Secret** and put it in a group named `nuvio_secrets`.
3. In `codemagic.yaml`, uncomment the `groups:` / `- nuvio_secrets` lines, then commit and push.

The build decodes the value into `local.properties` and removes `sdk.dir` and
`NUVIO_RELEASE_STORE_FILE`, as the GitHub workflow does. Gradle also reads
`NUVIO_SUPABASE_URL`, `NUVIO_SUPABASE_ANON_KEY`, `NUVIO_SUPABASE_FALLBACK_URL`, `SENTRY_DSN`
and `TMDB_API_KEY` straight from environment variables, so you can put those keys in the group
instead of the base64 file.

## 4. Run the build

On the app page click **Start new build**, select branch `feature/stream-cache` and workflow
**iOS unsigned full Release IPA** (`ios-unsigned-ipa`), then start it. It runs on a Mac mini M2;
a cold Release build (Kotlin/Native and Gradle caches empty) can take 45–90 minutes. Later builds
reuse the cached `~/.konan` and `~/.gradle/caches`. The build times out after 120 minutes.

## 5. Download the IPA

When the build finishes, open it and download `nuvio-…-full-release.ipa` from the
**Artifacts** section in the build overview (top right of the build page). The **Summarize IPA**
step in the log shows the file name, size and SHA-256.

## 6. Sideload

The IPA is **unsigned**. Install it the same way you installed `nuvio-0.6.13-full-release-localplayer.ipa`:

- **AltStore / SideStore**: open the IPA in the app (or use My Apps → +). It is signed with your
  Apple ID, and free accounts must refresh every 7 days.
- **Sideloadly**: drag the IPA in, enter your Apple ID and install over USB or Wi-Fi.
- **TrollStore** (supported iOS versions only): install the IPA directly. It is permanent and
  needs no re-signing.

If you install it over the old build with the same bundle ID, your data is kept. Sideloading tools
may change the bundle ID, and in that case it installs as a separate app.

## Notes / hidden requirements handled by the workflow

- `MPVKit` is a git submodule (`NuvioMedia/MPVKit`, branch `Nuvio`) that Xcode uses as a local
  Swift package (`../MPVKit`). The workflow initializes only that submodule. The repo also contains
  an orphan `libass-android` gitlink with no `.gitmodules` entry, so a bare
  `git submodule update --init --recursive` fails with
  `fatal: no submodule mapping found ... 'libass-android'`.
- `scripts/prepare-ios-dependencies.sh` downloads `NuvioEngine.xcframework` v0.1.6 from GitHub
  releases (checksum-verified) into `../nuvio-engine` (outside the clone directory). The path is
  hard-coded in `composeApp/build.gradle.kts`, so do not change `NUVIO_ENGINE_ROOT`.
- Xcode's "Compile Kotlin" build phase runs `./gradlew :composeApp:embedAndSignAppleFrameworkForXcode`
  using `JAVA_HOME` (Java 17, provided by `java: 17`).
- No Homebrew packages or Git LFS are required.
