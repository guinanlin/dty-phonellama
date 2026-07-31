# Release signing

Release APKs must use the same private key for the lifetime of the application.
Never commit the keystore, passwords, or the base64 value to Git.

## Create the keystore once

Run this on a secure machine with JDK 21 (the command prompts for passwords):

```bash
keytool -genkeypair -v \
  -keystore phonellama-release.jks \
  -storetype PKCS12 \
  -alias phonellama \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000
```

Back up `phonellama-release.jks` and its passwords in a password manager. Losing
the private key means future APKs cannot update existing installations.

## Configure GitHub Actions secrets

In the repository, open **Settings → Secrets and variables → Actions → New
repository secret** and create:

- `PHONELLAMA_KEYSTORE_BASE64`: output of
  `base64 -w 0 phonellama-release.jks`
- `PHONELLAMA_STORE_PASSWORD`: keystore password
- `PHONELLAMA_KEY_ALIAS`: `phonellama`
- `PHONELLAMA_KEY_PASSWORD`: key password

The workflow decodes the keystore only inside the ephemeral runner and deletes
it with the runner after the job. The keystore is never uploaded as an
artifact.

## Publish a signed release

Push a semantic version tag:

```bash
git tag -a v1.0.15 -m "PhoneLlama v1.0.15"
git push origin v1.0.15
```

The tag build uses `assembleRelease`, sets the APK version from the tag, and
attaches the signed APK to the GitHub Release. Branch and pull-request builds
remain unsigned debug builds for validation only.
