# 인지동 측정 Android 앱

휴대폰의 후면 RGB 카메라와 IMU, USB로 연결한 Intel RealSense D405 Depth 스트림을 수집하는 네이티브 Android 앱입니다. 화면의 **데이터 수집** 버튼 하나로 수집과 로컬 저장을 시작·중지합니다.

RGB와 Depth는 서로 다른 카메라의 입력입니다. 정렬과 시각 동기화가 검증되지 않아 `unverified`로 기록합니다. 서버 API가 아직 정해지지 않아 실제 네트워크 업로드와 중량 예측은 연결되지 않았습니다.

## 기능

- 휴대폰 후면 카메라 RGB 미리보기와 이미지 저장
- D405 USB 연결, Depth(Z16) 수신 및 Depth 컬러맵 표시
- 휴대폰 가속도계, 선형 가속도계, 자이로스코프, 회전 벡터 기록
- IMU·RGB·Depth 수집을 한 버튼으로 함께 시작·중지
- 세션별 JSON/JSONL 메타데이터와 원본 이미지·Depth 배열 저장
- 수집 종료 시 세션 업로드 경계 호출

서버 업로드 인터페이스는 `SessionTransport`입니다. 현재 연결된 `UnconfiguredSessionTransport`는 서버 URL과 업로드 계약이 없음을 `SERVER_API_UNCONFIGURED`로 반환합니다. 이 상태에서 데이터는 앱 내부에 저장되며 서버로 전송되지는 않습니다. 중량도 서버의 실제 `PredictionResult`가 오기 전까지 표시하지 않습니다.

## 개발 환경

| 항목 | 설정 |
|---|---|
| Android Studio | 최신 안정 버전 권장 |
| JDK | 21 |
| Android SDK Platform | 35 |
| Android Gradle Plugin | 8.7.3 |
| Gradle Wrapper | 8.9 |
| `minSdk` / `targetSdk` | 24 / 35 |
| CameraX | 1.4.2 |

RealSense Android wrapper AAR는 `app/libs/librealsense.aar`에 포함되어 있습니다. 앱을 빌드하는 데 NDK와 CMake를 별도로 설치할 필요는 없습니다. AAR를 소스에서 다시 빌드할 때만 NDK `28.0.13004108`과 CMake `3.22.1`이 필요합니다.

## 실행 방법

### Android Studio

1. Android Studio에서 이 저장소 폴더 `android-app`을 엽니다.
2. Gradle 동기화가 끝날 때까지 기다립니다. Android SDK Platform 35가 없다면 SDK Manager에서 설치합니다.
3. 툴바에서 `app` 실행 구성을 선택하고 Android 기기 또는 에뮬레이터를 선택합니다.
4. **Run**을 누릅니다.
5. 앱이 요청하는 휴대폰 카메라 권한과 USB 장치 권한을 허용합니다.
6. D405를 USB OTG로 연결하고 **데이터 수집**을 누릅니다. 수집을 마칠 때 같은 버튼을 다시 누릅니다.

### PowerShell 명령

프로젝트 폴더에서 실행합니다.

```powershell
.\gradlew.bat assembleDebug
```

USB 디버깅을 허용한 Android 기기에 설치하고 실행하려면 다음을 사용합니다.

```powershell
.\gradlew.bat installDebug
```

생성 APK 위치:

```text
app/build/outputs/apk/debug/app-debug.apk
```

에뮬레이터는 앱 UI와 휴대폰 카메라 기능 개발에 사용할 수 있지만, 에뮬레이터가 D405 USB 장치를 Android USB Host로 전달한다고 보장되지는 않습니다. D405 실제 스트림은 USB OTG 및 전원이 충분한 실제 Android 기기에서 확인해야 합니다.

## 화면과 수집 동작

화면에는 휴대폰 RGB 미리보기와 D405 Depth 컬러맵이 별도 영역으로 표시됩니다. 앱이 실행 중일 때 휴대폰 카메라 미리보기는 켜져 있고, **데이터 수집**을 누르면 IMU 기록과 D405 스트림 수집을 시작합니다. D405가 연결되어 프레임을 받으면 Depth는 초당 최대 1프레임, RGB 이미지는 초당 1장씩 세션에 저장됩니다. IMU는 Android 센서 이벤트 주기로 기록됩니다.

수집 종료 시 앱은 세션 파일을 닫고 `SessionTransport.sendSession()`을 호출합니다. 현재는 전송 미구성 응답을 표시합니다. 네트워크 업로드를 활성화하려면 서버 URL, 인증 방식, 세션 업로드 요청 및 응답 계약을 확정한 뒤 이 어댑터를 구현해야 합니다.

## 저장 파일 구조

파일은 Android 앱 전용 Documents 디렉터리에 저장됩니다.

```text
Android/data/com.injidong.measurement/files/Documents/sessions/<session_id>/
├── session.json                  # 세션 상태, 저장 개수, 파일 목록, 정렬·전송 상태
├── frames.jsonl                  # Depth 프레임과 metadata.json 경로 목록
├── rgb_index.jsonl               # RGB 이미지 경로, 해시, 시간, Depth 참조
├── imu.jsonl                     # 휴대폰 IMU 센서 샘플
├── raw/
│   └── <frame_id>_depth_z16.bin  # 카메라 원본 Z16 Depth
├── rgb/
│   └── phone_rgb_<index>.png     # 휴대폰 RGB 원본 이미지
└── <frame_id>/
    ├── metadata.json             # FramePacket v1.0.0 메타데이터
    └── arrays.npz                # depth_m(float32), valid_mask(bool)
```

`session.json`, 프레임 메타데이터, 인덱스와 IMU 샘플은 UTF-8 JSON/JSONL입니다. 이미지와 대량 Depth 배열은 규격의 저장 방식을 따라 PNG·NPZ 및 원본 Z16 파일로 보존합니다. 앱 데이터 폴더는 Android Studio **View → Tool Windows → Device Explorer**에서 확인할 수 있습니다.

주요 정렬 필드는 다음과 같습니다.

- `session.json`: `rgb_depth_alignment.status = "unverified"`, `aligned_to_depth = false`
- `rgb_index.jsonl`: `pair_status = "unverified"`, `aligned_to_depth = false`
- FramePacket: `sync.status = "unverified"`

RGB의 `frame_id`는 가장 최근 저장된 Depth 프레임을 참조하는 인덱스 정보일 뿐, 두 센서가 동기화되었거나 픽셀 정렬되었다는 뜻은 아닙니다.

## 소스 파일 안내

```text
.
├── app/
│   ├── build.gradle                   # Android 앱 의존성과 SDK 설정
│   ├── libs/librealsense.aar           # RealSense Android SDK wrapper
│   └── src/main/
│       ├── AndroidManifest.xml         # 권한, USB Host, 앱 시작 화면
│       ├── java/com/injidong/measurement/
│       │   ├── MainActivity.java       # 화면, CameraX, IMU, 통합 수집 버튼
│       │   ├── DepthCameraAdapter.java # Depth 카메라 공통 데이터 경계
│       │   ├── RealSenseDepthCameraAdapter.java # D405 USB/SDK Depth 수신
│       │   ├── CaptureSessionWriter.java # JSON/JSONL, PNG, NPZ, 세션 저장
│       │   ├── SessionTransport.java   # 전체 세션 업로드 인터페이스
│       │   └── UnconfiguredSessionTransport.java # 미설정 서버 응답
│       └── res/values/styles.xml       # 앱 테마
├── gradle/wrapper/                    # 프로젝트 고정 Gradle Wrapper
├── .gitignore                         # Gradle/Android 생성 파일 제외
├── build.gradle                       # Android Gradle Plugin 버전
└── settings.gradle                    # 저장소 이름과 app 모듈
```

## 현재 검증 범위와 제한

- APK 빌드는 성공했지만, D405가 연결된 실제 휴대폰에서 USB 연결·카메라 권한·RGB/Depth 동시 수집·IMU 저장을 확인해야 합니다.
- 휴대폰 카메라와 D405 Depth의 시각 동기화, 외참수, 픽셀 정렬은 검증되지 않았습니다.
- 서버 업로드 API와 중량 예측 모델이 연결되지 않았습니다. 중량은 실제 예측 결과가 있을 때만 표시해야 합니다.
- `local.properties`, `.gradle/`, `app/build/`, `.idea/`는 기기별 설정 또는 생성 파일이므로 Git에서 제외됩니다. `gradlew`와 Gradle Wrapper 파일은 빌드 재현을 위해 저장소에 포함합니다.
