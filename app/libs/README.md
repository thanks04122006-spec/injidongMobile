# RealSense Android SDK

`librealsense.aar` is the official RealSense Android wrapper built from librealsense 2.59.1 commit `c84fe6438bd14a4cc69f46deae92c993268232c6`.

SHA-256: `9d297093511d99b3f1fe5d69d952af3b4c174685dbfec310fb89491173ce34f5`.

The AAR contains `arm64-v8a` and `x86_64` native libraries. The app Gradle script includes this AAR when the file exists. Without it, the demo UI builds but the D405 stream adapter reports that the SDK is missing.

To rebuild it, follow the Android SDK/NDK and source-build instructions in the root README. The source commit listed above contains an enum punctuation typo in `Option.java`; the build checkout used a one-character comma correction.
