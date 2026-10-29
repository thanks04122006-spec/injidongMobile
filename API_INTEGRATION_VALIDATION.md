# injidong_api 연결 준비 검증 — 2026-10-23

## 후속 변경: 수동 업로드 버튼

아래 비활성 상태 기록 이후 사용자 요청으로 MainActivity에 **수집 데이터 API 업로드**
버튼을 연결했다. 수집 종료/onDestroy의 자동 sendSession 호출은 제거했다.
정상 저장 완료 세션에 한해 사용자가 버튼을 눌렀을 때만 업로드한다.
RGB 검사→보관 ZIP→HTTP→영수증 검사 순서이며 업로드 중 중복 요청/수집/내보내기를 막는다.
완료/오류 후 상태를 표시하고 재시도할 수 있다. 파괴된 Activity에는 결과 UI를 갱신하지 않는다.
업로드 자체는 기존 worker에서 완료하며 앱을 닫아도 이미 시작한 요청을 취소하는 UI는 없다.
서버 주소는 localhost:8000, Bearer 미설정이며 PC 연결에는 adb reverse를 사용한다.
network_security_config는 localhost/127.0.0.1에만 HTTP 예외를 적용한다.
실기기의 버튼 조작·adb reverse·HTTP 전송은 아직 직접 검증하지 않았다.
기존 Java HTTP 왕복 검증과 Android APK 빌드 검증을 이 범위와 구분한다.

## 결과와 활성화 상태

이 절은 수동 업로드 버튼 추가 이전의 검증 이력이다.

로컬 시험 통과 후 `SessionArchiveUpload.java`, `HttpSessionTransport.java`를 추가했다.
이 항목은 초기 구현 당시 기록이다. 현재 MainActivity는 `HttpSessionTransport`를 사용하며, 미사용 `UnconfiguredSessionTransport`는 삭제했다. 현재 실행 구조는 `APP_STRUCTURE.md`를 참조한다.
자동 업로드, 서버 주소 입력 화면, cleartext 허용, LAN 공개는 활성화하지 않았다.
기준 주소는 `http://localhost:8000`이다. 휴대폰의 localhost는 휴대폰 자신이다.
실기기 연결은 후속 작업이며 debug HTTP 정책과 연결 경로를 따로 검증해야 한다.

## 테스트 대상

- API 원본: `D:/injidong_api`의 app/tests/vendor를 작업 공간 안 시험 폴더로 복사.
- 시험 위치: `D:/injidong/outputs/mobile-api-integration`.
- 서버는 127.0.0.1:8000에만 바인딩하고 Java 클라이언트는 localhost:8000 사용.
- 기존 8000 포트가 사용 중이면 시험 중단. 기존 서버에 접속하거나 종료하지 않는다.
- DB/재전송 사본은 시험 임시 폴더 사용. 원본 API 소스와 data는 변경하지 않았다.
- 실제 촬영본: 0.5.1_array_sample.zip, 30,661,636 bytes.
- SHA-256: e87e810013b49756ab450349baa10ef939f7a1ddd0f68e4308fc5144efcf0735.
- 시험용 임시 Bearer는 프로세스 메모리/환경에만 전달하며 보고서/코드/명령행에 기록하지 않았다.
- 종료 시 시험 서버 종료, 임시 데이터 정리.

## 실행 결과

서버 unittest 10개 통과: 저장/목록/다운로드/재전송/재시작, 충돌,
크기·SHA·ID·한도, 손상 입력, 페이지, 동시 업로드, 인증/OpenAPI,
multipart 한도, NPY 검사, 실제 샘플 왕복.

앱과 동일한 Java 업로드 코어를 JVM에서 컴파일해 실제 Uvicorn/TCP와 시험했다.

| 검사 | 결과 |
|---|---|
| 신규 ZIP multipart 업로드 | 201 created |
| 동일 ZIP 재전송 | 200 existing, 최초 record 유지 |
| 같은 ID에 다른 유효 ZIP | 409 SESSION_CONFLICT, 기존 원본 유지 |
| 목록 | items/next_cursor/record 일치 |
| 다운로드 | ZIP 원본 바이트·길이·SHA·ID 일치 |
| 인증 누락 | 거부, 성공 처리되지 않음 |
| 서버 재시작 | 목록 record·원본 다운로드 동일 |

HTTP 헤더는 대소문자를 구분하지 않으므로 테스트 하네스의 헤더 이름 비교를 소문자로
정규화했다. 서버 계약 오류로 오인해 서버를 바꾸지 않았다.
최종 증거는 `D:/injidong/outputs/mobile-api-integration/MOBILE_WIRE_VERIFIED.json`이다.

별도 Android SDK android-35 기준 Java 컴파일도 통과했다.
소스 반영 후 `gradlew.bat --offline --no-daemon assembleDebug`도 성공했다.
시험한 Java 업로드 코어와 앱에 추가한 파일의 SHA-256이 일치함을 확인했다.
Android 실기기/에뮬레이터의 네트워크 및 JSONObject 영수증 검사 실행은 아직 미검증이다.
JVM HTTP 성공을 Android 종단 성공으로 간주하지 않는다.

## 구현 경계

SessionArchiveUpload는 원본 ZIP을 청크 전송하고 실제 크기/SHA를 multipart에 넣는다.
리다이렉트를 따라가지 않고 응답은 JSON/identity 및 1MiB 이내로 제한한다.
HTTP는 loopback만 허용한다. HTTPS의 기본 인증서 검증은 유지한다.
연결 timeout 3초, 응답 read timeout/요청 disconnect 예약은 기본120초다.
DNS/플랫폼 I/O의 엄격한 전체 시간 보장을 검증한 것은 아니다.

HttpSessionTransport는 complete·ID 일치 manifest만 허용한다.
첫 ZIP은 sessions/.upload-cache/<session_id>.zip에 보관하고 재시도 시 재압축하지 않는다.
원본 session.json의 transmission은 변경하지 않는다.
201/200만 성공 후보로 보고 disposition·ID·stored·크기·SHA·다운로드 경로·시각 형식·warnings를 대조한다.
서버 응답 원문, 예외 원문, 토큰을 UI나 로그에 출력하지 않는다.
영수증을 확인하지 못하면 실패/재시도 가능 상태를 반환하며 서버 저장 여부를 단정하지 않는다.
캐시 ZIP은 명시적으로 정리하기 전 보존한다. 자동 삭제/자동 재시도는 없다.
동기 호출이므로 기존 저장 worker 같은 백그라운드 실행기에서만 호출해야 한다.

## 후속 활성화 전 확인

실제 Android에서 보관 ZIP 재사용·잘못된 영수증 거부·cleartext 정책·중단/재시도·UI 상태를
검사해야 한다. PC 제품 GUI와 실제 기기까지의 연결은 별도로 확인한다.
현재 변경은 세션 ZIP 전송 어댑터이며 RGB 정렬/물리 정확도 승인이나 추론 API가 아니다.

## 재현

```powershell
cd D:/injidong/outputs/mobile-api-integration
& D:/injidong/toolcache/jdk21/jdk-21.0.12.1+1/bin/javac.exe -d probe-classes SessionArchiveUpload.java UploadProbe.java
& D:/injidong_api/.venv/Scripts/python.exe test_mobile_wire.py
```

하네스와 sample 경로는 이 PC 전용이다. 토큰을 설정 파일에 복사하지 않는다.
