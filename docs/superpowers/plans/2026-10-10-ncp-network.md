# NCP Network Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 두 존의 네트워크를 Terraform으로 검토·재현할 수 있게 만든다. 이번 단계에서는 자원을 생성하지 않는다.

**Architecture:** VPC 10.30.0.0/16, KR-1·KR-2의 11개 서브넷. App/Worker는 Private 라우팅을 존별로 분리하고 DB는 인터넷 경로를 추가하지 않는다. NAT 두 개는 기본 비활성화한다.

**Tech Stack:** Terraform >=1.7,<2 / NaverCloudPlatform ncloud 4.0.5.

**Spec:** `infra/network/README.md`의 배치·접근 규칙.

## Global Constraints

- 실제 apply·클라우드 호출·비용 발생은 이 단계의 검증에 포함하지 않는다.
- 키는 환경변수로 전달한다. state·tfvars·plan·키를 커밋하지 않는다.
- App 인바운드는 두 LB 대역의 8080과 Bastion SSH만, Worker 인바운드는 Bastion SSH만.
- 관리형 DB 생성·DB ACG·LB·ASG·서버·버킷은 다음 단계로 둔다.

## Review Focus

- 관리자가 SSH를 0.0.0.0/0으로 열면 변수 검증으로 거부한다.
- NAT 비활성화는 인터넷 경로도 만들지 않아야 한다.
- NAT 활성화 시 각각 자기 존에 생성되어야 한다.
- Worker HTTP 포트가 열리지 않아야 한다.
- DB 전용 라우팅에는 인터넷 경로를 추가하지 않는다.

## Task — Network module and validation

- [x] `versions.tf`에 provider 버전과 리전 고정, `variables.tf`에 관리 IP·NAT 설정을 정의.
- [x] `main.tf`에 VPC·서브넷·ACG·Private Route Table·조건부 NAT를 구성.
- [x] `outputs.tf`에 자원 관리표용 ID·대역·존 출력, `.gitignore`에 민감 산출물 제외.
- [x] 아래 명령으로 클라우드 호출 없는 검사. fmt·validate·mock plan 테스트 3개 통과.

```sh
terraform -chdir=infra/network fmt -check -recursive
terraform -chdir=infra/network init -backend=false
terraform -chdir=infra/network validate
terraform -chdir=infra/network test
git diff --check
```

- [ ] 검증 결과를 GitHub 이슈 #3에 연결된 PR에 남긴다. 실제 계정 plan과 접근 실험은 검증 완료로 표시하지 않는다.
- [ ] 이후 NCP 상품 가용성·기존 대역 중복·사양과 견적·state 공유 방법을 확인하고 실제 plan을 별도 검토한다.
