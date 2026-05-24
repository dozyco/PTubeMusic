<h1 align="center">PTubeMusic</h1>

<p align="center">
Polestar 4 (AAOS) YouTube Music client<br>
Fork of <a href="https://github.com/mostafaalagamy/Metrolist">Metrolist</a>
</p>

---

## 소개

PTubeMusic 은 **Polestar 4 (Android Automotive OS)** 차량에서 사용 가능한 YouTube Music 클라이언트입니다.

오픈소스 [Metrolist](https://github.com/mostafaalagamy/Metrolist) 프로젝트를 기반으로 차량 환경에 맞춰 수정/추가 작업했습니다.

모든 작업은 Claude AI를 이용하여 수정했습니다.
## 주요 기능

- **차량 친화 UI** — AAOS 미디어 브라우저 인터페이스 지원
- **좋아요 곡** — YouTube Music 의 좋아요 한 곡 (LM 플레이리스트) 표시
- **검색** — 카테고리별 결과 표시 (Top result / Songs / Albums / Artists / Videos)
- **추천 믹스** — 공식 YouTube Music 과 유사한 믹스/플레이리스트 표시

## 로그인 방법
**1. 시크릿 모드 열기**

- Chrome / Edge: `Ctrl + Shift + N`
- Firefox: `Ctrl + Shift + P`

**2. YouTube Music 접속 + 로그인**

- 주소창에 `https://music.youtube.com` 입력
- 본인 Google 계정으로 로그인
- 로그인 완료 후 본인 라이브러리 (좋아요 곡 등) 정상 표시되는지 확인

**3. 개발자 도구 열기**

- `F12` 누르기
- 또는 `Ctrl + Shift + I`

**4. Network 탭으로 이동**

- 개발자 도구 상단의 **Network** 탭 클릭

**5. 페이지 새로고침**

- `F5` 또는 `Ctrl + R`
- Network 탭에 요청 목록이 쭉 나타남

**6. 요청 찾기**

- Network 탭 좌측 필터창에 `browse` 입력
- 결과 중 아무거나 (보통 `browse?...` 같은 이름) 클릭

**7. 쿠키 복사**

- 우측 패널의 **Headers** 탭 선택
- 아래로 스크롤 → **Request Headers** 섹션 찾기
- **`cookie:`** 항목 찾기
- `cookie:` 다음에 오는 긴 문자열 **전체** 복사 (마우스로 드래그 또는 우클릭 → Copy value)

> 예시: `VISITOR_INFO1_LIVE=xxx; YSC=xxx; LOGIN_INFO=xxx; ...` (매우 김)

**8. PTubeMusic 에 쿠키 입력**

- 폴4 차량 또는 에뮬레이터에서 PTubeMusic 실행
- **설정 → 계정 → 쿠키 입력** 메뉴 진입
- 복사한 쿠키 전체를 붙여넣기
- 저장

**9. 로그인 확인**

- 라이브러리 탭 진입
- 본인 좋아요 곡, 구독, 플레이리스트 등이 정상 표시되면 성공

### 주의사항

- **쿠키는 비밀번호와 동급의 정보입니다.** 절대 다른 사람과 공유하지 마세요.
- 쿠키는 일정 시간 지나면 만료됩니다. 갑자기 로그인 풀리면 위 과정 반복하세요.

### 쿠키 만료 시

PTubeMusic 은 쿠키 만료를 자동 감지합니다. 만료 시 알림이 뜨면 위 단계를 다시 수행해주세요.

## 이용시 주의사항

- **비공식 앱입니다.** YouTube/Google 과 무관합니다.
- 사용은 **본인 책임** 하에 하세요.
- 광고 차단/우회 목적이 아니며, **YouTube Premium**를 가입하지 않으면 앱이 작동하지 않습니다.
- 언제든지 앱 작동이 되지 않을수 있습니다.

## 라이선스

이 프로젝트는 **GPL-3.0 License** 하에 배포됩니다.

원본 Metrolist 의 라이선스를 따르며, 본인 수정 사항도 동일한 라이선스로 공개됩니다.

자세한 내용은 [LICENSE](./LICENSE) 파일을 참고하세요.

## 출처

- 원본 프로젝트: [Metrolist](https://github.com/mostafaalagamy/Metrolist)
- 본 프로젝트: [PTubeMusic](https://github.com/dozyco/PTubeMusic)

<br>
<br>
<br>

네이버 폴스타 동호회 [폴스타 클루부](https://cafe.naver.com/hamseang) by 염발