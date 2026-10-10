# NCP 네트워크 — 팀원 3

이슈 #3. 한국 리전 VPC, 두 존의 배치 설계. **자원 생성 전 코드 검증 단계**이며 서버·LB·ASG·Cloud DB·버킷은 아직 포함하지 않는다. Worker 임대 갱신은 별도 PR #2이다.

## 배치

VPC `council-vpc`: 10.30.0.0/16.

| 역할 | KR-1 | KR-2 | 유형 / 용도 |
|---|---|---|---|
| LB | 10.30.0.0/24 | 10.30.100.0/24 | PUBLIC / LOADB |
| Bastion | 10.30.1.0/24 | 없음 | PUBLIC / GEN |
| NAT | 10.30.2.0/24 | 10.30.102.0/24 | PUBLIC / NATGW |
| App | 10.30.10.0/24 | 10.30.110.0/24 | PRIVATE / GEN |
| Worker | 10.30.20.0/24 | 10.30.120.0/24 | PRIVATE / GEN |
| DB | 10.30.30.0/24 | 10.30.130.0/24 | PRIVATE / GEN |

App/Worker는 각 존의 Private Route Table에 연결하며, `enable_nat=true`일 때만 인터넷 기본 경로와 NAT 두 개를 생성한다. DB 전용 Route Table에는 인터넷 경로를 추가하지 않는다. 공용 서브넷은 VPC 기본 Public Route Table을 사용한다. 기본 NACL을 사용하며 세부 NACL은 정상 통신 검증 후 별도 작업으로 제한한다.

## 접근 규칙

- Bastion SSH: `admin_ipv4_cidr` 하나의 /32에서만.
- App: LB 두 서브넷에서 8080, Bastion ACG에서 22.
- Worker: Bastion ACG에서 22만. HTTP 서버가 없어 8080을 열지 않는다.
- App/Worker 아웃바운드: DB 두 대역의 3306, 외부 HTTPS 443, DNS TCP/UDP 53, NTP UDP 123. 외부 목적지는 초기 구성에서 포트 기준으로 제한하며 서비스별 목적지 제한은 추후 검토한다.
- `allow_bootstrap_http=true`는 패키지/에이전트 설치용 80도 허용한다. OS 설치 경로를 확인한 뒤 끌 수 있다.
- DB 상품 관리 ACG의 인바운드는 다음 DB 생성 작업에서 App/Worker만 허용하고, 상품의 기본 운영 규칙은 유지한다.

## 키 없이 검증

```sh
terraform -chdir=infra/network init -backend=false
terraform -chdir=infra/network fmt -check -recursive
terraform -chdir=infra/network validate
terraform -chdir=infra/network test
```

`test`는 mock provider로 계획을 평가하므로 NCP 요청·자원 생성·과금이 없다. 이는 실제 상품 가용성·계정 권한·네트워크 연결을 검증하지 않는다. `.terraform.lock.hcl`은 provider 고정을 위해 커밋한다.

로컬 검증: 2026-10-10, Terraform 1.16.4, provider 4.0.5에서 fmt·validate·mock 계획 테스트 3개 통과. `.github/workflows/infra.yml`도 동일한 검사를 수행하며 NCP 인증키를 요구하지 않는다. 실제 NCP plan은 미실행이다.

## 실제 plan 준비 — 아직 실행하지 않음

1. NCP 계정에서 한국 리전 KR-1·KR-2 상품 가용성·자원 한도와 기존 네트워크 대역을 확인한다.
2. `terraform.tfvars.example`을 로컬 `terraform.tfvars`로 복사하고 관리 공인 IPv4 /32를 입력한다. 예시 IP는 문서용이므로 실제 관리에 쓰지 않는다.
3. 적절한 서브 계정의 Access Key/Secret Key를 로컬 환경변수 `NCLOUD_ACCESS_KEY`, `NCLOUD_SECRET_KEY`로 설정한다. 키는 채팅·GitHub·스크린샷에 남기지 않는다.
4. 개인 local state는 동시 작업에 쓰지 않는다. 팀에서 단일 실행자와 접근 제한된 state 저장·잠금/백업 방법을 정한 뒤 생성한다.
5. 아래 실제 plan으로 대상 자원을 확인하고 가격표/견적으로 비용을 별도 계산한다. `plan`은 가격 계산 도구가 아니다.

```sh
terraform -chdir=infra/network plan -out=network.tfplan
```

NAT 기본 false라도 실제 apply는 네트워크 자원을 만든다. NAT 활성화 시 두 개의 과금 자원이 추가된다. 이 단계에서 apply·destroy는 실행하지 않는다. API 키가 들어가는 provider 설정·state·plan 출력은 공유 전에 검토한다.

## 다음 단계

네트워크 실제 plan·견적 확인 → 적용 → LB·App·DB → 두 ASG 각 최소 1대 → Worker·Object Storage → Cloud Insight·부하/장애 검증. 실제 자원 ID·IP·도메인·삭제 예정일은 생성 후 출력과 관리표에 기록한다.

공식 근거: [Subnet](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/subnet.md), [ACG Rules](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/access_control_group_rule.md), [NAT](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/nat_gateway.md), [Routes](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/route.md).
