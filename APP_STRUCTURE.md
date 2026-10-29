# 모바일 앱 구조와 실행 흐름

현재 앱은 Java 기반 네이티브 Android 앱이며 `MainActivity` 하나에서 수집·저장·수동 업로드·세션별 3D 결과 표시를 관리한다. UI는 레이아웃 XML 대신 Java로 생성한다. 이 문서는 현재 소스 기준이며 실제 D405·서버 생성 파이프라인 전체의 검증 완료를 뜻하지 않는다.

## 실행 흐름

```text
AndroidManifest → MainActivity.onCreate → 화면 생성 / USB 확인 / 실행기 생성
  데이터 수집 버튼 → startRealSense
    RealSenseDepthCameraAdapter → CameraFrame → RGB·Depth 미리보기
    250ms 이상 간격으로 CaptureSessionWriter에 프레임 저장
    SensorManager → onSensorChanged → IMU JSONL 기록
  수집 중지 → SDK 연결 해제 / IMU 종료 / 저장 작업 마무리
    session.json 완료 → 내보내기·수동 업로드 버튼 활성화
    Depth 영역 → 업로드 대기 안내 (자동 업로드하지 않음)
  ZIP 내보내기 → RGB 검사 → 파일 저장 위치 선택 → SessionZipExporter
  API 업로드 버튼 → RGB 검사 → HttpSessionTransport → SessionArchiveUpload
    ZIP 생성·고정 → multipart POST → 서버 응답 ID·크기·SHA-256 검사
    성공 → 같은 세션의 3D 결과 GET → 준비 완료 시 Depth 영역을 3D로 전환
    다시 수집 → 이전 3D 조회 취소 / Depth 미리보기로 복귀
```

## Java 파일별 역할

모든 Java 파일은 `app/src/main/java/com/injidong/measurement/`에 있다.

| 파일 | 실행되는 기능과 호출 관계 |
|---|---|
| MainActivity.java | 앱 진입점. 화면·버튼·USB/카메라 권한·센서 이벤트·수집 상태·비동기 저장·업로드·3D 조회를 연결한다. onCreate/onResume/onPause/onDestroy와 센서/권한 콜백은 Android가 호출한다. |
| DepthCameraAdapter.java | 카메라 경계 인터페이스. connect/disconnect/supports와 Listener 콜백을 정의한다. CameraFrame은 원본 Z16, 미터 Depth, 유효 마스크, 미리보기, 시각·보정 정보를 운반한다. |
| RealSenseDepthCameraAdapter.java | Intel SDK의 USB 장치/스트림을 열어 RGB8·Z16을 읽고 CameraFrame을 전달한다. 왜곡 정보로 Depth를 핀홀 좌표에 재매핑하고 미리보기를 만든다. SDK 이름·메서드는 리플렉션으로 접근한다. RGB 프로파일 실패 시 Depth 단독으로 전환한다. |
| CaptureSessionWriter.java | 프레임별 metadata.json, arrays.npz, raw Z16, RGB PNG와 frames.jsonl/images.jsonl, 세션 요약을 저장한다. raw_capture.json과 rgb_depth_evidence.json도 기록한다. RGB PNG 저장 뒤 RGB8 헤더를 검사한다. IMU 파일 자체는 MainActivity가 작성한다. |
| RgbPngValidator.java | PNG 시그니처·IHDR·해상도·8비트 RGB 형식 검사. 저장 시 CaptureSessionWriter, 내보내기/업로드 검사 시 RgbExportValidator가 호출한다. |
| RgbExportValidator.java | images.jsonl의 파일 경로·RGB8 선언·해상도·SHA-256 및 session.json의 이미지 개수를 검사한다. 내보내기와 수동 업로드 전에 실행한다. |
| SessionZipExporter.java | 세션 폴더 전체를 스트리밍 ZIP으로 작성한다. 사용자가 지정한 위치로 내보내기와 업로드 ZIP 생성이 공유한다. |
| SessionTransport.java | sendSession과 성공/실패 콜백 계약. MainActivity와 HTTP 전송 구현의 경계를 유지한다. |
| HttpSessionTransport.java | 완료된 세션인지 확인하고 .upload-cache에 재시도용 ZIP을 고정한다. SessionArchiveUpload 호출 뒤 HTTP 상태 및 서버 저장 영수증을 검증한다. |
| SessionArchiveUpload.java | multipart POST 전송, Bearer 인증, 파일 크기·해시, 타임아웃, 리다이렉트 차단, 응답 크기·JSON 형식 검사를 담당한다. UI와 무관한 차단식 전송이므로 작업 스레드에서 호출한다. |
| PointCloudApi.java | 세션 ID로 GET 요청하고 IPC1 바이너리를 float XYZ/RGB 배열로 해석한다. X-Session-Id가 요청과 다르면 거절한다. HTTP 202는 결과 대기(null)로 반환한다. 파일을 생성하거나 점군을 계산하지 않는다. |
| PointCloudView.java | GLSurfaceView/OpenGL ES 2.0으로 받은 점 배열을 렌더링한다. 회전·핀치 확대·두 손가락 이동·시점 초기화 지원. 표시 좌표만 중심·크기를 정규화하며 원본 배열은 복사해 보존한다. |

## 설정·리소스 파일

| 파일 | 역할 |
|---|---|
| app/src/main/AndroidManifest.xml | MainActivity를 launcher로 등록. INTERNET/CAMERA 권한, 선택적 USB Host·카메라 기능, 네트워크 보안 설정과 테마 지정. |
| app/src/main/res/xml/network_security_config.xml | 일반 HTTP 차단. localhost/127.0.0.1만 HTTP 예외 허용. 세션 결과 클라이언트는 HTTP loopback을 debug 빌드에서만 허용한다. |
| app/src/main/res/values/styles.xml | 기본 색상·폰트·시스템 바·NoActionBar 테마. |
| app/src/main/res/values-v27/styles.xml | API 27 이상 시스템 내비게이션 바 아이콘 스타일. Android 리소스 선택으로 사용되므로 직접 Java 참조가 없어도 필요하다. |
| app/build.gradle | 앱 ID·SDK·버전·의존성과 BuildConfig.API_ORIGIN/API_TOKEN 생성. librealsense.aar가 있으면 포함한다. |
| build.gradle | Android Gradle Plugin 8.7.3 선언. |
| settings.gradle | 저장소와 :app 모듈 등록. |
| gradle.properties | AndroidX 활성화. |
| local.properties | SDK 경로·서버 origin·팀 토큰의 PC별 설정. Git 제외. 실제 토큰은 문서·로그에 기록하지 않는다. |
| gradle/wrapper/*, gradlew, gradlew.bat | 프로젝트 Gradle 실행 버전을 고정하고 빌드를 시작한다. |
| app/libs/librealsense.aar | 선택적 Intel RealSense SDK Android wrapper 및 포함된 네이티브 라이브러리. 앱 소스와 별개인 외부 의존성이므로 정리 대상에서 제외한다. |

## 주요 변수와 상수

### MainActivity

| 이름 | 값/의미 |
|---|---|
| CAMERA_PERMISSION_REQUEST | 31. 카메라 권한 요청/응답 구분. |
| FRAME_SAVE_INTERVAL_MS | 250ms. SDK 수신 속도와 별도로 저장 간격을 제한하며 저장 목표는 최대 약 4 FPS. |
| BG / PANEL / MUTED | 배경·카드·보조 글자 색상. |
| streaming / cameraConnected / pendingRealSenseStart | 수집 중 여부 / SDK 연결 완료 여부 / 권한 승인 후 시작 대기 여부. |
| sessionId / activeSessionDirectory | 현재 수집 세션 ID와 저장 폴더. ID는 d405_와 현재 시각의 밀리초 값으로 생성한다. |
| captureWriter / saveExecutor | 세션 작성기와 단일 저장 작업 실행기. 프레임 저장 완료 후 종료·업로드 작업 순서를 유지한다. |
| lastPersistedFrameMs / persistedFrameCount / persistedRgbCount | 저장 간격 판단 및 화면 저장 개수 표시. |
| imuRecording / imuWriter / imuSampleIndex / latestImuValues | IMU 수집 상태·파일 작성기·샘플 번호·화면용 최근 센서 값. 가속도/선형 가속도/자이로/회전 벡터를 SENSOR_DELAY_GAME으로 요청한다. |
| uploadableSession / uploading | 정상 완료·저장된 업로드 대상과 업로드 진행 여부. |
| exportableSession / pendingExportSession / exporting | ZIP 내보내기 대상·저장 위치 응답 대기 대상·진행 여부. |
| sessionCloudView / depthPreview / depthPlaceholder | 같은 화면 영역의 3D 뷰어·Depth 미리보기·대기/오류 안내. |
| cloudSessionId | 업로드 성공 후 조회할 세션 ID. 임의의 전역 테스터 결과를 사용하지 않는다. |
| cloudGeneration | 재수집·화면 이탈 등으로 오래된 네트워크 응답이 새 화면을 덮지 못하게 하는 세대 번호. |
| cloudAttempts | HTTP 202 대기 응답 횟수. 최대 60회, 다음 요청은 3초 후 예약. 실제 총 시간에는 요청 소요 시간도 포함된다. |
| cloudReady / cloudPaused | 결과 표시 완료 / Activity 일시 중지 여부. |
| cloudExecutor / cloudHandler | 네트워크 작업 실행기 / UI 스레드의 다음 결과 조회 예약. |

### 네트워크·렌더링

| 이름/설정 | 값/의미 |
|---|---|
| BuildConfig.API_ORIGIN | 서버 origin. local.properties의 injidong.api.origin 또는 debug의 injidongDebugOrigin 빌드 속성. 주소 변경은 재빌드 필요. |
| BuildConfig.API_TOKEN | injidong.api.token을 빌드에 반영. Authorization: Bearer로 전송. 소스 관리 제외 설정에서 읽지만 APK에는 포함되므로 APK도 팀 내부에서만 공유한다. |
| injidongViewerDebug | 지정하면 debug 앱 ID에 .viewerdebug를 붙여 기존 설치와 분리한다. |
| SessionArchiveUpload.MAX_ZIP_BYTES | 128 MiB. 업로드 ZIP 최대 크기. |
| SessionArchiveUpload.deadlineMs | 기본 120초 전체 제한, 연결 타임아웃 최대 3초, 읽기 타임아웃 120초. 응답 본문 최대 1 MiB. |
| PointCloudApi 조회 제한 | 연결 10초 / 읽기 30초 / 전체 타이머 60초 / 최대 250,000점. |
| IPC1 | 4바이트 magic + little-endian uint32 개수 + 점마다 float32 XYZ(미터), RGB(0~1). |
| Cloud.xyz / Cloud.rgb | 각각 N×3 길이의 평탄한 float 배열. 세션 결과 수신 후 PointCloudView에 전달. |
| PointCloudView.yaw / pitch | 회전 각도. pitch는 -89~89도로 제한. |
| distance / panX / panY | 카메라 거리와 화면 이동. 초기 거리 3.5, 확대·축소 거리 1.3~15. |
| RealSenseDepthCameraAdapter.SDK / running | SDK 클래스 이름 접두사 / 수신 루프의 AtomicBoolean 실행 상태. 문자열 메서드 호출은 실제 SDK 참조다. |

## 로컬 저장과 서버 경계

로컬 저장 위치는 getExternalFilesDir(DIRECTORY_DOCUMENTS)의 sessions/<session_id>/다.
session.json, frames.jsonl, images.jsonl, imu.jsonl, raw/, rgb/, 프레임별 metadata.json·arrays.npz·진단 sidecar를 포함한다.
업로드 재시도 ZIP은 세션 루트와 같은 부모의 .upload-cache에 둔다.

서버 업로드는 POST /api/v1/sessions이며 자동 업로드는 없다.
성공 후 GET /api/v1/sessions/{session_id}/pointcloud로 결과를 확인한다.
서버의 점군 생성·RGB 정렬·ROI 처리는 이 앱의 구현 범위가 아니다.
현재 앱에는 점군 생성, RGB 정렬, 부피·중량 추론 구현이 없다. 예측 정보 UI는 실제 결과가 없는 대기 표시다.
CaptureSessionWriter가 기록하는 session.json의 transmission 항목에는 초기 미설정 문구가 남아 있으며, 서버 업로드 완료 영수증 역할을 하지 않는다. 실제 업로드 성공 판단은 HttpSessionTransport가 담당한다.

## 미사용 코드 확인과 정리

- 테스트 전용 PointCloudActivity·PointCloudFile 및 Manifest 등록·전역 검토용 조회 분기는 앞선 정리에서 제거했다.
- 이번 정리에서는 생성·참조되지 않는 UnconfiguredSessionTransport를 삭제했다.
- DepthCameraAdapter.name 및 해당 구현은 호출자가 없어 제거했다.
- CameraFrame의 보정 JSON 없는 생성자 오버로드는 호출자가 없어 제거했다. SDK가 사용하는 보정 JSON 포함 생성자는 유지한다.
- Android 생명주기/센서/권한 콜백, Listener 구현, AutoCloseable.close, OpenGL Renderer 콜백, SDK 리플렉션, 버전별 리소스는 유지한다.
- 검사 범위는 이 앱의 자체 Java 코드·Manifest·리소스·Gradle과 문서 참조다. 외부 SDK 바이너리·역사적 검증 산출물·서버·PC 점군 코드는 변경하지 않는다.
