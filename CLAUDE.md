# AiWACS AI 운영 도우미 — 프로젝트 규칙

> 이 문서는 Claude와 바이브 코딩할 때 따르는 규칙과 프로젝트 명세다.
> 작업 시작 전 이 문서를 참고한다.

---

## 1. 프로젝트 개요

- **무엇**: SYSONE의 통합 모니터링 솔루션 `AiWACS`에 얹는 신규 기능 제안 프로젝트
- **목적**: 채용연계 프로젝트. 평가 기준은 **제품 이해도 · 아이디어 · 기획** (개발 난이도가 아님. 단, 작동하는 결과물은 필요)
- **컨셉**: "AI 운영 도우미 = 신입 담당자 옆에 앉은 선임"
  - 상태를 진단해주고(진단), 필요하면 기준을 바꿔준다(임계치 변경)
- **핵심 명분**: AiWACS는 데이터 수집·표시·판정까지는 훌륭하나, 그 **'해석'은 사람 몫**이다. 그 해석의 공백을 AI로 채운다.
- **심사 대상**: 이 제품(AiWACS)을 만든 회사. → 제품을 **존중하는 겸손한 제안 톤** 유지. "없다/부족하다" 같은 단정 표현 지양.

---

## 2. 기술 스택 (회사 스택에 맞춤)

- **백엔드**: Java 25 + Spring Boot 4.1.1 (Maven Wrapper `./mvnw`, Jackson 3 = `tools.jackson` 패키지)
- **프론트엔드**: HTML + CSS + JavaScript (Vanilla)
- **시스템 지표 수집**: OSHI 7.6.1
- **AI**: Google Gemini REST API (모델: `gemini-3.6-flash`, Spring RestClient로 호출)
- **DB**: PostgreSQL 17 (회사 스택, Docker로 실행, 정책은 `alert_policy` 테이블)
- **API 키**: `aiwacs/.env` (gitignore) → `spring.config.import`로 읽음
- **개발 도구**: VSCode (자바 확장) + 저(Claude)와 바이브 코딩

### 프로젝트 구조 (`aiwacs/`)
```
src/main/java/com/sysone/aiwacs/
├── monitor/  MetricsService(OSHI 수집), MonitorController(/api/status·procs·disk·traffic)
├── policy/   Policy·Threshold(엔티티), PolicyService(CRUD·판정·임계치 변경), PolicyController(/api/policies)
├── ai/       GeminiClient(호출·JSON 추출·재시도), AiService(프롬프트), AiController(/api/ai/*)
└── config/   WebConfig (/policy, /ai 화면 주소 연결)
src/main/resources/static/  index.html, policy.html, ai.html
docker-compose.yml           PostgreSQL (볼륨 이름 `aiwacs-pgdata`로 고정)
```
- API 응답 JSON 모양은 프론트(static HTML)가 기대하는 형식을 유지한다. 바꾸면 화면도 함께 수정해야 함.

### 실행 방법
```bash
cd aiwacs
docker compose up -d      # DB 켜기 (Docker Desktop 필요)
./mvnw spring-boot:run    # http://localhost:8080
```

---

## 3. 기능 명세

### 화면 3개
1. **메인 대시보드** — VM 실시간 모니터링 (AiWACS 스타일 재현)
   - CPU/메모리/디스크 현재값 + 임계치 판정(정상/주의/위험)
   - Resource Map (CPU/MEMORY/DISK/TRAFFIC 탭 전환 그래프)
   - 프로세스 TOP5, 디스크 파티션별, 트래픽 송수신
2. **알림정책** — 임계치 설정 (여러 정책 + 기업명 + CRUD)
   - 고객사 + 정책명 + CPU/메모리/디스크 주의·위험 임계치
3. **AI 운영 도우미** — 탭 2개
   - **AI 상태 진단**: 현재 상태+세부지표+프로세스를 AI가 해석
   - **AI 임계치 설정**: 자연어로 정책 임계치 변경 (여러 개 동시 가능)

### 완성 상태
- [x] 메인 대시보드 (실시간)
- [x] 알림정책 (여러 개 + 기업명 + CRUD)
- [x] 임계치 → 판정 연결
- [x] AI 임계치 변경 (자연어, 여러 개 동시)
- [x] AI 상태 진단 (세부지표 활용)
- [ ] AI 조치 실행 (프로세스 끄기/재시작 등) — 예정
- [ ] VM Agent (여러 서버 모니터링) — 예정
- [ ] 정책-서버 매칭 — 예정

### 세부 지표 (진단 정확도용, OSHI로 수집)
- CPU: 사용률, 코어수, Load Average, Context Switch
- 메모리: 사용률, Cached, Buffers, Available, Swap, 스왑 page-in/out(초당), Major/Minor 페이지폴트(초당)
- 디스크: 사용률, I/O 읽기/쓰기(MB/s), busy 비율, 대기열 길이
- 프로세스: CPU 상위, 메모리 상위, 디스크 I/O 상위(프로세스별 major 폴트 포함)
- ※ Cached/Buffers는 리눅스 전용 개념이라 `/proc/meminfo`가 있을 때만 수집 (macOS에선 생략)
- ※ I/O·페이지폴트는 누적값이 아니라 진단 시점에 **1초간 측정한 초당 값** (`MetricsService.sampleIo`)
- ※ 프롬프트에 각 지표의 뜻을 설명하고, "근거 수치를 함께 언급 / 수치가 낮으면 '뚜렷하지 않다'고 말할 것"을 규칙으로 둠

### 안정성 보완 사항
- AI 임계치 변경: 0~100 범위를 벗어난 값은 코드가 저장 거부
- 트래픽: 실제 초당 값(KB/s)으로 계산, loopback 제외
- Gemini 503/429(서버 혼잡) 시 최대 3회 재시도 후 한국어 안내 문구 표시

---

## 4. 설계 원칙 (반드시 지킴)

1. **판정은 코드, 해석은 AI**
   - 정상/주의/위험 판정 → 코드가 임계치로 결정 (일관성)
   - 원인 해석·조치 제안 → AI가 (사람 말로 설명)
2. **AI는 시스템을 직접 건드리지 않음**
   - 임계치 변경: AI는 자연어→JSON "번역"만, 실제 저장은 코드가
   - AI가 형식 어겨도 `{`~`}` / `[`~`]`만 추출해 파싱 (안정성)
3. **AI는 원인을 단정하지 않음**
   - "~일 가능성이 있습니다" 형태로만. 확인 방법·조치를 함께 제시
4. **위험한 조치(프로세스 끄기 등)는 안전장치 필수**
   - 승인 단계 + 시뮬레이션 스위치 (`SIMULATION=True/False`)
5. **화면과 로직 분리**
   - 프론트는 API로만 통신. 데이터를 직접 안 가짐
6. **판정 기준은 알림정책 값을 재사용** (임의로 만들지 않음)

---

## 5. Claude가 지킬 작업 규칙

1. **한 번에 하나씩** 진행하고, 각 단계를 검증한다.
2. 코드를 바꾸면 **왜 바꿨는지** 설명한다.
3. **API 키는 절대 코드에 하드코딩하지 않는다.** (환경변수 or gitignore된 파일)
4. 이 문서의 기능 명세를 벗어나지 않는다. 새 기능은 사용자와 합의 후 추가.
5. 에러가 나면 추측하지 말고 **실제 에러 메시지**를 먼저 확인한다.
6. 사용자가 초보 관점일 수 있으니 **용어를 쉽게 풀어서** 설명한다.
7. 회사(심사자)를 존중하는 톤을 코드 주석·문구에도 유지한다.
8. 검증용으로 앱을 직접 띄웠다면 끝난 뒤 반드시 종료한다 (8080 포트 충돌 방지).

---

## 6. 발표 대비 핵심 방어 논리

- **"왜 3개 지표만?"** → 핵심(CPU/메모리/디스크)에 집중, 확장 가능하게 설계
- **"AI가 원인을 어떻게 아냐?"** → 단정 아님. 세부지표 근거로 "가능성+확인방법" 제시
- **"DB/서버 여러 대는?"** → 정책은 이미 PostgreSQL에 저장. 현재 모니터링은 단일서버, Agent 추가로 확장 가능한 구조
- **"AI 서버가 멈추면?"** → 503/429 자동 재시도 + 사용자 안내. 판정은 코드가 하므로 AI 장애와 무관하게 대시보드는 정상 동작
- **"실제 AiWACS와 연동은?"** → 권한상 독립 구현, API 열리면 연동 가능
- **AI 활용 깊이** → 단순 호출이 아니라 프롬프트 설계(역할 부여, JSON 강제, 세부지표 근거)로 통제

---

## 7. 현재 단계

- **핵심 기능 구현 완료** (2026-09-23). 화면 3개 + API 10개 전부 동작 확인, GitHub 반영
- 다음 할 일 (3장의 "예정" 항목): AI 조치 실행(승인 + 시뮬레이션 스위치) → VM Agent → 정책-서버 매칭
