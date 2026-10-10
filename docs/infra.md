# NCP 자원 관리표 — 강대운(팀원 3)

## 현재 상태 · 2026-10-10

VPC·11개 서브넷·존별 NAT·Bastion에 더해 G3 Private App 이미지 준비 서버 1대, Worker 1대, 두 존 Cloud DB for MySQL HA, 두 존 Application LB, 비공개 Object Storage 버킷을 생성했다. 공개 LB 주소에서 로그인·실제 CLOVA 분석·근거 검토·승인·업무보드·그룹 요청 수락·제출·완료 집계를 검증했다. ASG는 KR-1·KR-2 각 최소 1대, 최대 2대이며 초기 App은 총 2대다.

JDK 21 전체 앱 빌드에서 테스트 86개가 통과하고 63MB 실행 jar를 생성했다. App·Worker에 같은 jar와 JRE 21을 배포했으며 SSH는 Bastion을 통한 공개키 접속만 허용한다. DB TLS 필수 연결이 실제 DB와 맞지 않아 최초 기동에 실패했다. 사용자가 가상 데이터 데모에 한해 비암호화 연결을 요청한 뒤 sslMode=PREFERRED로 변경했다. Private DB·ACG 제한은 유지하며 App 건강 응답 HTTP 200, Worker 실제 CLOVA 분석 SUCCEEDED를 확인했다. 실데이터 운영 전에 DB 통신 암호화 경로를 확보해야 한다.

Cloud DB는 KR-1 Master와 KR-2 Standby가 모두 실행 중이다. 생성 apply는 provider 4.0.5의 G3 응답 불일치로 실패했다. DB를 실제 API와 대조한 뒤 taint만 제거하여 불필요한 재생성을 방지했고, 실제 상태에 맞춘 plan은 변경 0개다. 요청했던 저장 암호화는 반영되지 않았으며 공식 생성 API도 해당 옵션을 ‘추후 적용’으로 표시한다. DB 저장 암호화·전송 TLS가 적용됐다고 주장하지 않는다. 데모는 가상 데이터만 사용한다.

DB 관리 ACG의 두 기본 HA 규칙을 보존하면서 App·Worker ACG의 TCP 3306만 추가했다. DB의 인터넷 기본 경로는 없다. 비인증 Object Storage 목록 요청은 403으로 거부됐고, Cloud Insight에서 실제 App CPU 지표를 조회했다. Terraform 모의 테스트는 11개 통과했다. Cloud Insight 콘솔 CPU 경보 규칙 ID는 11904이며 3분간 CPU 80% 초과 조건이다(외부 통보 없음). 두 존 ASG 109051·109052와 CPU 증감 이벤트 11905~11908을 실제 생성하고 정책 연결을 재조회했다. CPU 기반 증설이 실제로 발동하는 부하 실험은 후속 검증이다.

사용자 요청으로 이번 배포는 LB 기본 주소의 HTTP를 사용한다. 도메인·HTTPS·리다이렉트는 후속 작업이다. 현재 LB 주소: `http://council-lb-146192510-bdc992b32e90.kr.lb.naverncp.com` (가상 데이터 데모 접속 가능).

## 자원 목록

VPC `council-vpc` · ID `150172` · `10.30.0.0/16` · 리전 `KR`

| 서브넷 | 존 | CIDR | 유형 / 용도 | 자원 ID |
| --- | --- | --- | --- | --- |
| council-lb-a | KR-1 | 10.30.0.0/24 | PUBLIC / LOADB | 329136 |
| council-bastion-a | KR-1 | 10.30.1.0/24 | PUBLIC / GEN | 329131 |
| council-nat-a | KR-1 | 10.30.2.0/24 | PUBLIC / NATGW | 329137 |
| council-app-a | KR-1 | 10.30.10.0/24 | PRIVATE / GEN | 329130 |
| council-worker-a | KR-1 | 10.30.20.0/24 | PRIVATE / GEN | 329135 |
| council-db-a | KR-1 | 10.30.30.0/24 | PRIVATE / GEN | 329134 |
| council-lb-b | KR-2 | 10.30.100.0/24 | PUBLIC / LOADB | 329129 |
| council-nat-b | KR-2 | 10.30.102.0/24 | PUBLIC / NATGW | 329133 |
| council-app-b | KR-2 | 10.30.110.0/24 | PRIVATE / GEN | 329132 |
| council-worker-b | KR-2 | 10.30.120.0/24 | PRIVATE / GEN | 329128 |
| council-db-b | KR-2 | 10.30.130.0/24 | PRIVATE / GEN | 329127 |

| 자원 | ID | 용도 |
| --- | --- | --- |
| council-bastion ACG | 405629 | 관리 공인 IPv4 /32 → SSH 22 |
| council-app ACG | 405630 | 두 LB 서브넷 → 8080, Bastion ACG → SSH 22 |
| council-worker ACG | 405628 | Bastion ACG → SSH 22; HTTP 인바운드 없음 |
| council-compute-a Route Table | 317598 | KR-1 App·Worker 연결 |
| council-compute-b Route Table | 317600 | KR-2 App·Worker 연결 |
| council-db-private Route Table | 317599 | 두 DB 서브넷 연결; 인터넷 기본 경로 없음 |
| VPC 기본 Public Route Table | 317597 | Public 서브넷 기본 라우팅 |
| VPC 기본 Private Route Table | 317596 | VPC가 자동 생성; App·Worker·DB는 별도 테이블 사용 |
| VPC 기본 NACL | 202871 | 현재 기본 NACL 사용; 세부 제한은 미구현 |

| 관리 단계 자원 | 존 | 자원 ID / 주소 | 상태 |
| --- | --- | --- | --- |
| council-nat-a | KR-1 | 146191714 / 49.50.138.129 | Running |
| council-nat-b | KR-2 | 146191715 / 101.79.29.92 | Running |
| council-bastion-nic | KR-1 | 6068569 / 10.30.1.10 | Bastion에 연결 완료 |
| council-bastion-init | KR-1 | 1314010 | 생성 완료 |
| council-infra-key | KR | council-infra-key | 생성 완료, state 비공개 |
| council-bastion-a 서버 | KR-1 | 146191874 / 10.30.1.10 | Running, c2-g3·Rocky 8.10 |
| Bastion 공인 IP | KR-1 | 146191952 / 211.233.214.246 | 연결 완료, 관리 IP /32만 SSH |

관리 공인 IP의 정확한 값은 Git에서 제외된 로컬 `infra/network/terraform.tfvars`에만 기록한다. 관리 위치의 공인 IP가 바뀌면 이 값을 수정한 뒤 plan을 검토해야 한다. Bastion SSH 사용자명은 counciladmin이며 관리 IP /32에서 공개키 로그인에 성공했다. SSH 개인키는 저장소 밖 outputs/ncp-setup/ssh/council_ed25519에 권한 600으로 보관한다.

App·Worker의 아웃바운드는 DB 대역 TCP 3306, HTTP/HTTPS 80/443, DNS TCP/UDP 53, NTP UDP 123을 허용한다. 존별 NAT와 compute 인터넷 기본 경로는 생성했다. Private 서버에서 NAT를 통한 OS 패키지 다운로드에 성공했다. 실제 CLOVA 분석은 가상 회의 1건에서 성공했다(작업 1회 시도, 약 9초).

## 서비스 배포 단계 자원

| 자원 | 존 | ID / 내부 주소 | 상태 |
| --- | --- | --- | --- |
| App 이미지 준비 서버 | KR-1 | 146192512 / 10.30.10.10 | 중지됨; 이미지 제작 완료, 부팅 디스크는 과금 유지 |
| Worker 서버 | KR-1 | 146192511 / 10.30.20.10 | 실행 중, 실제 CLOVA 분석 성공 |
| Cloud DB HA | KR-1·KR-2 | 146192517 / db-4bde4l.vpc-cdb.ntruss.com | Master·Standby 실행 중 |
| DB 관리 ACG | KR | 405632 | 기본 HA 2규칙 + App·Worker 3306 |
| Application LB | KR-1·KR-2 | 146192510 | HTTP 80, 실행 중 |
| App Target Group | KR | 6406188 | /actuator/health; 정상 운영 App 2대 연결 |
| Object Storage | KR | council-recordings-150172 | 비공개, 미인증 목록403 |

App·Worker 모두 s2-g3 (2 vCPU·8GB), Rocky 8.10, CB1 10GB다. 운영 설정 파일은 root 0600이며 CLOVA 키는 Worker에만 전달했다. 시드 비밀번호와 공유 JWT는 저장소 밖에서 생성했고 화면이나 로그에 출력하지 않았다. App 이미지에는 이후 DB/JWT 설정이 포함되므로 이미지도 비공개로 관리해야 한다.

## 비용·수명 관리

이번에 생성한 VPC·Subnet·Route Table·NACL·ACG는 [NCP 공식 요금표](https://www.ncloud.com/api-cms/service-product/static/vpc)의 무료 항목이다(2026-10-10 확인). 이번 관리 단계에서는 유료 NAT 2개가 생성됐다. NAT 고정 단가 각 56원/시간 기준, 두 개를 7일 유지하면 부가세 포함 20,697.60원이며 처리 트래픽 비용은 별도다. Bastion은 G3 CPU/메모리 92원/시간 + CB1 10GB 1.6원/시간 + 공인 IP 5.6원/시간이며, NAT 두 개를 합친 7일 고정 예상은 부가세 포함 39,029.76원이다. 추가 트래픽 비용은 별도다. 사양별 견적은 [사양·비용 계획](infra-capacity.md)을 참고한다. 기존 AI 서비스 사용료는 별개다.

삭제 예정일은 발표 후 팀과 확정한다. 2026-10-16 마감 이후 유지/삭제 결정을 기록하고, 삭제 시에는 destroy plan과 사용 중인 자원을 먼저 확인한다. 자동 삭제는 설정하지 않았다.

## 상태 파일·키 관리

- Terraform 실행 담당자는 강대운 1명으로 시작한다. 현재 backend는 로컬이며 다른 팀원의 별도 apply는 금지한다.
- `.env.ncp`, `terraform.tfvars`, `terraform.tfstate`, `network.tfplan`은 Git에서 제외하고 파일 권한 600으로 보관한다.
- 상태 파일은 작업 폴더 밖의 보호된 `outputs/ncp-setup/state-backups/`에도 복사했다. 동일 컴퓨터의 로컬 백업이며 원격·재해 복구 백업은 아직 아니다.
- `.env.ncp`는 설정 파일이며 키를 터미널 명령·로그·PR에 붙여넣지 않는다. 실행할 때만 provider 환경변수로 전달한다.
- 초기 네트워크 실행에서 API 인증키 값이 state·plan·로그에 노출되지 않았음을 확인했다. 관리 단계의 NCP Login Key는 SSH 개인키를 state에 저장하므로 state·plan은 전체 내용을 공유하지 않는다.

## 검증 근거

| 확인 | 실제 결과 |
| --- | --- |
| NCP 인증·기존 목록 조회 | 성공; 생성 전 VPC·서브넷 0개 |
| 한국 리전 존 조회 | KR-1·KR-2 확인 |
| 최초 plan | 27개 추가, 수정·삭제 0개 |
| apply | 27개 추가, 수정·삭제 0개; exit 0 |
| NCP API 재조회 | VPC 150172, 서브넷 11개의 ID·CIDR·존·유형 일치 |
| 적용 후 plan | 변경 0개; exit 0 |
| 키 파일·state·plan 권한 | 600 |

| 관리 추가 plan | 최초 9개 생성 / 한도 수정 후 남은 2개 생성, 기존 자원 변경·삭제 0 |
| 관리 apply 최종 | G3 서버·공인 IP 생성 완료; 전체 state 36개 |
| 관리 적용 후 plan | 변경 0개 |
| NAT·라우팅 API 재조회 | NAT 2개 Running, compute 경로 동일 존 NAT, DB 인터넷 기본 경로 없음 |
| 실제 SSH | counciladmin 공개키 접속·sudo 성공, 키 디렉터리 700·authorized_keys 600 |
| 실제 sshd 설정 | AuthenticationMethods publickey, PermitRootLogin no, PasswordAuthentication no, AllowUsers counciladmin |
| Terraform mock 테스트 | 6개 통과; fmt·validate·스크립트 문법 검사 통과 |

실행 로그·검토한 plan 요약은 저장소 밖의 로컬 `outputs/ncp-setup/`에 보관한다. 관리 단계 실패·완료 state 모두 권한 600으로 로컬 백업했다. 실제 CLOVA 분석·승인·요청 수락·결과 제출·완료 집계를 SSH 터널로 확인했다. 근거 원문 일치, 미인증 401, 다른 그룹 회의 403, 오래된 버전 409를 확인했다. 공개 LB 성능·부하 결과는 아직 없다. Private 서버의 NAT 패키지 다운로드와 Insight CPU 수집은 확인했다.

## 공개 서비스 검증과 후속 작업

공개 LB에서 가상 회의 작업 2번이 실제 CLOVA로 SUCCEEDED했고, 제안 2건의 근거 원문 일치와 승인·그룹 요청 완료를 확인했다. 미인증 401, 다른 그룹 회의 접근 403, 오래된 버전 수정 409를 확인했다. Private DB 주소 3306은 외부 Mac 연결에서 접근할 수 없었다. 이는 현재 구성 확인이며 전체 보안 취약점 진단은 아니다.

두 존 App과 Worker의 실제 CPU 시계열을 조회했다. 운영 오류 로그는 요청·작업 ID 및 처리 시간 중심으로 기록하며 API 키·회의 원문을 남기지 않는다. Worker 장애·DB failover·존 전체 장애·부하 한계는 아직 시험하지 않았다. 녹음 전사·지출 규정 안내는 팀원 개발 과제다.

1. 조회 트래픽과 동시 회의 분석을 구분해 부하를 측정하고 총 App 2~4대 상한·CPU 정책을 재검토한다.
2. 실데이터 사용 전에 HTTPS, DB 통신 암호화, 저장 암호화 대안을 완료한다.
3. Worker 임대 갱신 PR #2 병합 후 재배포·긴 작업 및 재시도 검증을 한다. 현재 Worker는 1대이며 임대 10분, 갱신 변경은 미배포다.
4. 원격 Terraform 상태·잠금·재해 복구 백업을 마련한 뒤 인프라 공동 작업을 허용한다.

## 종료 후 청구 자원 점검

네트워크만 지웠다고 비용이 모두 종료되는 것은 아니다. 실제 생성한 자원을 기준으로 아래를 확인한다.

- Server·Auto Scaling 그룹·서버 이미지·스냅샷·추가 디스크·공인 IP
- Load Balancer·NAT Gateway·Cloud DB 및 백업 보관
- Object Storage 데이터·미완료 멀티파트 업로드
- 외부 AI 서비스 플랜·요청 사용량·모니터링/로그 보관 비용
- 최종 Terraform state와 NCP 콘솔 목록 대조, 삭제 완료 및 잔여 청구 확인

## 배포 이미지 관리

부팅 스냅샷 `146195761`, App 이미지 `146195776`, G3 실행 템플릿 `39683` (`council-app-20261010`, s2-g3)을 공식 API로 생성했다. Terraform provider 4.0.5가 지원하지 않는 필드 때문에 별도 관리하며 종료 점검 대상이다. 이미지에는 root 0600 DB/JWT 설정이 있고 CLOVA 키·시드 비밀번호는 없다. 다른 계정으로 공유하지 않는다.

## Auto Scaling·Cloud Insight 연결 기록

| 존 | ASG | 최소/최대/초기 | 증설/축소 정책 | CPU 이벤트 |
| --- | --- | --- | --- | --- |
| KR-1 | 109051 | 1 / 2 / 1 | 37871 / 37868 | 11905 / 11906 |
| KR-2 | 109052 | 1 / 2 / 1 | 37870 / 37869 | 11907 / 11908 |

CPU 평균 80% 초과 3분이면 +1(쿨다운 300초), 30% 미만 10분이면 -1(쿨다운 600초)이다. 초깃값으로, 성능 실험으로 정한 수치는 아니다. ASG 상태 확인 유형은 LOADB이며 초기 유예 180초다. 이미지 준비 서버는 중지하고 실제 ASG App 두 대로 운영한다.

Cloud Insight 이벤트는 Terraform provider 범위 밖에서 공식 API로 생성했다. 재현용 요청은 [asg-cpu-rules.json](../infra/monitoring/asg-cpu-rules.json)에 있다. API `POST /cw_fea/real/cw/api/rule/group/ruleGrp/createDirectly`를 NCP 서명 인증으로 호출하고 반환 rule ID를 기록한다. 기존 ID를 먼저 조회해 중복 생성하지 않는다. ASG 대상 이벤트라도 prodKey는 Server(VPC) 키(현재 460438474722512896)를 쓰고, monitorGroup.type은 ASG다. 정책 연결은 재조회 응답의 asgPolicys로 확인한다. 외부 통보 수신자는 설정하지 않았다. 다른 계정에서는 상품 키·ASG·정책 번호를 다시 조회해 교체한다. 이벤트·임시 모니터/지표 그룹은 Terraform destroy 대상이 아니므로 종료 때 콘솔에서 별도 삭제·잔여 목록을 확인한다.

## App 장애 점검 — 2026-10-10

KR-1 App 146195839의 서비스를 중지하고 KR-2 App을 유지했다. 2초 간격 인증 조회 196번 중 2번이 실패했고, LB가 장애 App을 제외한 뒤 ASG 대체 서버 146196160(10.30.10.7)이 정상화됐다. 두 대상이 모두 UP이 된 시점은 장애 주입 후 약 409초(6분 49초)다. 관측 중 API 조회 시간도 포함한 한 번의 시험이며 무중단·평균 복구시간·존 전체 장애 대응 결과로 확대하지 않는다. 최종 KR-1 146196160 / KR-2 146195836(10.30.110.6), 두 대상 모두 UP. 원래 장애 서버는 ASG에서 제거됐다.

배포 후 plan에서 축소 정책 두 개가 증설 정책 ID로 잘못 읽힌 것을 발견했다. provider 4.0.5 create 구현이 반환 목록 첫 항목을 선택하는 문제로 실제 정책은 -1로 정상 생성돼 있었다. 보호된 state를 백업하고 기존 정책 109051:37868 / 109052:37869를 import했다. 정책/서버를 재생성하지 않았다. 향후 정책 추가 시 실제 번호·방향을 대조하고 plan 재검증이 필요하다.

import 이후 축소 정책의 cooldown을 provider가 null로 읽었지만 공식 API의 coolDown은 600이었다. 축소 정책에 한해 cooldown drift를 제외하고 생성값 600을 유지했다. 이후 변경 전 두 정책의 실제 coolDown=600을 공식 API로 대조한다. 이 제외는 외부에서 바뀐 쿨다운을 자동 복원하지 못하는 비용이 있다. 정책 ID/방향/min/max는 그대로 Terraform이 관리한다.

최종 검증: 전체 Terraform state 52개, taint 없음, 실제 plan 변경 0개, mock 테스트 11개 통과. 장애 복구 후 공개 로그인·내 계정 조회 200. 비밀 값 변경 파일 검사 0건.

최종 독립 리뷰: 중요한 문제 1건(재배포 시 실행 중 JVM 미갱신)을 enable 후 restart·기동 확인으로 수정했다. 회귀 테스트가 실패 후 통과했으며 CI에도 포함했다.


## Ubuntu 전환 진행 (2026-10-10)

수업 환경과 맞추기 위해 self-managed 서버를 Ubuntu 24.04 LTS로 전환한다. 관리형 MySQL은 기존 서비스와 데이터를 유지한다. 기존 Rocky 서버는 새 관리 경로와 서비스 검증이 끝날 때까지 유지한다.

| 준비 자원 | ID / 주소 | 확인 상태 |
|---|---|---|
| Ubuntu Bastion | 146197393 / 10.30.1.11 / 223.130.143.82 | Ubuntu 24.04.1, root·비밀번호 SSH 차단, 공개키 접속 확인 |
| Ubuntu 이미지 준비 서버 | 146197391 / 10.30.10.11 | Java 21.0.11, 개선 UI jar 설치, DB 포함 health UP; 스냅샷 후 정지 |
| Ubuntu Worker | 146197397 / 10.30.20.11 | Java 21.0.11, jar·설정 staging 준비; 아직 폴링 시작 안 함 |
| Ubuntu App 스냅샷 / 이미지 | 146197564 / 146197588 | 비공개, 부팅 가능, CREAT |
| Ubuntu Launch Configuration | 39685 | s2-g3, init script 1314015; ASG 109051·109052 생성 설정 갱신 완료 |

신규 서버에는 `apt-get install openjdk-21-jre-headless`로 Java 21을 설치한다. 런타임은 배포판별 JDK 디렉터리 대신 `/usr/bin/java`를 사용하며, 설치 스크립트가 Java 21인지 확인한 후 jar·환경설정을 교체한다. service 재시작과 is-active를 확인한다.

검증: 새 UI 포함 JDK21 `./mvnw verify` 86/86, frontend test 3/3, runtime test 3/3, Terraform mock 13/13 통과. 이 단계의 실제 Terraform은 병렬 자원 8개 생성 후 ASG 설정 2개 변경만 수행했으며 기존 자원 삭제는 없었다.

아직 공개 URL은 전환 중이며, 최종 Ubuntu App·Worker 검증과 Rocky 자원 정리는 완료 전 별도 기록한다. 전환 중 서버별 UI 버전이 잠시 다를 수 있으므로 최종적으로 두 존의 jar SHA와 정적 자산 일치를 확인한다. 병렬 서버와 공인 IP는 임시 추가 과금 대상이고, 이전 이미지·LC는 비공개 롤백 자원으로 보존 기간을 정한다.


전환 방식 보완: 혼합 OS 그룹을 단순 축소하면 검증한 새 서버가 종료될 가능성을 배제하지 못해 자동 승인 검토가 거절했다. 기존 그룹의 임의 축소는 실행하지 않았다. 대신 별도의 Ubuntu 전용 ASG(`council-ubuntu-app-asg-a/b`, prefix `cfub-a/b`)를 두 존에 생성해 새 서버를 검증한 다음, 이전 그룹 전체를 퇴역시키는 블루/그린 방식으로 진행한다. 최종 서버 수와 최소·최대(2~4대)는 동일하다. 전환 중 추가 App 2대는 일시적인 과금 대상이다.

Worker 검증 정정: `application-worker.yml`은 HTTP 서버를 띄우지 않는다. 따라서 `/actuator/health` 대신 systemd active, DB 연결 시작 로그, 기존 Worker inactive, 실제 분석 작업 성공으로 확인한다. Ubuntu Worker에서 가상 회의 5번·분석 작업 5번이 실제 CLOVA API로 SUCCEEDED를 반환했다.
