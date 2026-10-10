# NCP 관리 접속·외부 통신 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Native execution in the current session; no subagent dispatch required.

**Goal:** 기존 두 존 네트워크에 관리 IP 제한 Bastion과 존별 NAT를 연결하고 실제 SSH 접속·라우팅 설정을 검증한다.

**Architecture:** 현재 infra/network 단일 state에 관리 자원을 명시적 opt-in 플래그로 추가한다. Bastion은 공개키 인증 전용 관리 계정을 쓰고 Private App/Worker는 각 존 NAT 기본 경로를 사용한다. DB 인터넷 기본 경로는 추가하지 않는다.

**Tech Stack:** Terraform 1.16.4, ncloud 4.0.5, Rocky Linux 8.10, OpenSSH.

**Spec:** docs/infra-capacity.md, docs/infra.md.

## Global Constraints

- VPC 150172, 10.30.0.0/16, KR-1/KR-2의 기존 자원을 재사용한다. 기존 자원 삭제·교체가 있는 plan은 적용하지 않는다.
- 예산 2,250,000원, 마감 2026-10-16. 이번 Bastion/NAT 7일 고정 예상 40,212.48원; 변동 트래픽 별도.
- 관리 접속은 실제 공인 IPv4 /32와 SSH 공개키 인증만 허용한다.
- enable_management, enable_nat 기본 false; 실제 생성은 로컬 tfvars에서 활성화한다.
- provider 4.0.5·로컬 state 단일 실행자 유지. 키·state·tfvars·plan 커밋 금지.

## Review Focus

- 공개키 누락 상태로 관리 서버 생성을 켜면 검증 실패해야 한다.
- Bastion의 NIC에는 기존 Bastion ACG가 적용되어야 한다.
- 비밀번호·root 로그인 차단 전에 counciladmin 공개키를 설치해야 한다.
- 각 compute route는 동일 존 NAT를 가리키고 DB route에는 인터넷 경로가 없어야 한다.
- init script 실패·SSH 관리 IP 변경은 접속 실패로 드러나며 생성 성공만으로 연결 성공을 주장하지 않는다.

### Task 1: 관리 자원·접속 설정

**Files:** Create infra/network/management.tf, infra/network/templates/bastion-init.sh.tftpl, infra/network/tests/management.tftest.hcl; Modify variables.tf, outputs.tf, terraform.tfvars.example.
**Consumes:** 기존 VPC·bastion_a 서브넷·Bastion ACG; 로컬 SSH 공개키.
**Produces:** enable_management 플래그, Bastion server/NIC/Public IP/login key/init script, management output(IP·사용자·서버 ID).

- [x] mock plan에 비밀번호/root 로그인이 금지되는 init script와 공개키 누락 거부 조건을 추가한다.
- [x] Terraform test에서 관리 자원 미구현 실패를 확인한 뒤 opt-in 자원을 구현한다.
- [x] 로컬 outputs/ncp-setup/ssh/council_ed25519를 생성하고 공개키만 tfvars에 넣는다. 개인키는 권한 600, 디렉터리는 700.
- [x] fmt·validate·전체 Terraform mock test를 실행한다.

```sh
terraform -chdir=infra/network fmt -recursive
terraform -chdir=infra/network validate
terraform -chdir=infra/network test
```

Expected: 네트워크·관리 테스트 전부 pass, mock provider이므로 실제 자원 생성 없음.

### Task 2: 비용·실제 plan 확인과 적용

**Files:** Modify 로컬 infra/network/terraform.tfvars(ignored); 로컬 plan·state·로그(ignored).
**Consumes:** Task 1 구성·기존 API 인증키.
**Produces:** 실행 결과와 실제 자원 ID.

- [x] 로컬 tfvars에 enable_nat=true, enable_management=true와 SSH 공개키를 설정한다.
- [x] 실제 plan은 기존 27개를 유지하고 관리 자원·NAT 경로만 추가하는지 확인한다.
- [x] 추가·비용 목록을 사용자에게 공유한 뒤 기존 자원 생성 요청 범위로 저장 plan을 적용한다.
- [x] plan·apply 로그에서 비밀값이 노출되지 않게 보호하고 state를 권한 600으로 백업한다.

### Task 3: 실제 연결·기록

**Files:** Modify docs/infra.md, infra/network/README.md, docs/infra-capacity.md.
**Consumes:** Task 2 출력·로컬 SSH 개인키.
**Produces:** 직접 접속 확인 및 남은 구축 범위를 명시한 자원 관리표.

- [x] 공개키로 counciladmin SSH 접속 후 hostname·sshd 설정·sudo 동작을 확인한다.
- [x] NCP API 목록과 출력 ID를 대조한다.
- [x] 적용 후 실제 plan은 변경 0개인지 확인한다.
- [x] 실제 IP/ID·비용·생성일·삭제 예정일 상태를 기록하고 App·DB·LB 미생성 상태를 유지한다.

Expected: SSH 공개키 접속 성공, root/password 로그인 금지, 변경 0개. Private App/Worker가 없으므로 그 서버의 NAT 통신 시험은 다음 단계에 남긴다.

## 실행 중 발견한 제약

2026-10-10: 검토에서 `AuthenticationMethods publickey`를 추가해 키 인증 제한을 명시했다. 전체 mock test 6개 통과. 실제 plan 9개 추가·변경/삭제 0개, apply는 7개 완료 후 High-CPU G2 생성 한도 0대로 실패했다(1153027). NAT 2개 Running을 별도 API로 확인했다. Bastion 서버·공인 IP는 미생성, SSH 검증은 미실시이며 Task 2 적용 완료와 Task 3는 남아 있다. 부분 완료 state 백업 완료. 두 존 분산은 같은 App 2대 기준 NAT 한 개의 추가 비용을 감수하는 방향으로 유지한다.

최종 결과: G3 Rocky 8.10 image 25495367 / c2-g3로 조정하고 남은 서버·공인 IP 2개만 추가했다. 실제 SSH 공개키 접속·sudo와 sshd 보안 설정 확인, NCP API NAT/compute route/DB 인터넷 경로 부재 대조, 최종 plan 변경 0개 완료. 전체 state 36개이며 App·DB·LB는 미생성이다. 최종 단계 7일 고정 예상은 39,029.76원(부가세 포함), 데이터 처리·전송 비용 별도다.
