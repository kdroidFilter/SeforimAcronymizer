# SeforimAcronymizer

Kotlin Multiplatform Library

### Run Sample App

 - Desktop JVM: `./gradlew :sample:composeApp:run`

### Publish to MavenLocal

1) Run `./gradlew :acronymizer:publishToMavenLocal`
2) Open `~/.m2/repository/io/github/kdroidfilter/seforim/acronymizer/`

### Publish to MavenCentral

1) Create an account and a namespace on Sonatype:  
   https://central.sonatype.org/register/central-portal/#create-an-account
2) Add developer id, name, email and the project url to  
   `./acronymizer/build.gradle.kts`
3) Generate a GPG key:  
   https://getstream.io/blog/publishing-libraries-to-mavencentral-2021/#generating-a-gpg-key-pair
   ```
   gpg --full-gen-key
   gpg --keyserver keyserver.ubuntu.com --send-keys XXXXXXXX
   gpg --export-secret-key XXXXXXXX > XXXXXXXX.gpg
   ```
4) Add these lines to `gradle.properties`:
   ```
   signing.keyId=XXXXXXXX
   signing.password=[key password]
   signing.secretKeyRingFile=../XXXXXXXX.gpg
   mavenCentralUsername=[generated username]
   mavenCentralPassword=[generated password]
   ```
5) Run `./gradlew :acronymizer:publishAndReleaseToMavenCentral --no-configuration-cache`
