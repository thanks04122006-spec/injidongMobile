# 인지동 측정 Android 앱

USB로 연결한 Intel RealSense D405의 RGB·Depth 스트림과 휴대폰 IMU를 수집하는 네이티브 Android 앱입니다. 화면의 **데이터 수집** 버튼 하나로 수집과 로컬 저장을 시작·중지합니다.

D405 RGB와 Depth는 같은 SDK 프레임 세트에서 받습니다. 별도 미리보기로 표시하고 저장하지만 픽셀 정렬과 시각 대응은 아직 검증되지 않아 `unverified`로 기록합니다. 서버 API가 아직 정해지지 않아 실제 네트워크 업로드와 중량 예측은 연결되지 않았습니다. 카메라가 RGB 프로파일을 열지 못하면 기존 Depth 스트림으로 전환해 Depth 수집을 계속합니다.

## 기능

- D405 RGB와 Depth 미리보기 및 별도 파일 저장
- RGB 수신 진단 영역에서 USB·SDK 장치 검색·프로파일·프레임 변환 상태 표시
- D405 USB 연결, RGB(RGB8)·Depth(Z16) 수신 및 각각 미리보기 표시
- 휴대폰 가속도계, 선형 가속도계, 자이로스코프, 회전 벡터 기록
- IMU·RGB·Depth 수집을 한 버튼으로 함께 시작·중지
- 세션별 JSON/JSONL 메타데이터와 원본 이미지·Depth 배열 저장
- 저장이 끝난 세션 전체를 ZIP으로 내보내고 사용자가 선택한 위치에 저장
- 프레임별 SDK 보정값과 원본 Z16 stride·버퍼 정보 기록
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
| 앱 버전 / 빌드 번호 | 0.5.1 / 5 |

RealSense Android wrapper AAR는 `app/libs/librealsense.aar`에 포함되어 있습니다. 앱을 빌드하는 데 NDK와 CMake를 별도로 설치할 필요는 없습니다. AAR를 소스에서 다시 빌드할 때만 NDK `28.0.13004108`과 CMake `3.22.1`이 필요합니다.

## 실행 방법

### Android Studio

1. Android Studio에서 `settings.gradle`이 있는 이 저장소 루트 폴더를 엽니다.
2. Gradle 동기화가 끝날 때까지 기다립니다. Android SDK Platform 35가 없다면 SDK Manager에서 설치합니다.
3. 툴바에서 `app` 실행 구성을 선택하고 Android 기기 또는 에뮬레이터를 선택합니다.
4. **Run**을 누릅니다.
5. **데이터 수집**을 누른 뒤 앱이 요청하는 Android 카메라 접근 권한을 허용합니다. 이후 RealSense Android SDK가 USB 장치를 검색하고 연결합니다.
6. D405를 USB OTG로 연결하고 **데이터 수집**을 누릅니다. 수집을 마칠 때 같은 버튼을 다시 누릅니다.
7. 파일 저장 마무리가 끝나 **수집 데이터 ZIP 내보내기** 버튼이 활성화되면 누릅니다. Android 저장 화면에서 파일명과 저장 위치를 선택합니다.

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

에뮬레이터는 앱 UI를 확인하는 용도입니다. 에뮬레이터가 D405 USB 장치를 Android USB Host로 전달한다고 보장되지는 않습니다. D405 RGB/Depth 실제 스트림은 USB OTG 및 전원이 충분한 실제 Android 기기에서 확인해야 합니다.

## 화면과 수집 동작

화면에는 D405 RGB와 Depth 컬러맵이 별도 영역으로 표시됩니다. **데이터 수집**을 누르면 IMU 기록과 D405 스트림 수집을 시작합니다. D405가 두 스트림 프레임을 받으면 Depth는 초당 최대 1프레임, RGB 이미지는 초당 1장씩 세션에 저장됩니다. IMU는 Android 센서 이벤트 주기로 기록됩니다. RGB 프로파일을 사용할 수 없는 경우 Depth는 계속 표시·저장되고 RGB 누락 상태를 보여줍니다.

수집 종료 시 앱은 세션 파일을 닫고 `SessionTransport.sendSession()`을 호출합니다. 현재는 전송 미구성 응답을 표시합니다. 네트워크 업로드를 활성화하려면 서버 URL, 인증 방식, 세션 업로드 요청 및 응답 계약을 확정한 뒤 이 어댑터를 구현해야 합니다.

### ZIP 내보내기

수집을 중지하면 저장 대기 중인 Depth 프레임 처리와 `session.json` 갱신, 파일 닫기를 마친 뒤 내보내기를 활성화합니다. 서버 전송이 미구성이어도 로컬 ZIP 내보내기는 사용할 수 있습니다.

- 기본 ZIP 파일명은 `<session_id>.zip`이며, ZIP 안의 최상위 폴더는 `<session_id>/`입니다.
- 아래 저장 구조의 모든 파일과 하위 폴더를 그대로 포함합니다. JSON뿐 아니라 원본 Z16, PNG, NPZ도 포함됩니다.
- 압축은 백그라운드에서 스트림 방식으로 처리합니다. 수집 중이거나 다른 내보내기가 진행 중일 때는 내보내기 버튼이 비활성화됩니다.
- 현재 화면에서 저장 종료가 완료된 가장 최근 세션을 내보냅니다. 앱 재시작 후 기존 세션을 검색하거나 선택하는 기능은 없습니다.
- 저장에 실패하면 오류를 표시합니다. 대상 위치에 불완전한 ZIP이 남았다면 삭제하고 다시 시도합니다.

## 저장 파일 구조

파일은 Android 앱 전용 Documents 디렉터리에 저장됩니다.

```text
Android/data/com.injidong.measurement/files/Documents/sessions/<session_id>/
├── session.json                  # 세션 상태, 저장 개수, 파일 목록, 정렬·전송 상태
├── frames.jsonl                  # Depth 프레임과 metadata.json 경로 목록
├── images.jsonl                  # 규격 RGB 이미지 경로, 해시, 시간, Depth 참조
├── imu.jsonl                     # 휴대폰 IMU 센서 샘플
├── raw/
│   └── <frame_id>_depth_z16.bin  # 카메라 원본 Z16 Depth
├── rgb/
│   └── d405_rgb_<index>.png      # D405 RGB 원본 이미지
└── <frame_id>/
    ├── metadata.json             # FramePacket v1.0.0 메타데이터
    ├── raw_capture.json          # 앱 전용 raw 진단 근거(공통 스키마 밖)
    └── arrays.npz                # depth_m(float32), valid_mask(bool)
```

`session.json`, 프레임 메타데이터, 인덱스와 IMU 샘플은 UTF-8 JSON/JSONL입니다. 이미지와 대량 Depth 배열은 규격의 저장 방식을 따라 PNG·NPZ 및 원본 Z16 파일로 보존합니다. 앱 데이터 폴더는 Android Studio **View → Tool Windows → Device Explorer**에서 확인할 수 있습니다.

새로 수집하는 세션의 RGB 인덱스 파일명은 공통 규격에 맞춘 `images.jsonl`입니다. 이전 버전에서 만든 세션의 `rgb_index.jsonl`은 변경하거나 이름을 바꾸지 않습니다.

주요 정렬 필드는 다음과 같습니다.

- `session.json`: `rgb_depth_alignment.status = "unverified"`, `aligned_to_depth = false`
- `images.jsonl`: `pair_status = "unverified"`, `aligned_to_depth = false`
- FramePacket: `sync.status = "unverified"`

RGB의 `frame_id`는 가장 최근 저장된 Depth 프레임을 참조하는 인덱스 정보일 뿐, 두 센서가 동기화되었거나 픽셀 정렬되었다는 뜻은 아닙니다.

### Depth 보정값과 원본 stride

각 프레임의 공통 `metadata.json`에는 기존 v1.0.0 필드만 저장합니다. 추가 raw 진단 정보는 같은 프레임 폴더의 `raw_capture.json`에 저장합니다. 공통 스키마와 `schema_version`은 변경하지 않습니다.

| 필드 | 내용 |
|---|---|
| `intrinsics` | 해상도와 `fx`, `fy`, `cx`, `cy`, 보정 식별자 |
| `intrinsics.calibration_id` | 원본 왜곡 모델과 계수를 포함한 식별 문자열 |
| `provenance.raw_scale_to_m` | 원본 Z16 값을 미터로 변환하는 스케일 |
| `raw_capture.json: raw_stride_bytes` | SDK에서 읽은 원본 Z16 한 행의 바이트 수. 패딩 포함 |
| `raw_capture.json: raw_width`, `raw_height` | 원본 Depth 해상도 |
| `raw_capture.json: raw_bytes_per_pixel` | Z16 픽셀당 바이트 수: `2` |
| `raw_capture.json: raw_byte_order` | `little_endian` |
| `raw_capture.json: raw_data_size_bytes` | 저장한 원본 Z16 버퍼의 전체 바이트 수 |


`raw_capture.json`의 경로는 세션 루트 기준이며 session_id·frame_id·metadata/raw 파일 경로와 SHA-256으로 프레임에 연결됩니다. `raw_stride_bytes`는 SDK getStride()에서 받은 값입니다. `raw_byte_order=little_endian`은 현재 디코더의 고정 해석이며 SDK가 별도 조회해 준 값은 아닙니다. `evidence_format=mobile-raw-capture-evidence-v1`은 이 수정안의 앱 전용 제안 형식이고 공통규격 버전이 아닙니다. 기존 파일에는 생성하거나 소급 적용하지 않습니다. 현재 모바일 검사기는 이 sidecar를 부가 파일로 분류하고 내용까지 검증하지 않으므로, 파일 인수 검사 통과를 raw 진단 근거의 검증 완료로 확대하지 않습니다.

원본 `raw/` 파일의 픽셀 `(x, y)`는 `y * raw_stride_bytes + x * 2` 바이트 위치에서 읽습니다. 패딩이 있을 수 있으므로 행 간격을 항상 `width * 2`로 가정하면 안 됩니다. 이 stride는 원본 Z16에 대한 값이며 NPZ 배열의 행 간격을 의미하지 않습니다. stride 필드는 변경된 앱으로 새로 수집한 데이터부터 저장되며 기존 파일에는 소급 추가되지 않습니다.

`arrays.npz`의 `depth_m`은 광축 방향 거리(미터)의 float32 배열이며 `valid_mask`는 유효 픽셀을 나타냅니다. 원본 왜곡 모델이 `BROWN_CONRADY`이면 최근접 이웃 방식으로 핀홀 좌표에 재매핑하고, 원본 Z16은 `raw/`에 그대로 보존합니다. RGB 원본은 `rgb/`에 PNG로 저장하고 인덱스·해시는 `images.jsonl`에 기록합니다. 현재 RGB/Depth 픽셀 정렬과 시각 대응은 검증되지 않았습니다.

촬영 대상과 별도 거리 입력은 현재 구현되어 있지 않아 `specimen_id`, `capture_conditions.material`, `capture_conditions.camera_distance_m`은 `null`입니다. 대상 영역의 유효 `depth_m` 중앙값으로 대략적인 광축 방향 거리를 추정할 수 있지만, 배경이 섞이지 않도록 대상 영역을 지정해야 합니다.

## 소스 파일 안내

```text
.
├── app/
│   ├── build.gradle                   # Android 앱 의존성과 SDK 설정
│   ├── libs/librealsense.aar           # RealSense Android SDK wrapper
│   └── src/main/
│       ├── AndroidManifest.xml         # 권한, USB Host, 앱 시작 화면
│       ├── java/com/injidong/measurement/
│       │   ├── MainActivity.java       # 화면, RealSense RGB/Depth, IMU, 통합 수집 버튼
│       │   ├── DepthCameraAdapter.java # RGB·Depth 카메라 공통 데이터 경계
│       │   ├── RealSenseDepthCameraAdapter.java # D405 USB/SDK RGB·Depth 수신
│       │   ├── CaptureSessionWriter.java # JSON/JSONL, PNG, NPZ, 세션 저장
│       │   ├── SessionZipExporter.java # 세션 폴더 전체 ZIP 스트림 내보내기
│       │   ├── SessionTransport.java   # 전체 세션 업로드 인터페이스
│       │   └── UnconfiguredSessionTransport.java # 미설정 서버 응답
│       └── res/values/styles.xml       # 앱 테마
├── gradle/wrapper/                    # 프로젝트 고정 Gradle Wrapper
├── .gitignore                         # Gradle/Android 생성 파일 제외
├── build.gradle                       # Android Gradle Plugin 버전
└── settings.gradle                    # 저장소 이름과 app 모듈
```

## 현재 검증 범위와 제한

- 사용자 확인에 따르면 휴대폰 디버그 실행으로 촬영 데이터를 수집하고 파일을 추출했습니다. ZIP 내보내기와 stride 저장 변경 이후의 실기기 검증 결과는 아직 확인되지 않았습니다.
- D405 RGB 스트림 추가 후 `gradlew.bat assembleDebug` 빌드가 성공했습니다. 사용자 실기기 화면에서 D405 RGB 640×480 RGB8 프레임 수신·미리보기와 저장 동작을 확인했습니다. 화면 진단값은 stride 1920 bytes입니다.
- D405 RGB와 Depth의 시각 대응, 외참수, 픽셀 정렬은 검증되지 않았습니다.
- 서버 업로드 API와 중량 예측 모델이 연결되지 않았습니다. 중량은 실제 예측 결과가 있을 때만 표시해야 합니다.
- `local.properties`, `.gradle/`, `app/build/`, `.idea/`는 기기별 설정 또는 생성 파일이므로 Git에서 제외됩니다. `gradlew`와 Gradle Wrapper 파일은 빌드 재현을 위해 저장소에 포함합니다.
