# 회의 녹음 (Android)

앱 자체 녹음 → 목록·재생·공유. (다음 단계: 온디바이스 Whisper 텍스트 변환 → 요약)

## 휴대폰에 설치하는 방법 (PC에 프로그램 설치 불필요)

1. github.com 에 로그인 → 오른쪽 위 **+ → New repository** → 이름 `MeetingRecorder` → **Create**
2. 생성된 페이지에서 **uploading an existing file** 클릭
3. 이 압축을 푼 **MeetingRecorder 폴더 안의 모든 파일·폴더**를 끌어다 놓기 → **Commit changes**
   - `.github` 폴더가 숨김이라 안 보이면 탐색기 **보기 → 숨긴 항목** 체크 후 같이 올리기
4. 저장소의 **Actions** 탭에서 "APK 빌드"가 초록색 ✓ 될 때까지 대기 (약 5~8분)
5. 휴대폰 브라우저에서 아래 주소 열기 → 다운로드된 APK 실행 → 설치
   `https://github.com/<내아이디>/MeetingRecorder/releases/latest/download/meeting-recorder.apk`
   - "출처를 알 수 없는 앱" 경고가 나오면 해당 브라우저만 허용 (휴대폰 다른 설정은 변경 없음)

코드를 고쳐서 다시 올리면 자동으로 새 APK가 만들어지고, 같은 주소로 받아 덮어 설치하면 기존 녹음은 유지됩니다.

## Android Studio로 직접 빌드
폴더를 열고 ▶ Run (휴대폰 USB 디버깅 연결).

## 독립 실행 원칙
- 기본 녹음기·전화 등 다른 앱을 대체·수정하지 않음
- 녹음은 앱 전용 저장소에만 저장 (저장소 권한 없음, 앱 삭제 시 함께 삭제)
- 권한: 마이크, 알림, 포그라운드 서비스(마이크)만 사용
- 정지·오류·종료 시 마이크 즉시 반납
