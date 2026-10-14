# 학생자치 결정 추적 서비스 (council-flow, 임시 이름)

학생자치 회의에서 확정한 결정을 부서와 관련 학생회에 전달하고, 담당·기한·승인·이행 결과를 연결해 추적하는 웹 서비스입니다.
설계 원칙과 도메인 모델은 [CLAUDE.md](CLAUDE.md)에 있습니다.

## 분석 요청이 몰릴 때

회의록은 DB 작업 큐에 접수하고, CLOVA의 호출·토큰 예산이 남을 때 처리합니다. 대기 중에는 ‘현재 분석 요청이 많아 잠시 대기 중입니다’ 안내를 표시합니다. 화면을 닫아도 서버 작업은 유지됩니다. 토큰 계산기 장애·영구 오류·처리 여부를 모르는 타임아웃은 대기 상태로 숨기지 않습니다.

`app.clova.quota` 기본값은 `enabled=true`, `scope=main-HCX-007-test`, `requests-per-minute=60`, `tokens-per-minute=60000`, `budget-ratio=0.9`, `max-wait=30m`입니다. 같은 메인 계정·모델·이용 목적의 모든 Worker는 같은 scope와 한도를 설정합니다. 서비스 키나 계정을 바꾸면 실제 응답 헤더를 다시 확인합니다. 토큰 계산 호출도 보수적으로 요청 예산에 포함합니다. 대기 중에는 DB 잠금을 유지하지 않고 임대를 갱신합니다.

## 필요한 것

| 도구 | 버전 | 비고 |
|---|---|---|
| JDK | 21 | 더 높은 JDK로 테스트하면 테스트 도구가 깨질 수 있습니다 |
| Docker | Docker Desktop 등 | 로컬 MySQL과 테스트(Testcontainers)에 씁니다 |
| Maven | 설치 안 해도 됨 | 저장소의 `./mvnw`(Windows는 `mvnw.cmd`)가 알아서 내려받습니다 |
| Node.js | 설치 안 해도 됨 | Maven 빌드가 `frontend/node`에 Node 22를 내려받아 씁니다. 프론트만 따로 개발할 때는 Node 22 이상 |

JDK가 여러 개면 실행 전에 21을 고릅니다. 예(macOS): `export JAVA_HOME=$(/usr/libexec/java_home -v 21)`

## 로컬 실행

```bash
docker compose up -d --wait
```

```bash
./mvnw spring-boot:run -Dspring-boot.run.profiles=local
```

브라우저에서 http://localhost:8080 을 엽니다. 처음 실행하면 Flyway가 테이블을 만들고, 시드 데이터가 들어갑니다.

> **3306 포트를 이미 쓰고 있다면** (로컬에 MySQL/MariaDB가 떠 있는 경우)
> `MYSQL_PORT=3307 docker compose up -d --wait` 로 띄우고, 앱도 `DB_URL=jdbc:mysql://localhost:3307/councilflow` 를 붙여 실행합니다.

### 시드 계정 (local 프로필 전용, 가상 데이터)

그룹: 총학생회 · AI 융합대학 학생회 · 컴퓨터공학과 학생회 · 소프트웨어학과 학생회.
그룹마다 부서 3개(기획부·홍보부·재정부)와 역할별 계정 5개가 있습니다.

| 아이디 | 역할 |
|---|---|
| `{코드}` | 회장(owner) |
| `{코드}.plan` | 기획부장(editor) |
| `{코드}.pr` | 홍보부장(editor) |
| `{코드}.fin` | 재정부장(editor) |
| `{코드}.member` | 기획부 부원(viewer) |
| `admin` | 서비스 관리자(그룹 소속 없음) |

`{코드}`: `union`(총학) · `ai`(AI 융합대학) · `ce`(컴공) · `sw`(소프트웨어). 예: 컴공 회장은 `ce`, 컴공 홍보부장은 `ce.pr`

### 역할

그룹 종류(총학·단과대·학과)와 상위 그룹은 화면 표시용입니다. 권한은 그룹 안 역할로만 정해집니다.

| 역할 | 할 수 있는 일 |
|---|---|
| 회장(owner) | 회의록 올리기·분석, 후보 고치기·승인, 업무 편집·추가·완료 확정, 체크리스트, 다른 그룹에 업무 요청·결과 확정, 받은 요청 수락·담당 부서 지정, 가입 신청 승인(직책·부서 지정)·부장·부원 초대 |
| 부장(editor) | 자기 부서 업무와 자기 부서가 맡은 받은 요청만 시작·결과 제출, 자기 부서 업무의 체크리스트를 만들어 부서 사람에게 맡김. 나머지는 보기만 |
| 부원(viewer) | 보기 + 자기에게 맡겨진 체크리스트 완료 체크 |
| 서비스 관리자 | 그룹 만들기, 그룹에 회장 계정 주기·거두기, 모든 그룹 읽기 전용 열람(고치기는 403) |

부장(editor)·부원(viewer)은 **권한 수준**이고, 실제로는 회장이 정한 **직책**을 줍니다. 직책마다 권한 수준(부장급·부원급)이 있어
예를 들어 "총무(부장급)", "서기(부원급)"처럼 만들 수 있습니다. 새 그룹에는 기본 직책 "부장(부장급)", "부원(부원급)"이 있습니다.

회장 자리는 서비스 관리자만 줍니다(**서비스 관리** 화면). 서비스 관리자는 그룹을 만들 때 **가입 코드**도 정합니다(시드 그룹은 `union`·`ai`·`ce`·`sw`).

### 회원가입과 승인

1. 회장은 대시보드의 **구성원·부서·직책**에서 가입 코드와 가입 링크(`/signup?code=ce`)를 보고 부장·부원에게 알려 줍니다.
2. 부장·부원은 로그인 화면의 **회원가입**에서 가입 코드·아이디·이름·비밀번호(8자 이상)를 넣습니다. 계정은 바로 생기지만 그룹 화면은 아직 못 씁니다.
3. 회장은 **구성원·부서·직책 → 구성원**의 "가입 신청"에서 직책과 부서를 골라 **승인**하거나, 사유를 적어 **거절**합니다. 승인하면 바로 그룹 화면을 쓸 수 있습니다.
4. 거절되면 신청한 사람에게 사유가 보이고, 다시 신청할 수 있습니다. 이미 계정이 있으면 **내 계정**에서 가입 코드로 다른 그룹에 신청합니다.

회장이 **구성원** 탭에서 아이디와 처음 비밀번호를 직접 만들어 초대할 수도 있습니다. 비밀번호는 이름을 눌러 **내 계정**에서 바꿉니다.

### 부서·직책 설정

회장은 대시보드의 **구성원·부서·직책**에서 탭을 골라 그룹 구성을 정합니다. 구성원은 누구나 볼 수 있고, 바꾸기는 회장만 합니다.

- **부서** 탭: 부서를 만들고 이름·역할 키워드를 고치거나 없앱니다. 역할 키워드는 AI가 회의록에서 담당 부서를 추천할 때 씁니다.
  없앤 부서는 지우지 않고 숨기므로 지난 업무 기록에 이름이 남고(업무보드 트리의 "없앤 부서"), 같은 이름으로 다시 만들면 되살아납니다.
  구성원이 있거나, 끝나지 않은 업무·받은 요청이 있거나, 마지막 부서면 없앨 수 없습니다.
- **직책** 탭: 직책 이름과 권한 수준(부장급·부원급)을 정합니다. 권한 수준을 바꾸면 그 직책을 가진 사람의 권한도 바로 바뀝니다.
  부장급은 부서가 있어야 하므로, 부서 없는 사람이 있는 직책은 부장급으로 올릴 수 없습니다. 사람이 있는 직책과 마지막 직책은 지울 수 없습니다.
- **구성원** 탭: 가입 신청 승인, 직접 초대, 직책·부서 바꾸기, 내보내기를 합니다.

비밀번호는 모든 시드 계정이 `1234`입니다(`application-local.yml`의 `app.seed.password` 기본값, 로컬 전용). 바꾸려면 `SEED_PASSWORD` 환경변수를 넣습니다.
시드는 그룹 테이블이 비어 있을 때만 들어갑니다. 다시 넣으려면 `docker compose down -v`로 DB를 지우고 다시 띄웁니다.
권한 개편(V5) 전에 만든 DB라면 예전 역할은 서버를 띄울 때 자동으로 회장·부장으로 바뀌지만, `.member`·`admin` 계정은 생기지 않습니다. 필요하면 DB를 지우고 다시 띄웁니다.
가입 기능(V6) 전에 만든 DB라면 그룹 가입 코드가 비어 있어 가입 신청을 받지 않습니다. `admin`으로 **서비스 관리**에서 코드를 정합니다.

### 프론트만 빠르게 고칠 때

```bash
cd frontend && npm install && npm run dev
```

http://localhost:5173 에서 열리고, `/api` 요청은 8080의 Spring 서버로 넘어갑니다(서버는 따로 띄워 둡니다).

## 프로필

| 프로필 | 용도 |
|---|---|
| `local` | 로컬 개발. docker compose MySQL, 개발용 JWT 비밀값, 시드 데이터 |
| `prod` | NCP 운영. 모든 접속 정보와 비밀값을 환경변수로만 받습니다(기본값 없음) |
| `worker` | 비동기 작업 서버. HTTP API 없이 DB `job` 테이블만 폴링해서 처리합니다. 운영에서는 App과 따로 띄웁니다 |
| `fake-ai` | CLOVA를 부르지 않고 고정 응답을 씁니다(부하 실험·데모 예비용). 녹음 전사도 데모 전사로 고정됩니다. 예: `local,fake-ai` |

`local` 프로필은 편의상 App 하나가 분석 작업까지 처리합니다(`app.worker.enabled=true`). 따로 Worker를 띄울 필요가 없습니다.

Worker 작업 임대는 기본 10분이며, 처리 중에는 별도 스레드가 약 3분 20초마다 10분 뒤까지 연장합니다.
완료·실패 시 갱신을 중단하고, 다른 Worker가 가져간 작업이나 이미 만료된 임대는 연장하지 않습니다.
Worker가 꺼지면 마지막 갱신으로부터 최대 10분 후 다른 Worker가 다시 가져갈 수 있습니다(실제 복구시간에는 폴링·대기 작업 처리 시간이 더해집니다).
갱신 실패는 작업 ID와 오류 종류만 기록합니다. DB 장애 등으로 임대가 만료되면 외부 AI 호출이 중복될 가능성은 남아 있습니다.

## 환경변수

`.env.example`을 `.env`로 복사해서 씁니다. `.env`는 커밋하지 않습니다.
docker compose와 `local` 프로필 앱이 `.env`를 읽습니다(셸 환경변수가 있으면 그쪽이 먼저). 비워 둘 값은 `KEY=`로 두지 말고 줄을 지우거나 `#`으로 막습니다. 빈 값이 기본값을 덮어씁니다.

| 이름 | 필요한 곳 | 설명 |
|---|---|---|
| `DB_URL` | prod 필수, local 선택 | 예: `jdbc:mysql://localhost:3306/councilflow` |
| `DB_USERNAME`, `DB_PASSWORD` | prod 필수, local 선택 | DB 계정 |
| `JWT_SECRET` | prod 필수, local 선택 | 32바이트 이상. 없으면 앱이 뜨지 않습니다. 예: `openssl rand -base64 48` |
| `CLOVA_STUDIO_API_KEY` | 실제 분석에 필요 | CLOVA Studio API 키. 서버(Worker)만 읽고 브라우저로 보내지 않습니다. 없으면 분석이 `AI_KEY_MISSING`으로 실패합니다 |
| `SPEECH_MODE` | 선택 | 녹음 전사 방식. `fake`(기본, local): 녹음과 상관없이 "(데모 전사)" 고정 문장 / `clova`(prod 기본): CLOVA Speech 실제 호출 |
| `CLOVA_SPEECH_INVOKE_URL` | 실제 전사에 필요 | CLOVA Speech 도메인 빌더 > 설정 > 연동 정보의 **Invoke URL** 전체(`https://`로 시작). 키를 넣으면 `SPEECH_URL_INVALID`로 실패합니다 |
| `CLOVA_SPEECH_SECRET_KEY` | 실제 전사에 필요 | 같은 화면의 **Secret Key**. 없으면 전사가 `SPEECH_KEY_MISSING`으로 실패합니다 |
| `STORAGE_MODE` | 선택 | 녹음 저장소. `local`(기본): 이 서버 폴더 / `ncp`(prod 기본): NCP Object Storage |
| `OBJECT_STORAGE_BUCKET` | prod 녹음에 필요 | 녹음용 비공개 버킷 이름(예: `council-recordings-…`). App·Worker 둘 다 |
| `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY` | prod 녹음에 필요 | NCP 인증키. Object Storage 권한만 준 Sub Account 키를 권장합니다. 셋 중 하나라도 비면 앱은 뜨고 녹음 업로드만 503(`STORAGE_UNAVAILABLE`)으로 막힙니다 |
| `LOCAL_STORAGE_DIR` | local 선택 | 로컬에서 올린 녹음을 저장할 폴더(기본: 시스템 임시 폴더 아래 `council-flow-recordings`) |
| `SEED_PASSWORD` | local 선택 | 시드 계정 비밀번호 |
| `MYSQL_PORT` | docker compose 선택 | 로컬 MySQL을 띄울 호스트 포트(기본 3306) |

## 녹음 전사 써 보기 (로컬)

로컬은 Object Storage 대신 서버 폴더(`LOCAL_STORAGE_DIR`)에 녹음을 저장합니다.
기본은 **데모 전사**라서 어떤 녹음을 올려도 "(데모 전사)"가 붙은 고정 문장 3줄이 나옵니다(녹음 내용이 아님).

실제 녹음 내용으로 전사하려면 `.env`에 아래 세 줄을 넣고 서버를 다시 띄웁니다.

```
SPEECH_MODE=clova
CLOVA_SPEECH_INVOKE_URL=https://clovaspeech-gw.ncloud.com/external/v1/...   # 연동 정보의 Invoke URL
CLOVA_SPEECH_SECRET_KEY=...                                               # 연동 정보의 Secret Key
```

- 로컬은 저장된 파일을 CLOVA Speech `/recognizer/upload`로 직접 보냅니다. 운영(prod)은 Object Storage에 있는 파일을 `/recognizer/object-storage`로 읽게 합니다.
- 회장 계정 → **회의록 올리기** → 녹음(또는 m4a·mp3·wav 파일) → 올리면 전사 작업이 돌고, 회의 화면에서 진행·실패 이유와 **전사 회의 대본 보기**가 나옵니다.
- 키가 틀리면 `SPEECH_HTTP_401`처럼 바로 실패하고, 응답을 기다리다 시간이 넘으면(기본 180초) 처리 여부를 몰라 다시 부르지 않습니다(`SPEECH_TIMEOUT`).
- 화자는 "화자1, 화자2"로만 저장하고 실명과 연결하지 않습니다.

## 운영 녹음 저장소 (NCP Object Storage)

운영(`prod`)은 녹음을 NCP Object Storage 비공개 버킷에 둡니다(`STORAGE_MODE` 기본 `ncp`).

1. 브라우저가 서버에서 받은 presigned PUT 주소(15분 유효)로 버킷에 **직접** 올립니다. 녹음이 App 서버를 거치지 않습니다.
2. 전사 작업(Worker)이 버킷에 파일이 있는지 확인한 뒤, CLOVA Speech 에 `/recognizer/object-storage`로 그 키를 넘깁니다.
   CLOVA Speech 가 버킷을 직접 읽으므로 **도메인 설정에서 이 버킷을 Object Storage 로 연동**해야 합니다.

준비할 것:

- App·Worker 환경변수: `OBJECT_STORAGE_BUCKET`, `OBJECT_STORAGE_ACCESS_KEY`, `OBJECT_STORAGE_SECRET_KEY`.
  Worker 에는 `CLOVA_SPEECH_INVOKE_URL`, `CLOVA_SPEECH_SECRET_KEY`도 넣습니다.
- 버킷은 비공개로 둡니다. 버킷 **접근 제어**로 특정 서버만 허용하면 브라우저 업로드와 CLOVA 읽기가 막힐 수 있습니다.
- 버킷 CORS: 사이트 주소에서 오는 `PUT`을 허용합니다. 브라우저가 `Content-Type` 헤더를 보내므로 헤더도 허용합니다.
  콘솔에 메뉴가 없으면 S3 호환 API로 넣습니다(키는 NCP 인증키, 지역 `kr-standard`).

  ```json
  {"CORSRules":[{"AllowedOrigins":["https://서비스주소"],"AllowedMethods":["PUT"],"AllowedHeaders":["*"],"MaxAgeSeconds":3000}]}
  ```

  ```bash
  aws --endpoint-url=https://kr.object.ncloudstorage.com --region kr-standard s3api put-bucket-cors --bucket 버킷이름 --cors-configuration file://cors.json
  ```

- 버킷·키가 비어 있어도 앱은 뜹니다. 그때는 녹음 업로드만 503 으로 막히고 텍스트 회의록은 그대로 씁니다.
- presigned PUT 은 파일 크기를 강제하지 못합니다. 100MB 제한은 업로드 주소를 줄 때 신고한 크기로만 확인합니다.

## 테스트

```bash
./mvnw verify
```

프론트 빌드 → 백엔드 테스트 → jar 패키징을 한 번에 합니다. CI(GitHub Actions)도 같은 명령을 돌립니다.
테스트는 Testcontainers로 실제 MySQL 8 컨테이너를 띄우므로 Docker가 켜져 있어야 합니다. 외부 API는 실제로 호출하지 않습니다.

백엔드 테스트만 빠르게 돌릴 때(프론트 빌드 건너뛰기):

```bash
./mvnw test -Dskip.installnodenpm -Dskip.npm
```

## 회의 분석 써 보기 (단계 1)

1. 저장소 루트의 `.env`에 `CLOVA_STUDIO_API_KEY=발급받은키`를 넣고 서버를 띄웁니다(`.env.example`을 복사해서 시작).
   `local` 프로필은 `.env`를 읽습니다. 키 없이 화면만 보려면 `fake-ai`를 붙입니다(고정 응답).

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local
   ```

   ```bash
   ./mvnw spring-boot:run -Dspring-boot.run.profiles=local,fake-ai
   ```

2. 회장 계정(예: `ce`)으로 로그인 → **회의록 올리기**.
   한 줄에 발언 하나, "이름: 내용" 형식이면 화자가 따로 저장됩니다. 연습용 회의록: [scripts/eval/samples/meeting-01.txt](scripts/eval/samples/meeting-01.txt)
   **파일에서 불러오기**로 `.txt`나 `.pdf`(10MB·100쪽 이하)를 고를 수 있습니다. PDF는 서버가 글자만 뽑아 입력칸에 채우고(원본은 저장하지 않음),
   "이름: 내용" 회의록이면 PDF 줄바꿈으로 끊긴 발언을 이어 붙이고 쪽 번호 줄은 지웁니다. 스캔한 이미지 PDF는 글자가 없어 읽을 수 없습니다.
3. 올리면 바로 분석 작업이 만들어지고, 화면이 2초마다 상태를 확인합니다(보통 10초 안팎).
4. **AI 제안 검토**: 왼쪽 후보 카드를 누르면 오른쪽 회의록에서 근거 발언이 강조됩니다.
   - 근거 발언 ID가 없거나 회의록에 없는 ID를 인용한 후보는 "근거 확인 필요"로 표시됩니다(버리지 않음).
   - 담당 부서는 회의에서 말한 부서가 먼저입니다(그 부서 이름이 근거 발언에 실제로 있어야 합니다). 말하지 않았으면 AI가 업무 내용과 부서별 역할 키워드를 보고 부서를 골라 담당으로 넣고, "AI 지정"과 이유를 보여 줍니다. 맞지 않으면 회장이 승인 전에 바꾸면 되고, 바꾸면 "AI 지정" 표시가 사라집니다.
   - AI가 고를 근거가 없거나 없는 부서를 고르면 담당은 비워 두고, 역할 키워드로 고른 "추천(확정 아님)"만 보여 줍니다. 부서별 역할 키워드는 **구성원·부서·직책 → 부서** 탭에서 고칩니다.
   - 승인 전에는 요약·담당·기한을 고칠 수 있습니다. 고치기와 **승인**은 회장만 할 수 있습니다.
   - AI가 엉뚱한 업무를 냈으면 **후보에서 빼기**, 놓친 업무는 **업무 직접 추가**를 씁니다.
5. 승인한 결정의 업무만 **업무보드**에 나옵니다. 업무보드는 그룹 → 부서 → 사람 트리로 먼저 보이고,
   부서마다 단계별 업무 수·기한 지난 업무·나에게 맡겨진 일 수가 붙습니다. 담당이 없는 업무는 "담당 미정"에 모입니다.
   부서를 누르면 그 부서 업무가 할 일 / 진행 / 결과 제출 / 완료로 나뉘어 보입니다.
   담당 부서 부장은 시작·결과 제출, 회장은 완료 확정·반려를 합니다.
   회장은 카드의 **편집**으로 업무 내용·담당 부서·기한을 바꾸거나 **업무 취소**를 하고, 트리 화면의 **업무 추가**로 승인된 결정에 업무를 더합니다.
   기한이 지난 업무는 "기한 지남"으로 표시되고 따로 모아 볼 수 있습니다. 완료·취소된 업무는 고칠 수 없습니다.
6. **체크리스트**: 부장(또는 회장)은 업무 카드에서 할 일을 잘게 나눠 그 부서 사람에게 맡깁니다.
   맡은 사람은 자기 줄만 완료 체크할 수 있고, 대시보드의 "나에게 맡겨진 일"에서 모아 봅니다.

7. **삭제**(회장만, 되돌릴 수 없음): 업무 카드의 **삭제**는 어떤 상태의 업무든 체크리스트와 함께 지웁니다. 기록을 남기려면 **업무 취소**를 쓰세요.
   회의 화면 아래의 **회의 삭제**는 지우기 전에 함께 사라지는 것(결정·업무·체크리스트·다른 그룹에 보낸 요청)을 보여 주고 확인받습니다.
   다른 그룹에 보낸 요청은 받은 그룹의 수신함에서도 사라지고, 사람이 요청한 지출 검토는 남되 회의 연결만 끊깁니다(업무 카드의 자동 규정 확인은 함께 지워짐). 지운 기록은 감사 로그에 남습니다.

분석이 실패하면 화면에 사유가 나오고 **다시 분석**할 수 있습니다.
AI 응답을 기다리다 시간이 넘으면(240초) AI가 처리했는지 알 수 없으므로 자동으로 다시 부르지 않고 `FAILED_UNKNOWN`으로 남깁니다.

## 그룹 간 업무 요청 써 보기 (단계 2)

회장은 승인한 결정으로 자기 그룹을 뺀 **어느 그룹에든** 업무를 요청하고 이행 결과를 받습니다(상위·하위 관계 무관). 예: AI 융합대학(`ai`) → 컴공(`ce`)·소프트웨어(`sw`), 컴공(`ce`) → 소프트웨어(`sw`).

1. AI 융합대학 회장(`ai`)이 회의를 올리고 결정을 승인한 뒤, 결정 카드의 **다른 그룹에 업무 요청**을 누릅니다.
   받을 그룹(처음에는 아무것도 선택돼 있지 않음), 요청 종류(협조 요청 / 결과 보고 요청 / 알림), 기한, 요청 사항을 적어 보냅니다.
2. 학과 회장(`ce`)은 대시보드의 **받은 요청**에서 **수락 / 확인 요청(사유 필수) / 해당 없음**을 고르고, 안에서 맡을 **담당 부서**를 정합니다.
   알림은 "확인했습니다"를 누르면 끝납니다.
3. 학과 회장이나 담당 부서 부장이 **시작 → 결과 제출**(링크나 설명)을 합니다. 낸 결과는 고치지 않고, 반려되면 새로 냅니다.
4. AI 융합대학 회장은 **보낸 요청 → 결정**에서 대상별 상태와 집계(예: "완료 1 / 확인 요청 1 / 전체 2")를 보고,
   제출물을 **완료 확정** 또는 **반려(사유 필수)** 합니다. 확인 요청에는 답변하고, 기한을 바꾸면 받은 그룹 수신함에 "기한 변경됨: 이전 → 이후"가 보입니다.
   집계의 전체(분모)에는 해당 없음·취소가 들어가지 않습니다.

받은 그룹은 결정 요약과 요청 사항만 보고, 보낸 그룹의 회의록은 볼 수 없습니다. 상위 그룹도 다른 그룹의 회의록을 볼 수 없습니다(서비스 관리자의 읽기 전용 열람만 예외).

## 학교 규정 (공통 규정)

지출 절차 안내와 업무 카드의 규정 확인은 **서비스가 미리 넣어 둔 학교 규정**을 근거로 합니다. 회장이 규정을 올리거나 조항을 검수하는 화면은 없습니다.

| 규정 | 파일 | 상태 |
|---|---|---|
| 단국대학교 감사규정(2024.08.21 일부개정) | `src/main/resources/rules/audit-regulation-2024-08-21.json` | 검수 완료(확정) |
| 단국대학교 총대의원회 회칙(2025.11.28 개정) | `.../council-bylaws-2025-11-28.json` | 검수 완료(확정) |
| 단국대학교 학생회칙(2025.11.28 개정) | `.../student-union-bylaws-2025-11-28.json` | 검수 완료(확정) |
| 2026년 제3회 사무간담회 안내 자료(슬라이드) | `.../office-briefing-2026-09-01.json` | **검수 전(초안)** — 검색에 쓰이지 않음 |
| 감사 규정 3-2-5(2021, 본부 자체감사) | `.../university-audit-regulation-2021-02-25.json` | **검수 전(초안)** — 검색에 쓰이지 않음 |

- 앱이 뜰 때(`worker` 프로필 제외) json 을 모든 그룹이 쓰는 공통 규정으로 넣습니다(`RuleSeedLoader`). 같은 원본(`sourceSha256`)은 두 번 넣지 않습니다.
- `verified: true` 인 문서만 확정(CONFIRMED)하고 조항을 검수 완료로 둡니다. 사람이 원문과 대조해 검수한 문서에만 쓰세요. 검수 전 문서는 DRAFT 로만 들어가 후보 조항에 나오지 않습니다.
- 두 개의 검수 전 문서는 PDF 에서 자동으로 나눈 결과입니다. 원문과 대조한 뒤 json 의 `verified` 를 `true` 로 바꾸면 다음 기동에서 확정됩니다.
  2021 본부 감사규정은 학생자치기구의 감사규정이 아니라 대학 본부의 자체감사 규정입니다. 학생회비 지출과 관련이 적으니 쓸지 정해 주세요.
- 규정이 개정되면 json 을 바꿔(원본 파일의 SHA-256 이 바뀌면) 새로 배포합니다. 같은 제목의 옛 공통 규정은 새 규정이 확정될 때 폐기됩니다.
- 공통 규정은 화면과 API 로 고치거나 지울 수 없습니다(403). 그룹 전용 규정을 올리는 API(`POST /api/orgs/{id}/rule-documents`)는 남아 있지만 화면은 없고, 평가 스크립트와 테스트가 씁니다.
- 원본 PDF 는 저장하지 않습니다. 쪽번호 줄만 걷어낸 글자와 파일 지문(SHA-256)만 남습니다.

## 지출 절차 확인 써 보기 (단계 3)

규정을 확정해 두면, 지출 항목을 적었을 때 관련 조항을 찾아 원문 그대로 보여 줍니다. 후보 조항을 찾는 데는 AI를 쓰지 않습니다.
후보가 있으면 AI가 그 후보 안에서만 필요한 승인과 증빙을 정리하고, 서버가 근거를 하나씩 확인한 것만 화면에 보여 줍니다(아래 "AI 절차 안내").

1. 별도 준비는 없습니다. 위의 학교 규정(공통 규정)이 미리 들어 있고, 확정하지 않은 규정은 찾지 않습니다.
2. 대시보드의 **지출 절차 확인**에서 지출 항목(예: `MT 숙박비`)을 적고 **관련 조항 찾기**를 누릅니다. 금액·재원·집행 예정일·찾을 말은 선택입니다.
   승인된 결정에 지출 언급이 있으면 새 요청 폼의 **승인된 결정에서 불러오기**에서 고르면 항목·금액·재원이 미리 채워집니다(고친 뒤 요청).
3. 결과 화면에 후보 조항(관련도 순, 최대 10개)이 규정 이름·버전·시행일과 함께 나옵니다. 후보는 키워드로 찾은 것이라 실제 적용 여부는 사람이 판단합니다.

- 찾는 범위: 공통 규정과 그 그룹이 올린 규정 중 확정되어 집행 예정일(비우면 오늘)에 이미 시행 중인 것, 그리고 검수 완료한 조·보칙·부칙.
- 후보를 못 찾으면 `직접 확인 필요`(NEEDS_CHECK)이고 "문제가 없다는 뜻이 아닙니다"라고 알립니다. "문제없음" 상태는 없습니다.
- 규정에 쓰인 낱말과 지출 항목이 다르면(예: 항목은 `간식비`, 규정은 `다과`) 못 찾을 수 있습니다. **찾을 말**에 규정 속 낱말을 더하면 됩니다.
- 요청은 회장과 부장급 직책이 하고, 부원은 결과를 볼 수만 있습니다. 변경 기록(AuditLog)에 남습니다.
- 금액을 적으면 `300000` → "30만원"처럼 규정에 쓰이는 표기로도 찾습니다. 이 결과는 항목으로 찾은 조항 뒤에만 붙어서, 금액만 언급한 조항이 위로 올라오지 않습니다.

### AI 절차 안내

후보 조항이 있으면 요청과 함께 AI 안내 작업(EXPENSE)이 만들어지고 Worker가 처리합니다(local 프로필은 앱 하나가 같이 처리). 결과 화면은 처리 중이면 3초마다 새로 불러옵니다.

1. AI는 후보 조항의 **이름**(`C1`, `C2`…)과 필요한 승인·증빙·충돌 가능성·더 필요한 사실·참고 안내문만 냅니다. 조항 원문은 AI가 쓰지 않고, 화면의 원문은 항상 DB의 검수된 값 그대로입니다.
2. 서버가 AI 답을 확인합니다. 후보 목록에 없는 조항을 든 항목, 조항 원문에 없는 인용, 서로 다른 조항 둘을 가리키지 않은 충돌, "문제없음·승인 불필요·통과" 같은 판정 표현이 든 글은 버립니다. 인용은 목록 조항의 머리 문장과 호를 이어 붙여도 되지만 각 조각이 원문에 순서대로 그대로 있어야 합니다(공백·문장부호 차이는 무시). 버리거나 조정한 개수는 화면에 알려 줍니다.
   - 승인·증빙으로 분류됐지만 인용 구절이 "금지한다·넘지 못한다"만 말하면 서버가 금지·제한으로 옮깁니다.
   - 금지 표현이 있는 조항을 근거로 들었는데 인용에 승인·증빙을 말하는 낱말이 없으면 그 항목은 버립니다.
3. 결과는 `금지 가능성`(이 지출과 직접 관련 있어 보이는 금지 조항이 있음. 절차가 있다는 뜻이 아닙니다) / `충돌 가능`(후보 조항끼리 다르게 말함, 어느 쪽이 맞는지는 정하지 않음) / `절차 확인`(승인·증빙·한도 항목이 남음) / `확인 필요`(검증을 통과한 것이 없음)입니다. 어느 경우에도 "문제없음"은 없습니다.
   금지·제한은 지출 항목과 글자 조각이 겹치는 것만 위에 보이고, 관련이 없어 보이는 것은 지우지 않고 접힌 "참고"에 둡니다. 글자 비교라서 관련 있는 금지를 놓칠 수 있습니다.
4. 필요한 승인·증빙은 체크리스트가 됩니다. 항목마다 근거 조항과 원문에서 확인된 인용 구절이 붙고, 회장·부장급이 체크할 수 있습니다.
5. AI 안내를 못 받으면(`AI_TIMEOUT`, `AI_INVALID_RESPONSE` 등) 오류 종류가 보이고 후보 조항은 그대로 보입니다. **AI 안내 다시 받기**로 다시 요청할 수 있습니다. 응답 형식이 틀리면 서버가 정확히 한 번만 다시 요청합니다.
6. 회장이 **검토 완료**를 누르면 이 건은 잠깁니다(체크·다시 받기 불가). 이것은 "규정에 맞다"는 판정이 아니라 "사람이 안내와 조항 원문을 확인했다"는 표시입니다. 재정부 부장급도 할 수 있게 할지는 정해지지 않아 지금은 회장만 합니다.

- 화면에는 항상 "이 안내는 감사를 대체하지 않습니다" 안내문이 붙습니다.
- 조항이 너무 길면 앞부분만 AI에 보냅니다(`app.expense.clause-max-chars`, 기본 1500자). 검증도 실제로 보낸 글만 기준으로 합니다.
- 서비스 관리자는 읽기만 하고, 다른 그룹의 지출 검토는 볼 수 없습니다.
- `fake-ai` 프로필에서는 후보 첫 조항의 첫 줄을 인용하는 고정 응답이 나오며 "(가짜 AI)" 표시가 붙습니다.

### 회의 업무 카드의 규정 확인 (자동)

회의 분석이 끝나면 **돈과 관련된 업무 카드**를 서버가 규정에서 자동으로 확인하고, 카드에 배지를 답니다. 사람이 누르지 않아도 됩니다.

- 대상: 카드 제목·담당 표현에 구매·결제·숙박·회식·금액 같은 낱말이 있거나, 담당(또는 추천) 부서가 재정·총무·사무·회계 같은 이름인 카드.
  돈과 관련 없는 카드는 확인하지 않고 표시도 없습니다. 회의 하나에서 최대 15개(`app.rules.auto-check.max-per-meeting`).
- 표시: **금지 가능성**(빨강) · **위험**(주황) · **관련 조항 보기** · **규정 확인 안 됨** · 규정 확인 중. 모두 확정이 아니라 가능성이며,
  "문제없음"은 없습니다. 배지가 없다는 것이 안전하다는 뜻이 아닙니다.
- 카드에는 근거 조항 이름과 원문에서 확인된 인용 구절이 같이 보이고, **관련 조항 보기**를 누르면 지출 절차 확인 화면에서 조항 원문을 볼 수 있습니다.
- 표시일 뿐 결정이나 업무 승인을 막지 않습니다. 사람이 원문을 보고 승인합니다.
- AI 안내는 지출 절차 확인과 같은 작업(`EXPENSE`)으로 처리되어 몇 초 걸립니다. 그동안 카드에는 "규정 확인 중"이 보이고 끝나면 바뀝니다.
- `fake-ai` 프로필에서는 고정 응답이라 배지가 실제 모델 결과와 다를 수 있습니다("(가짜 AI)" 표시).
- 끄려면 `app.rules.auto-check.enabled=false`.

### 지출 안내 평가

`scripts/eval/expense/` 의 사례 9건(가상 재정 세칙 기준)으로 정답 조항이 나오는지, 규정에 없는 지출이 `확인 필요`로 나오는지, 화면 원문이 규정 원문과 같은지, AI 안내의 인용이 원문에 있는지를 확인합니다.
자세한 실행 방법은 `scripts/eval/README.md` 를 보세요. 같은 사례를 `ExpenseEvaluationSetTest` 가 매번 자동으로 돌립니다.

### AI 결과 평가

기준 답안과 AI 결과를 결정·업무 단위로 비교하는 스크립트가 있습니다. 사용법과 지금까지의 결과는 [scripts/eval/README.md](scripts/eval/README.md).

## API

| 요청 | 설명 |
|---|---|
| `POST /api/auth/login` | `{ email, password }` → `{ accessToken, tokenType, expiresIn }`. 토큰은 15분 동안 유효합니다 |
| `GET /api/me` | 내 정보, 서비스 관리자 여부(`serviceAdmin`), 활성 소속 목록 |
| `POST /api/auth/signup` | 회원가입 + 가입 신청 `{ loginId, name, password, groupCode }` → 201. 로그인 없이 부른다. 아이디가 있으면 409, 코드가 없으면 400 |
| `POST /api/me/password` | 비밀번호 변경 `{ currentPassword, newPassword }` (새 비밀번호 8자 이상) → 204 |
| `POST /api/me/join-requests` | 가입 코드로 다른 그룹에 신청 `{ groupCode }`. 이미 소속이거나 처리 전 신청이 있으면 409 |
| `GET /api/orgs/{id}/join-requests` | 처리 전 가입 신청(회장) |
| `POST /api/join-requests/{id}/approve` | 승인 `{ positionId, departmentId }` (회장, `If-Match`). 부장급 직책은 부서 필수 |
| `POST /api/join-requests/{id}/reject` | 거절 `{ reason? }` (회장, `If-Match`) |
| `GET /api/orgs/{id}` | 그룹 정보·부서·내 역할(`myRole`)·내 부서. 그 그룹의 활성 소속만 볼 수 있고, 아니면 403. 서비스 관리자는 `adminView: true`로 읽기만 |
| `POST /api/meetings` | 텍스트 회의록으로 회의 생성 `{ orgId, title, heldAt, transcript }` (회장) |
| `POST /api/meetings/extract-text` | PDF 회의록에서 글자 뽑기(multipart `orgId`, `file`) → `{ text, pageCount }`. 저장하지 않음 |
| `GET /api/orgs/{id}/meetings` | 그룹의 회의 목록 |
| `GET /api/meetings/{id}` | 회의 상태와 최근 분석 작업 |
| `POST /api/meetings/{id}/analysis` | 분석 작업 생성 → `{ jobId }` (202). 이미 승인한 결정이 있으면 409 |
| `GET /api/meetings/{id}/deletion-impact` | 회의를 지우면 함께 사라지는 것: 결정·업무·체크리스트·다른 그룹에 보낸 요청 수와 받은 그룹, 연결이 끊길 지출 검토 수, 분석 진행 여부(회장) |
| `DELETE /api/meetings/{id}` | 회의 완전 삭제(회장, 되돌릴 수 없음) → 204. 분석·전사 진행 중이면 409 |
| `GET /api/jobs/{id}` | 작업 상태(`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`FAILED_UNKNOWN`)와 오류 종류 |
| `GET /api/meetings/{id}/proposals` | 결정·업무 후보 + 후보마다 근거 발언 원문 + 회의록 |
| `PATCH /api/decisions/{id}` | 승인 전 요약·안건 수정. `If-Match: 버전` 필요 |
| `POST /api/decisions/{id}/approve` | 승인(회장). `If-Match: 버전` 필요 |
| `PATCH /api/tasks/{id}` | 제목·담당(`departmentId`)·기한(`dueDate`)·상태(`status`, 취소는 `CANCELLED`) 변경. `If-Match: 버전` 필요 |
| `DELETE /api/tasks/{id}` | 업무 완전 삭제(회장, 어떤 상태든, 체크리스트 포함) → 204 |
| `POST /api/decisions/{id}/tasks` | 업무 직접 추가 `{ title, departmentId?, dueDate? }` (회장) |
| `GET /api/orgs/{id}/decisions` | 승인된 결정 목록(업무 추가할 때 고르는 용도) |
| `GET /api/orgs/{id}/request-targets` | 요청할 수 있는 그룹(자기 그룹을 뺀 활성 그룹) |
| `POST /api/decisions/{id}/deliveries` | 다른 그룹에 업무 요청 `{ targetOrgIds, requestType, dueDate?, message? }` (보낸 그룹 회장, 승인된 결정만, 자기 그룹은 400) |
| `GET /api/decisions/{id}/delivery-summary` | 대상별 상태와 집계 `tally { total, counts }` (보낸 그룹 소속만) |
| `GET /api/orgs/{id}/inbox` | 받은 요청(그 그룹 소속만) |
| `GET /api/orgs/{id}/sent-deliveries` | 보낸 요청을 결정별로 |
| `PATCH /api/deliveries/{id}` | `status`(ACCEPTED·CLARIFICATION_REQUESTED·NOT_APPLICABLE·IN_PROGRESS·PENDING(답변)·CANCELLED), `reason`, `clarificationReply`, `internalDepartmentId`, `dueDate`. `If-Match` 필요 |
| `POST /api/deliveries/{id}/submissions` | 결과 제출 `{ resultLink?, description? }` |
| `POST /api/submissions/{id}/review` | 확정·반려 `{ approve, comment }` (반려는 comment 필수) |
| `GET /api/orgs/{id}/tasks` | 업무보드(승인된 업무만, 업무마다 체크리스트 `items`). `?departmentId=`로 거르기 |
| `POST /api/tasks/{id}/items` | 체크리스트 추가 `{ title, assigneeUserId? }` (회장·담당 부서 부장). 맡길 사람은 담당 부서 사람만 |
| `PATCH /api/task-items/{id}` | `title`·`assigneeUserId`(회장·부장)·`done`(맡은 사람도) 변경. `If-Match` 필요 |
| `DELETE /api/task-items/{id}` | 체크리스트 지우기(회장·담당 부서 부장) → 204 |
| `GET /api/orgs/{id}/members` | 구성원 목록(권한 순, 직책 이름 포함) |
| `POST /api/orgs/{id}/members` | 구성원 초대(회장) `{ loginId, name, initialPassword, positionId, departmentId }`. 부장급 직책은 부서 필수. 이미 있는 아이디면 소속만 붙임 |
| `PATCH /api/memberships/{id}` | 직책·부서 변경(회장) `{ positionId, departmentId }`. `If-Match` 필요 |
| `GET /api/orgs/{id}/departments` | 부서 설정(이름·역할 키워드·인원·끝나지 않은 업무 수, 승인 전 후보 포함) |
| `POST /api/orgs/{id}/departments` | 부서 만들기 `{ name, roleKeywords? }`(회장). 없앤 부서와 이름이 같으면 되살림 |
| `PATCH /api/departments/{id}` | 부서 이름·역할 키워드 바꾸기(회장, `If-Match`) |
| `DELETE /api/departments/{id}` | 부서 없애기(숨김, 회장). 구성원·끝나지 않은 업무·받은 요청이 있거나 마지막 부서면 409 |
| `GET /api/orgs/{id}/positions` | 직책 목록(이름·권한 수준·인원) |
| `POST /api/orgs/{id}/positions` | 직책 만들기 `{ name, level: EDITOR·VIEWER }`(회장) |
| `PATCH /api/positions/{id}` | 직책 이름·권한 수준 바꾸기(회장, `If-Match`). 수준을 바꾸면 그 직책 구성원의 권한도 바뀜 |
| `DELETE /api/positions/{id}` | 직책 지우기(회장). 그 직책을 가진 구성원이 있거나 마지막 직책이면 409 |
| `DELETE /api/memberships/{id}` | 구성원 내보내기(회장) → 204. 회장 소속과 자기 자신은 안 됨 |
| `GET /api/admin/groups` | 모든 그룹과 회장 목록(서비스 관리자) |
| `POST /api/admin/groups` | 그룹 만들기 `{ name, joinCode, type, parentId?, term }`. 기획부·홍보부·재정부를 같이 만듦. 쓰는 코드면 409 |
| `PATCH /api/admin/groups/{id}` | 가입 코드 정하기·바꾸기 `{ joinCode }` |
| `POST /api/admin/groups/{id}/owners` | 회장 계정 주기 `{ loginId, name, initialPassword }` |
| `DELETE /api/admin/groups/{id}/owners/{userId}` | 회장 자격 거두기 |
| `GET /api/tasks/{id}/rule-check` | 업무 카드 하나의 규정 확인 표시(돈과 관련된 카드만, 아니면 204) |
| `POST /api/orgs/{id}/rule-documents` | (화면 없음·운영/평가용) 그룹 전용 규정 올리기(multipart: `title`, `issuer`, `appliesTo?`, `effectiveDate?`, `docVersion?`, `notes?`, `bodyStartLine?`, `file` 또는 `text`)(회장) → 201, 조항으로 나뉜 `DRAFT` |
| `GET /api/orgs/{id}/rule-documents` | 규정 목록과 검수 진행 상황(구성원) |
| `GET /api/rule-documents/{id}` | 규정과 조항 전체 |
| `PATCH /api/rule-documents/{id}` | 발행 주체·시행일·버전·메모 고치기(회장, `If-Match`, 확정 전만) |
| `POST /api/rule-documents/{id}/clauses` | 조항 직접 추가 `{ afterClauseId?, kind?, label, title?, text }`(회장, 확정 전만) |
| `POST /api/rule-documents/{id}/clauses/review` | 고른 조항을 검수 완료로 `{ clauseIds }`(회장) |
| `PATCH /api/clauses/{id}` | 조항 고치기·검수 표시 `{ label?, title?, text?, reviewed? }`(회장, `If-Match`). 글을 고치면 검수 표시가 풀림 |
| `DELETE /api/clauses/{id}` | 조항 지우기(회장, 확정 전만) → 204 |
| `POST /api/rule-documents/{id}/confirm` | 확정(회장, `If-Match`). 시행일·버전이 없거나 검수 안 한 조항이 있으면 거절 |
| `POST /api/rule-documents/{id}/retire` | 확정한 규정 폐기(회장, `If-Match`) |
| `DELETE /api/rule-documents/{id}` | 확정 전 규정 지우기(회장) → 204 |
| `POST /api/expense-reviews` | 지출 절차 확인 요청 `{ orgId, decisionId?, item?, amount?, fundSource?, plannedDate?, note?, keywords? }`(회장·부장급) → 201, 후보 조항·알림이 든 결과 |
| `GET /api/orgs/{id}/expense-reviews` | 지출 검토 목록(구성원) |
| `GET /api/expense-reviews/{id}` | 지출 검토 결과: 후보 조항 원문(DB 값 그대로), 알림, 검증된 AI 안내, 체크리스트, 최근 작업, 고정 안내문 |
| `POST /api/expense-reviews/{id}/guidance` | AI 안내 다시 받기(회장·부장급) → 202 + 작업. 대기·진행 중이면 그 작업을 돌려줌. 검토 완료·후보 없음은 409 |
| `POST /api/expense-reviews/{id}/review` | 검토 완료(회장, `If-Match`). AI 안내가 끝난 뒤 한 번만 |
| `PATCH /api/expense-review-items/{id}` | 체크리스트 체크/해제 `{ checked }`(회장·부장급, `If-Match`). 검토 완료 뒤에는 409 |
| `GET /actuator/health` | 상태 확인. 인증 없이 열려 있습니다 |

- `/api/**`는 `Authorization: Bearer <토큰>`이 필요합니다.
- 오류 본문은 항상 `{ code, message, requestId }`입니다. 모든 응답에 `X-Request-Id` 헤더가 붙고, 서버 로그의 `rid:` 값과 같습니다.
- 권한은 토큰이 아니라 요청마다 DB의 활성 소속(Membership)으로 확인합니다. 상위 그룹에 소속돼 있어도 다른 그룹 데이터는 직접 볼 수 없습니다.
- `If-Match`가 없으면 400(`IF_MATCH_REQUIRED`), 화면이 본 버전과 다르면 409(`VERSION_CONFLICT`), 지금 상태에서 할 수 없는 변경이면 409(`INVALID_TRANSITION`).
- 로그에는 요청 ID·작업 ID(`job:`)·처리 시간·오류 종류만 남고 회의 원문은 남지 않습니다.

## 폴더 구조

```
src/main/java/kr/dku/councilflow/
  auth/      로그인, JWT, 보안 설정, 그룹 단위 인가(OrgAccessGuard)
  org/       그룹·부서
  user/      계정·소속·구성원 관리·비밀번호 변경
  admin/     서비스 관리자: 그룹 만들기, 회장 계정 주기
  meeting/   회의, 발언(Segment), 회의록 나누기
  job/       DB 작업 큐와 Worker(가져가기·임대·재시도)
  ai/        CLOVA Studio·Speech 클라이언트(실제/가짜, AiConfig 에서 고름)
  storage/   녹음 저장소(로컬: 서버 폴더에 저장하는 FakeObjectStorage / 운영: NcpObjectStorage)
  analysis/  회의 분석: 프롬프트, 형식 검증, 구간 합치기, 의미 검증·저장
  decision/  결정·업무, 승인, 업무보드
  delivery/  그룹 간 업무 요청, 수신함, 결과 제출·검토, 집계
  rule/      규정, "제n조" 분리(RuleTextSplitter), 조항 검수·확정, 학교 규정 시드(RuleSeedLoader)
  expense/   지출 절차 확인 요청, 후보 조항 찾기(SearchTerms), AI 안내 프롬프트·형식 검증·의미 검증, 체크리스트
             회의 업무 카드의 규정 확인(RuleCheckPlanner: 대상 고르기, TaskRuleChecks: 배지)
  audit/     변경 기록(AuditLog)
  common/    공통 엔티티, 오류 응답, 요청 ID 필터
  seed/      local 시드 데이터
src/main/resources/ai/             AI 지시문과 응답 스키마
src/main/resources/rules/          학교 규정 시드(검수한 원문, json)
src/main/resources/db/migration/   Flyway 마이그레이션 (스키마는 여기서만 바꿉니다)
frontend/                          React + Vite + TypeScript
scripts/eval/                      AI 결과 평가 스크립트·샘플·실행 기록
```
# student-council
