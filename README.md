# AiWACS AI 운영 도우미

> **"신입 담당자 옆에 앉은 선임"** — 통합 모니터링 솔루션 AiWACS에 얹는 AI 기능 제안

AiWACS는 서버 지표의 수집·표시·임계치 판정까지 안정적으로 제공합니다.
이 프로젝트는 그 다음 단계, 즉 **"그래서 지금 이 서버에 무슨 일이 일어나고 있는가"를 해석하는 일**을 AI가 곁에서 거들 수 있다면 어떨까 하는 제안입니다.

숙련된 담당자라면 지표 몇 개만 보고도 상황을 짐작하지만, 신입 담당자에게는 쉽지 않은 일입니다.
AI 운영 도우미는 선임처럼 옆에서 **상태를 진단해 주고**, 필요하면 **말 한마디로 기준(임계치)을 바꿔** 줍니다.

---

## 주요 기능

### 1. 메인 대시보드 — 실시간 모니터링
AiWACS 화면 구성을 참고해 재현했습니다.
- CPU / 메모리 / 디스크 현재값 + **정상 · 주의 · 위험** 판정
- Resource Map: CPU · MEMORY · DISK · TRAFFIC 탭 전환 그래프
- 프로세스 TOP5, 디스크 파티션별 사용률, 트래픽 송수신

### 2. 알림정책 — 임계치 관리
- 고객사 + 정책명 + CPU/메모리/디스크 **주의·위험 임계치**
- 여러 정책 등록 및 추가 · 수정 · 삭제
- 대시보드 판정은 이 정책 값을 그대로 사용

### 3. AI 운영 도우미
| 탭 | 설명 |
|---|---|
| **AI 상태 진단** | 현재 상태 + 세부 지표 + 상위 프로세스를 종합해 AI가 원인 가능성, 지표 간 인과관계, 확인 방법, 권장 조치를 쉬운 말로 설명 |
| **AI 임계치 설정** | "테라넷 DB서버 CPU 주의 75로, 메모리 위험은 88로 바꿔줘"처럼 자연어로 여러 임계치를 한 번에 변경 |

진단 정확도를 위해 수집하는 세부 지표:
- **CPU**: 사용률, 코어 수, Load Average, Context Switch
- **메모리**: 사용률, Available, Swap (리눅스에서는 Cached, Buffers 포함)
- **디스크**: 사용률, I/O 읽기/쓰기

---

## 설계 원칙

AI를 운영에 들일 때 가장 중요한 것은 **"AI를 얼마나 믿을 수 있게 통제하느냐"** 라고 보았습니다.

1. **판정은 코드, 해석은 AI**
   정상/주의/위험은 코드가 알림정책 임계치로 결정합니다 (항상 같은 결과).
   AI는 이미 내려진 판정을 바꾸지 않고, 원인과 조치를 사람의 말로 풀어 줄 뿐입니다.
2. **AI는 시스템을 직접 건드리지 않음**
   임계치 변경 시 AI는 자연어를 JSON으로 "번역"만 하고, 검증과 저장은 코드가 합니다.
   (예: 0~100을 벗어난 값은 코드가 저장을 거부)
3. **AI는 원인을 단정하지 않음**
   "~일 가능성이 있습니다" 형태로만 답하고, 직접 확인할 방법을 함께 제시합니다.
4. **AI 응답이 흐트러져도 안전하게**
   응답에서 JSON 부분만 골라내 처리하고, AI 서버가 혼잡할 때는 자동 재시도 후 안내 문구를 보여줍니다.
   판정은 코드가 하므로 AI에 문제가 생겨도 대시보드는 정상 동작합니다.
5. **화면과 로직 분리**
   화면은 API로만 데이터를 주고받습니다.

---

## 기술 스택

| 구분 | 사용 기술 |
|---|---|
| Backend | Java 25, Spring Boot 4.1, Spring Data JPA |
| Frontend | HTML, CSS, JavaScript (Vanilla) |
| 지표 수집 | OSHI |
| AI | Google Gemini API (`gemini-3.6-flash`) |
| DB | PostgreSQL 17 (Docker) |

---

## 프로젝트 구조

```
aiwacs/
├── docker-compose.yml               # PostgreSQL
├── pom.xml
└── src/main/
    ├── java/com/sysone/aiwacs/
    │   ├── monitor/                 # 시스템 지표 수집 + 대시보드 API
    │   ├── policy/                  # 알림정책 CRUD + 임계치 판정
    │   ├── ai/                      # Gemini 호출, 상태 진단, 자연어 임계치 변경
    │   └── config/                  # 화면 주소 연결
    └── resources/
        ├── static/                  # 화면 (index / policy / ai)
        └── application.properties
```

---

## 실행 방법

**준비물**: JDK 25, Docker Desktop, Gemini API 키

```bash
cd aiwacs

# 1. API 키 설정 (.env는 git에 올라가지 않습니다)
echo "GEMINI_API_KEY=발급받은_키" > .env

# 2. DB 실행
docker compose up -d

# 3. 애플리케이션 실행
./mvnw spring-boot:run
```

브라우저에서 http://localhost:8080 접속
- `/` 메인 대시보드 · `/policy` 알림정책 · `/ai` AI 운영 도우미
- 처음 실행하면 샘플 알림정책 3개가 자동으로 등록됩니다.

---

## API

| Method | URL | 설명 |
|---|---|---|
| GET | `/api/status` | CPU/메모리/디스크 현재값 + 판정 |
| GET | `/api/procs` | CPU 사용률 상위 프로세스 |
| GET | `/api/disk` | 파티션별 디스크 사용률 |
| GET | `/api/traffic` | 송수신 속도 (KB/s) |
| GET · POST | `/api/policies` | 알림정책 목록 조회 · 추가 |
| PUT · DELETE | `/api/policies/{id}` | 알림정책 수정 · 삭제 |
| POST | `/api/ai/diagnose` | AI 상태 진단 |
| POST | `/api/ai/threshold` | 자연어 임계치 변경 `{"message": "..."}` |

---

## 향후 계획

- [ ] **AI 조치 실행**: 프로세스 종료·재시작 등 제안된 조치 실행 (승인 단계 + 시뮬레이션 모드로 안전장치)
- [ ] **VM Agent**: 여러 서버 동시 모니터링
- [ ] **정책-서버 매칭**: 서버별로 다른 알림정책 적용

현재는 권한상 단일 서버 기준의 독립 구현이며, AiWACS API가 열린다면 실제 수집 데이터와 연동할 수 있는 구조로 설계했습니다.
