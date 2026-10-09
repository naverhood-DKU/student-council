# 학생자치 결정 추적 서비스 (council-flow, 임시 이름)

학생자치 회의에서 확정한 결정을 부서와 관련 학생회에 전달하고, 담당·기한·승인·이행 결과를 연결해 추적하는 웹 서비스입니다.
설계 원칙과 도메인 모델은 [CLAUDE.md](CLAUDE.md)에 있습니다.

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
| `fake-ai` | CLOVA를 부르지 않고 고정 응답을 씁니다(부하 실험·데모 예비용). 예: `local,fake-ai` |

`local` 프로필은 편의상 App 하나가 분석 작업까지 처리합니다(`app.worker.enabled=true`). 따로 Worker를 띄울 필요가 없습니다.

## 환경변수

`.env.example`을 `.env`로 복사해서 씁니다. `.env`는 커밋하지 않습니다.
docker compose와 `local` 프로필 앱이 `.env`를 읽습니다(셸 환경변수가 있으면 그쪽이 먼저). 비워 둘 값은 `KEY=`로 두지 말고 줄을 지우거나 `#`으로 막습니다. 빈 값이 기본값을 덮어씁니다.

| 이름 | 필요한 곳 | 설명 |
|---|---|---|
| `DB_URL` | prod 필수, local 선택 | 예: `jdbc:mysql://localhost:3306/councilflow` |
| `DB_USERNAME`, `DB_PASSWORD` | prod 필수, local 선택 | DB 계정 |
| `JWT_SECRET` | prod 필수, local 선택 | 32바이트 이상. 없으면 앱이 뜨지 않습니다. 예: `openssl rand -base64 48` |
| `CLOVA_STUDIO_API_KEY` | 실제 분석에 필요 | CLOVA Studio API 키. 서버(Worker)만 읽고 브라우저로 보내지 않습니다. 없으면 분석이 `AI_KEY_MISSING`으로 실패합니다 |
| `SEED_PASSWORD` | local 선택 | 시드 계정 비밀번호 |
| `MYSQL_PORT` | docker compose 선택 | 로컬 MySQL을 띄울 호스트 포트(기본 3306) |

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
   - 회의에서 말한 담당이 등록된 부서 이름과 다르면 담당은 비워 두고 "추천(확정 아님)"만 보여 줍니다.
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
| `GET /api/jobs/{id}` | 작업 상태(`QUEUED`/`RUNNING`/`SUCCEEDED`/`FAILED`/`FAILED_UNKNOWN`)와 오류 종류 |
| `GET /api/meetings/{id}/proposals` | 결정·업무 후보 + 후보마다 근거 발언 원문 + 회의록 |
| `PATCH /api/decisions/{id}` | 승인 전 요약·안건 수정. `If-Match: 버전` 필요 |
| `POST /api/decisions/{id}/approve` | 승인(회장). `If-Match: 버전` 필요 |
| `PATCH /api/tasks/{id}` | 제목·담당(`departmentId`)·기한(`dueDate`)·상태(`status`, 취소는 `CANCELLED`) 변경. `If-Match: 버전` 필요 |
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
  ai/        CLOVA Studio 클라이언트(실제/가짜)
  analysis/  회의 분석: 프롬프트, 형식 검증, 구간 합치기, 의미 검증·저장
  decision/  결정·업무, 승인, 업무보드
  delivery/  그룹 간 업무 요청, 수신함, 결과 제출·검토, 집계
  audit/     변경 기록(AuditLog)
  common/    공통 엔티티, 오류 응답, 요청 ID 필터
  seed/      local 시드 데이터
src/main/resources/ai/             AI 지시문과 응답 스키마
src/main/resources/db/migration/   Flyway 마이그레이션 (스키마는 여기서만 바꿉니다)
frontend/                          React + Vite + TypeScript
scripts/eval/                      AI 결과 평가 스크립트·샘플·실행 기록
```
# student-council
