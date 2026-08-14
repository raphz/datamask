# Releasing DataMask

Releases are cut by the **Release** workflow (`.github/workflows/release.yml`), run manually from
the Actions tab against `main`. It verifies the build, tags the commit, publishes every module to
Maven Central, and only then pushes the tag and creates the GitHub release.

The ordering is deliberate: the tag is created **locally**, and pushed only after Central has
accepted the bundle. A rejected upload therefore leaves nothing published behind, and the run can
simply be repeated.

## One-time setup

### 1. Claim the namespace on the Central Portal

Sign in at [central.sonatype.com](https://central.sonatype.com) and register the namespace matching
the published group id, which is currently **`ch.raph`** (from `group = 'ch.raph.datamask'`).

Central will only grant a namespace you can prove you control:

| Namespace | How it is verified |
|---|---|
| `ch.raph` | A DNS `TXT` record on `raph.ch` containing the code the portal shows you |
| `io.github.raphz` | A temporary public GitHub repository named after the code the portal shows you |

**If `raph.ch` is not yours, the group id has to change to `io.github.raphz` before the first
release** — in `build.gradle` (`allprojects { group = ... }`) and in the README's coordinates. A
group id cannot be changed after artifacts are published under it, so decide this before releasing
`0.1.0`, not after.

### 2. Generate the signing key

Central rejects unsigned artifacts, and the public half must be resolvable on a keyserver.

```bash
gpg --full-generate-key            # RSA 4096, no expiry or a long one
gpg --list-secret-keys --keyid-format=long

# Publish the public half — Central verifies signatures against it.
gpg --keyserver keyserver.ubuntu.com --send-keys <LONG_KEY_ID>

# Export the private half. This whole block, BEGIN/END lines included, is the secret.
gpg --armor --export-secret-keys <LONG_KEY_ID>
```

### 3. Generate a Portal publishing token

On the Central Portal: **your account → Generate User Token**. This produces a username/password
pair that is *not* your portal login — the login itself will not authenticate the upload.

### 4. Add the repository secrets

**Settings → Secrets and variables → Actions → New repository secret.**

| Secret | Value | Comes from |
|---|---|---|
| `MAVEN_CENTRAL_USERNAME` | Portal token username | Step 3 |
| `MAVEN_CENTRAL_PASSWORD` | Portal token password | Step 3 |
| `SIGNING_KEY` | The ASCII-armored private key, including the `-----BEGIN PGP PRIVATE KEY BLOCK-----` and `-----END-----` lines | Step 2 |
| `SIGNING_KEY_PASSWORD` | The passphrase protecting that key | Step 2 |

`GITHUB_TOKEN` is provided by Actions automatically and needs no setup; it is what pushes the tag
and creates the release.

The workflow hands these to Gradle as `ORG_GRADLE_PROJECT_*` environment variables, which is how a
Gradle project property is set without ever writing a secret to a file:

```
MAVEN_CENTRAL_USERNAME  -> ORG_GRADLE_PROJECT_mavenCentralUsername
MAVEN_CENTRAL_PASSWORD  -> ORG_GRADLE_PROJECT_mavenCentralPassword
SIGNING_KEY             -> ORG_GRADLE_PROJECT_signingInMemoryKey
SIGNING_KEY_PASSWORD    -> ORG_GRADLE_PROJECT_signingInMemoryKeyPassword
```

If the exporting keyring ever holds more than one secret key, add
`ORG_GRADLE_PROJECT_signingInMemoryKeyId` as well. With a single key it is unnecessary.

## Cutting a release

**Actions → Release → Run workflow**, on `main`:

- **increment** — `patch`, `minor` (default) or `major`. Maps to axion's
  `incrementPatch` / `incrementMinor` / `incrementMajor`.
- **version** — an exact version such as `1.0.0`. Overrides the increment. Use it for the first
  `1.0.0` and for anything else the incrementer would not arrive at on its own.

The run then:

1. builds and tests (`./gradlew build`, which includes `spotlessCheck`);
2. creates the tag locally with `createRelease`, so axion resolves the plain release version;
3. runs `publishAndReleaseToMavenCentral` — rebuilds at that version, signs every artifact, uploads
   the bundle and waits for the portal's validation;
4. pushes the tag;
5. creates the GitHub release with generated notes and the jars attached.

Artifacts usually appear on `repo1.maven.org` within 10–30 minutes of the portal accepting them.

## Releasing locally

Possible, but the workflow is the supported path. If you must:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=...
export ORG_GRADLE_PROJECT_mavenCentralPassword=...
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...

./gradlew createRelease -Prelease.versionIncrementer=incrementMinor
./gradlew clean publishAndReleaseToMavenCentral
git push origin "v$(./gradlew -q currentVersion -Prelease.quiet)"
```

`./gradlew build` needs none of these. Signing and credentials are only touched by the publish
tasks, so day-to-day development is unaffected.
