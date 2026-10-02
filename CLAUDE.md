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
- **시스템 지표 수집**: OSHI 7.6.1 — **Agent(`aiwacs-agent/`)에서만** 사용. 본체는 직접 측정하지 않음
- **AI**: 로컬 LLM — Ollama (모델: `gemma3:4b`, `http://localhost:11434/api/chat`, Spring RestClient로 호출). 보안상 지표가 외부로 나가지 않게 전환
  - 이전 방식 Google Gemini REST API(`GeminiClient`)는 코드를 남겨두고 `@Component`·설정만 주석 처리 (되돌릴 때 주석 해제)
  - Ollama 설치: `brew install ollama && brew services start ollama && ollama pull gemma3:4b`
- **DB**: PostgreSQL 17 (회사 스택, Docker로 실행). 테이블: `alert_policy`(정책), `monitored_server`(서버), `metric_history`(1분 평균 지표 이력)
- **API 키**: `aiwacs/.env` (gitignore) → `spring.config.import`로 읽음
- **개발 도구**: VSCode (자바 확장) + 저(Claude)와 바이브 코딩

### 전체 구조
```
[모니터링 대상 VM (Rocky Linux 9)]            [맥: AiWACS 본체]
 └─ aiwacs-agent.jar ── 2초마다 지표 전송 ─→  /api/agent/metrics → 서버 자동 등록(DB) + 최신 지표(메모리)
                                              → 대시보드 / 장비 목록 / 알림정책 / AI 운영 도우미
```
- 모니터링 대상은 **VM 2대**(Agent 설치). 맥은 AiWACS 본체만 실행하고 모니터링 대상에서 제외

### 프로젝트 구조
```
aiwacs/  (본체, Spring Boot)
src/main/java/com/sysone/aiwacs/
├── server/   AgentReport, MonitoredServer(엔티티), ServerService(수신·판정·서버 수정), ServerController(/api/agent/metrics, /api/servers, /api/companies)
├── monitor/  MonitorController(/api/status·procs·disk·traffic ?serverId=)
├── history/  MetricHistory(엔티티), MetricHistoryService(1분 평균 저장·7일 보관), MetricHistoryController(/api/history?date=, /api/history/days, /api/history/recent)
│             ProcessHistory(엔티티, 서버별 1분 프로세스 CPU 상위 8 + 메모리 상위 5), ProcessHistoryService(저장·보관), ProcessTrend(새로 등장/급증/원래 높음 계산)
├── policy/   Policy·Threshold(엔티티), PolicyService(CRUD·판정·서버별 정책 결정·임계치 변경), PolicyController(/api/policies)
├── alarm/    Alarm(발생/해제 + 처리 기록 여러 줄), AlarmService(3초 판정·사건 묶기·처리 기록), AlarmController(/api/alarms/active|history|handled, POST /api/alarms/handle)
├── action/   ActionCommand, ActionService(사람이 승인한 조치 → Agent 명령 대기열, 안전 검사·만료·시뮬레이션), ActionController(/api/actions)
├── ai/       AiClient(공통: JSON 추출·예외), OllamaClient(로컬 LLM, 사용 중), GeminiClient(사용 안 함),
│             AiService(임계치 설정·알림 묶기), DiagnosisService(진단·조치 추천·처리 기록 초안), AiController(/api/ai/*)
└── config/   WebConfig (/policy, /ai, /servers, /alerts, /alerts/handled 화면 주소 연결)
src/main/resources/static/  index.html, servers.html, policy.html, ai.html, alerts.html, alerts-handled.html, sidebar.js(모든 화면 공통 사이드바 메뉴)
docker-compose.yml           PostgreSQL (볼륨 이름 `aiwacs-pgdata`로 고정)

aiwacs-agent/  (모니터링 대상 서버에 설치, Java 17+, Spring 없음)
├── Collector.java       OSHI 수집. 변화량 지표는 직전 전송 이후 초당 값. 프로세스는 pid·시작 시각·사용자도 보냄
├── AgentMain.java       주기 전송, 끊겨도 재시도, 상태가 바뀔 때만 로그. 전송 응답에 실린 조치 명령을 받아 실행하고 결과를 다음 전송에 실음
├── ActionExecutor.java  조치 실행(정상 종료 SIGTERM / renice +10만). 실행 직전 PID·이름·시작 시각 재확인, 보호 프로세스 거절
└── AgentConfig.java     agent.properties / 환경변수 (server.url, server.name, agent.token, interval.sec, action.enabled)
```
- API 응답 JSON 모양은 프론트(static HTML)가 기대하는 형식을 유지한다. 바꾸면 화면도 함께 수정해야 함.

### 실행 방법
```bash
# 본체 (맥)
cd aiwacs
docker compose up -d      # DB 켜기 (Docker Desktop 필요)
./mvnw spring-boot:run    # http://localhost:8080

# Agent 빌드 (맥) → aiwacs-agent/target/aiwacs-agent.jar 를 VM으로 복사
cd aiwacs-agent && ./mvnw package

# VM (Rocky 9, root)
dnf install -y java-21-openjdk-headless
cd /root/aiwacs-agent && java -jar aiwacs-agent.jar   # 같은 폴더에 agent.properties
```
- **네트워크 주의**: 집 공유기 와이파이는 기기 간 통신 차단(AP 격리) → **휴대폰 핫스팟**으로 맥·윈도우(VM 호스트) 연결. VirtualBox 네트워크는 **브리지**. 맥 IP가 바뀌면 `agent.properties`의 `server.url` 수정 필요

---

## 3. 기능 명세

### 화면 4개
- 공통 사이드바(`sidebar.js`): 대시보드(요약·실시간) / AI 운영 도우미(상태 진단·임계치 변경) / 장비 목록 / ICMP … / 정책 관리(알림 정책 …) / 그룹 설정 / 환경설정. 하위 메뉴는 화살표로 펼침. 링크 없는 항목은 AiWACS 메뉴 구성 재현용 자리
1. **메인 대시보드** — VM 실시간 모니터링 (AiWACS 스타일 재현)
   - 상단 `Company ▾`로 고객사 필터, "모니터링 서버" 탭으로 서버 선택 (온라인 점 + 대표 상태)
   - 장비 현황(전체/주의/경고/위험/장애/다운, OS별) = 서버 목록 기준 집계
   - 선택 서버의 CPU/메모리/디스크 판정, Resource Map, 프로세스 TOP5, 디스크 파티션, 트래픽
   - Resource Map: 기본은 **오늘 하루**(가로 00:00~24:00, 세로 %, 15분 단위 막대 96개 = 1분 평균 기록을 15분씩 묶은 평균, 마우스를 올리면 그 15분의 평균·최고값). 날짜 선택으로 지난 날짜(보관 7일) 조회, 기록이 없는 칸은 비움
   - CPU → MEMORY → DISK → TRAFFIC 탭이 10초마다 슬라이드 모션으로 자동 전환 (마우스를 올리면 멈춤)
   - '실시간(최근 80초, 2초 간격)'도 선택 가능 — 본체 메모리에 보관해 서버 전환·화면 이동 후에도 유지
   - 사용량 위젯(CPU·메모리 TOP5, 디스크, 트래픽): 세로 막대그래프, 항목 수에 따라 막대 너비 자동 조절
2. **장비 목록** (`/servers`) — Agent가 자동 등록한 서버 관리
   - 장비 이름(표시 이름) 변경, 고객사 지정, 적용 정책 지정 (그 고객사의 정책만 선택 가능)
   - Agent ID(`agent.properties`의 server.name)는 서버 식별용으로 유지, 화면 이름만 따로 변경
3. **알림정책** — 임계치 설정 (AiWACS와 같은 주의/경고/위험/장애 4단계, 순서 검증 + 여러 정책 + 고객사 + CRUD + 수정일자 자동 기록). 고객사 목록은 정책의 고객사에서 가져옴
4. **AI 운영 도우미** — 탭 2개
   - **AI 상태 진단**: 서버 + 구간 선택 — 메뉴에서는 지금 이 순간(기본) / 최근 30분, 알림·사건의 [진단·조치]로 들어오면 **사건 시작 5분 전 ~ 지금**으로 자동 설정(최대 180분) → 코드가 프로세스 이력으로 원인 후보(새로 등장/급증/원래 높음)를 계산하고 AI가 해석. 부하가 끝난 뒤에 눌러도 그 시간의 원인이 나옴 (오프라인 서버는 진단 불가)
     - **조치 제안**: AI가 후보 중 하나에 정상 종료 / 우선순위 낮추기 / 그대로 두기를 *추천*만 함 → 사람이 확인 창에서 [실행]해야 명령 생성 → Agent가 다음 전송 때 가져가 실행 → 결과 표시 + 그 서버의 처리 대기 알림에 "⚙ 조치" 처리 기록(점검 중)으로 자동 기록 (조치는 처리 과정 중의 행동 하나 → 기록은 처리 내역 한 곳에)
   - **AI 임계치 설정**: 자연어로 정책 임계치 변경 (여러 개 동시 가능)
5. **알림 내역 / 처리 내역** (`/alerts`, `/alerts/handled`)
   - 발생/해제(코드가 자동)와 처리(사람이 기록)는 별개. 처리 상태는 AiWACS와 같은 4가지(ON MAINTENANCE=점검 중 / COMPLETE=완료 / HOLD=보류 / IGNORE=무시), 한 알림에 기록이 여러 줄 쌓임
   - **✦ AI 초안**: 선택한 알림의 경과 + 그 서버의 직전 진단 + 조치 이력으로 처리 내용 초안 작성 → 사람이 고쳐서 저장 (AI 실패 시 코드가 기본 초안)
   - 알림은 줄이지 않는다 (피드백: 반복 알림 = "아직 처리 안 함" 신호). 사건 묶기는 원본·발생 횟수를 그대로 둔 채 '모아 보기'만 — 결과는 표 위에, 발생 중 + 처리가 끝나지 않은 알림 대상, 10분 넘게 떨어지면 다른 사건
   - 알림 내역: 탭(처리 필요 / 발생 중 / 전체, 기본 처리 필요) + 20건씩 페이지. 처리 내역: 한 번에 같이 저장한 기록은 한 줄로, 처리 내용은 두 줄까지(눌러서 펼침)
   - 시연 흐름: 부하 → 알림 → 사건 묶어 보기 → [AI 진단·조치] → 원인 확인 → 정상 종료 승인 → 자동 해제 → 처리 기록(AI 초안) → 처리 내역

### 판정 기준 (서버별)
- 서버에 지정한 정책 → 없으면 **그 서버 고객사의 첫 번째 정책** → 고객사도 없으면 전체 첫 번째 정책
- 정책을 삭제하면 그 정책을 쓰던 서버는 자동으로 기본 정책으로
- 오프라인(10초간 수신 없음) 서버는 판정하지 않음

### 완성 상태
- [x] 메인 대시보드 (실시간, 서버 선택, 고객사 필터)
- [x] 알림정책 (여러 개 + 고객사 + CRUD)
- [x] 임계치 → 판정 연결
- [x] AI 임계치 변경 (자연어, 여러 개 동시)
- [x] AI 상태 진단 (세부지표 활용, 서버별)
- [x] VM Agent (여러 서버 모니터링, 자동 등록, 토큰 옵션)
- [x] 정책-서버 매칭 (고객사 → 서버 → 정책), 장비 이름 변경
- [x] 지표 이력 저장 (1분 평균, 7일 보관 `history.retention-days`) → Resource Map 날짜별 하루 그래프
- [x] 프로세스 이력(1분) + 구간 진단 (부하가 끝난 뒤에도 원인 추적)
- [x] AI 조치 실행 (정상 종료 / 우선순위 낮추기, 사람 승인 + Agent 허용 + 재확인 + 만료 + 시뮬레이션)
- [x] 처리/해제 분리, 처리 상태 4종, AI 처리 기록 초안
- [ ] 알람·조치 이력 DB 저장 (지금은 메모리 — 앱 재시작 시 초기화되므로 시연 중 재시작 금지)
- [ ] Agent 로그 영어화(VM 콘솔 한글 깨짐) — 예정
- [x] 로컬 LLM(Ollama)으로 전환 — AI 호출은 `AiClient` 뒤에 숨겨져 있어 `@Component`만 바꾸면 Gemini와 교체 가능

### 세부 지표 (진단 정확도용, Agent가 OSHI로 수집)
- CPU: 사용률, 코어수, Load Average, Context Switch
- 메모리: 사용률, Cached, Buffers, Available, Swap, 스왑 page-in/out(초당), Major/Minor 페이지폴트(초당)
- 디스크: 사용률, I/O 읽기/쓰기(MB/s), busy 비율, 대기열 길이
- 프로세스: CPU 상위, 메모리 상위, 디스크 I/O 상위(프로세스별 major 폴트 포함)
- ※ Cached/Buffers는 리눅스 전용 개념이라 `/proc/meminfo`가 있을 때만 수집 (macOS에선 생략)
- ※ I/O·페이지폴트·CPU·트래픽은 누적값이 아니라 **Agent 직전 전송 이후(기본 2초)의 초당 값** (`Collector.collect`)
- ※ 프롬프트에 각 지표의 뜻을 설명하고, "근거 수치를 함께 언급 / 수치가 낮으면 '뚜렷하지 않다'고 말할 것"을 규칙으로 둠

### 안정성 보완 사항
- AI 임계치 변경: 0~100 범위를 벗어난 값은 코드가 저장 거부
- 트래픽: 실제 초당 값(KB/s)으로 계산, loopback 제외
- Gemini 503/429(서버 혼잡) 시 최대 3회 재시도 후 한국어 안내 문구 표시

---

## 4. 설계 원칙 (반드시 지킴)

1. **판정은 코드, 해석은 AI**
   - 정상/주의/경고/위험/장애 판정 → 코드가 임계치로 결정 (일관성)
   - 원인 해석·조치 제안 → AI가 (사람 말로 설명)
2. **AI는 시스템을 직접 건드리지 않음**
   - 임계치 변경: AI는 자연어→JSON "번역"만, 실제 저장은 코드가
   - AI가 형식 어겨도 `{`~`}` / `[`~`]`만 추출해 파싱 (안정성)
3. **AI는 원인을 단정하지 않음**
   - "~일 가능성이 있습니다" 형태로만. 확인 방법·조치를 함께 제시
4. **위험한 조치(프로세스 끄기 등)는 안전장치 필수**
   - AI는 조치 API를 부르지 않음. 화면 확인 창에서 사람이 [실행]을 눌러야만 명령 생성
   - 그 서버 Agent가 `action.enabled=true`일 때만 실행 (기본 꺼짐 = 서버 관리자가 직접 허용)
   - 조치는 정상 종료(SIGTERM)·우선순위 낮추기(renice +10)만. 강제 종료·셸 명령 없음
   - 실행 직전 Agent가 PID·이름·시작 시각 재확인(PID 재사용 방지), 보호 프로세스(systemd·sshd 등·Agent 자신) 거절, 30초 내 미수신 시 만료
   - 시뮬레이션 스위치: 본체 `action.simulation=true`(환경변수 `ACTION_SIMULATION`)면 Agent에 보내지 않고 기록만
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
- **"서버 여러 대는?"** → Agent 방식. 새 서버는 Agent 설치만 하면 자동 등록 (VM 2대로 시연, 즉석에서 추가도 가능). 대규모는 목록/필터·그룹 정책·지표 이력 저장으로 확장
- **"고객사별 관리는?"** → 고객사 → 서버 → 정책 구조. 서버는 자기 고객사의 정책으로만 판정, 대시보드 Company 필터
- **"AI 서버가 멈추면?"** → 503/429 자동 재시도 + 사용자 안내. 판정은 코드가 하므로 AI 장애와 무관하게 대시보드는 정상 동작
- **"실제 AiWACS와 연동은?"** → 권한상 독립 구현, API 열리면 연동 가능
- **AI 활용 깊이** → 단순 호출이 아니라 프롬프트 설계(역할 부여, JSON 강제, 세부지표 근거)로 통제
- **"원격으로 프로세스를 끄는 건 위험하지 않나?"** → AI는 추천만, 실행은 사람 승인 + 서버 관리자가 Agent에서 허용해야만. 종료는 SIGTERM(정리 후 종료)만, 실행 직전 재확인·보호 프로세스·만료·전 과정 기록. 운영 정책에 따라 끌 수 있는 옵션
- **"알림을 묶으면 처리 안 한 게 가려지지 않나?"** → 원본 알림과 발생 횟수는 그대로. 묶음은 모아 보기일 뿐이고, 목표는 알림을 줄이는 게 아니라 빨리 처리·기록하게 돕는 것 (처리 기록이 빈칸으로 남지 않게 AI 초안)

---

## 7. 현재 단계

- **VM Agent + 정책-서버 매칭 + 고객사 구조 구현** (2026-09-23). VM 1대(rocky-01) 실제 연결 확인
- 사용자 결정: 심사자 피드백은 "하나만"이었지만 **VM 2대**로 시연하기로 함
- **진단 → 조치 → 처리 흐름 구현** (2026-10-02): 프로세스 이력·구간 진단, 사람 승인 조치(Agent 명령 통로), 처리/해제 분리 + AI 처리 기록 초안
- 다음 할 일: Mac에서 실제 VM으로 전체 흐름 확인 (VM의 agent.properties에 `action.enabled=true`, 새 Agent jar 배포)
