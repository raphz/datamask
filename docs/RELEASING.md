# Releasing DataMask

Releases are cut by **publishing a GitHub release**. That fires the Release workflow
(`.github/workflows/release.yml`), which checks out the released tag, verifies the build and
publishes every module to Maven Central.

The version is decided in exactly one place — the tag on the release you publish. Axion reads it
back out, and the workflow refuses to continue if the tag does not resolve to a clean release
version, so a mislabelled artifact cannot reach Central.

Because the release is already public when the workflow starts, a failed upload would otherwise
leave a release advertising a version that never arrived on Central. The workflow puts the release
back into **draft** if publishing fails; fix the cause and publish it again to re-run.

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

First create the tag on `main`, at the commit being released:

```bash
git checkout main && git pull
./gradlew createRelease -Prelease.versionIncrementer=incrementMinor   # or incrementPatch / incrementMajor
# or, for an exact version:
./gradlew createRelease -Prelease.forceVersion=1.0.0

git push origin "v$(./gradlew -q currentVersion -Prelease.quiet)"
```

Then **Releases → Draft a new release**, pick that tag, generate the notes, and **Publish**.

Publishing fires the workflow, which:

1. refuses to continue if the release is marked as a pre-release — Central has no equivalent;
2. checks out the tag and resolves the version from it, refusing anything that is not a clean
   release version;
3. builds and tests (`./gradlew build`, which includes `spotlessCheck`);
4. runs `publishAndReleaseToMavenCentral` — signs every artifact, uploads the bundle and waits for
   the portal's validation;
5. attaches the jars to the release.

The tag must be `v<version>`, matching axion's tag prefix. `createRelease` produces exactly that,
which is why it is worth using rather than tagging by hand.

Artifacts usually appear on `repo1.maven.org` within 10–30 minutes of the portal accepting them.

If the workflow fails, the release is returned to draft. Fix the cause and publish it again.

## Publishing locally

Possible, but the workflow is the supported path — it is the only route that also verifies the
build and keeps the GitHub release and Central in step. If you must:

```bash
export ORG_GRADLE_PROJECT_mavenCentralUsername=...
export ORG_GRADLE_PROJECT_mavenCentralPassword=...
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --armor --export-secret-keys <KEY_ID>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=...

git checkout "v1.0.0"     # publish from the tag, or the version carries a snapshot suffix
./gradlew clean publishAndReleaseToMavenCentral
```

`./gradlew build` needs none of these. Signing and credentials are only touched by the publish
tasks, so day-to-day development is unaffected.
