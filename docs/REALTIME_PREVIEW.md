# LP2LN direct Flow preview on Android

The experimental JNI bridge is gated by Cargo feature realtime-preview. The default Android build still resolves the published Core Git pin. The feature requires a local Core checkout with F2/M1 code, so it remains off in builds using the published pin.

## Isolated build

1. Copy the Android working tree and preserve its lock. In the copy, replace the Core git dependency with a path to the exact Core checkout under test. Remove the root h3-quinn patch for the old Core pin because the local Core has its own vendored dependency. Resolve the isolated lock once, then use --locked for subsequent builds. The original Android manifest and published-pin lock stay intact.
2. Build the JNI bridge with Rust 1.100.0, NDK 27.3.13750724 and --features realtime-preview. Example using cargo-ndk from the copied rust directory:

~~~bash
cargo ndk -t arm64-v8a -p 29 -o ../app/build/generated/lp2ln/jniLibs \
  build --locked --features realtime-preview
~~~

3. Build the two debug APKs from the copied Android tree using JDK 17, SDK 34 and Gradle 8.0:

~~~bash
./gradlew :app:assembleDebug :app:assembleDebugAndroidTest :app:testDebugUnitTest \
  -PrustAbis=arm64-v8a -PskipRustBuild \
  -PapplicationIdOverride=lp2ln_android.krusalov.org.realtimepreview
~~~

## Two physical Android devices

Use two arm64 devices on the same WLAN with client isolation disabled. The preview has its own Android application ID and data directory. Install both APKs on each. Keep the tests in a dedicated directory and keep the processes active until results appear. Both devices may run without Internet as long as a route between them exists. Replace the illustrative variables with the device serials, Peer IDs and address observed on those devices.

~~~bash
adb -s "$listener_serial" install -r LP2LN_Realtime_Preview_arm64.apk
adb -s "$listener_serial" install -r LP2LN_Realtime_Device_Test.apk
adb -s "$dialer_serial" install -r LP2LN_Realtime_Preview_arm64.apk
adb -s "$dialer_serial" install -r LP2LN_Realtime_Device_Test.apk

test_case=lp2ln_android.krusalov.org.network.FlowDuplexDeviceTest
runner=lp2ln_android.krusalov.org.realtimepreview.test/androidx.test.runner.AndroidJUnitRunner
adb -s "$dialer_serial" shell am instrument -w -e class "$test_case" \
  -e flowRole identity "$runner"
adb -s "$listener_serial" shell am instrument -w -e class "$test_case" \
  -e flowRole identity "$runner"
~~~

The identity run prints each persistent flowPeerId. Start the listener test in its own terminal using the dialer's ID. It prints a new flowPort and waits up to 120 seconds. Use that port and the listener's LAN IP when starting the dialer.

~~~bash
adb -s "$listener_serial" shell am instrument -w -e class "$test_case" \
  -e flowRole listener -e flowPeer "$dialer_peer_id" "$runner"

adb -s "$dialer_serial" shell am instrument -w -e class "$test_case" \
  -e flowRole dialer -e flowPeer "$listener_peer_id" \
  -e flowAddress "$listener_ip:$flow_port" "$runner"
~~~

The test checks the expected peer and exchanges numbered payloads in both directions at 50 PPS. Each device requires at least 190 distinct, correct packets out of 200 offers and prints flowDelivered. A build or successful test on Linux does not count as a device run.

## lp2ln-rtc integration contract

The Rust application receives one authenticated Flow scoped to the expected remote PeerId and an application-owned protocol number. The separate RTC layer handles signaling, consent, media capture, playback, Opus, clock recovery, jitter buffering and audio focus. It exchanges encoded frames over Core's unordered sender and passes its local capture/playout deadlines to admission. The receiver obtains authenticated bytes through Flow::recv and supplies them to the application jitter buffer. Datagram admission and remote playout are distinct. Read the current max payload before encoding. The preview Flow terminates on path changes and its calibrated age policy is bounded, so the RTC lifecycle needs explicit reconnect and renewal before longer calls.

JNI exposes open, accept, receive, offer and close for the device harness. The receiving call returns null on timeout and raises on a closed handle. Offer returns 0 for local transport admission, 1 for closed, 2 for deadline, 3 for clock uncertainty, 4 for MTU, 5 for full queue, 6 for pacing and 7 for other transport errors. Run JNI blocking operations off the UI or audio callback thread. Production RTC integration should expose key renewal and avoid the preview JNI payload copy in the audio callback.

The newer arm64 voice build includes a screen-driven duplex test, a duration-configurable instrumentation test and a direct Opus call. See [M2 installation, offline tests and logs](M2_ANDROID.md).
