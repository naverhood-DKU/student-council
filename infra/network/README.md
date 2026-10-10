# NCP 네트워크 — 팀원 3

이슈 #3. 한국 리전 VPC, 두 존의 배치 설계. **2026-10-10 네트워크 자원 생성·재검증 완료**이며 Bastion/NAT opt-in 설정을 추가했다. NAT 두 개와 G3 Bastion이 생성됐고 공개키 SSH 검증까지 완료했다. App·LB·Cloud DB·버킷 생성과 runtime 배포를 추가했고 두 존 ASG·CPU 이벤트 연결과 공개 LB 핵심 흐름 검증을 완료했다. Worker 임대 갱신은 별도 PR #2이다.

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
- DB 상품 관리 ACG의 인바운드는 App/Worker만 추가 허용하고, 상품의 기본 운영 규칙은 유지한다.

## 키 없이 검증

```sh
terraform -chdir=infra/network init -backend=false
terraform -chdir=infra/network fmt -check -recursive
terraform -chdir=infra/network validate
terraform -chdir=infra/network test
```

`test`는 mock provider로 계획을 평가하므로 NCP 요청·자원 생성·과금이 없다. 이는 실제 상품 가용성·계정 권한·네트워크 연결을 검증하지 않는다. `.terraform.lock.hcl`은 provider 고정을 위해 커밋한다.

로컬 검증: 2026-10-10, Terraform 1.16.4, provider 4.0.5에서 fmt·validate·mock 계획 테스트 11개 통과. `.github/workflows/infra.yml`도 동일한 검사를 수행하며 NCP 인증키를 요구하지 않는다. 실제 NCP plan·apply도 완료했다. 생성 결과는 27개 추가, 수정·삭제 0개이고 적용 후 plan은 변경 0개다. 자원 ID와 검증 범위는 [자원 관리표](../../docs/infra.md)에 기록했다.

## 이후 실제 변경 작업

1. NCP 계정에서 한국 리전 KR-1·KR-2 상품 가용성·자원 한도와 기존 네트워크 대역을 확인한다.
2. `terraform.tfvars.example`을 로컬 `terraform.tfvars`로 복사하고 관리 공인 IPv4 /32를 입력한다. 예시 IP는 문서용이므로 실제 관리에 쓰지 않는다.
3. 적절한 서브 계정의 Access Key/Secret Key를 로컬 환경변수 `NCLOUD_ACCESS_KEY`, `NCLOUD_SECRET_KEY`로 설정한다. 키는 채팅·GitHub·스크린샷에 남기지 않는다.
4. 개인 local state는 동시 작업에 쓰지 않는다. 팀에서 단일 실행자와 접근 제한된 state 저장·잠금/백업 방법을 정한 뒤 생성한다.
5. 아래 실제 plan으로 대상 자원을 확인하고 가격표/견적으로 비용을 별도 계산한다. `plan`은 가격 계산 도구가 아니다.

```sh
terraform -chdir=infra/network plan -out=network.tfplan
```

NAT 기본 false라도 실제 apply는 네트워크 자원을 만든다. NAT 활성화 시 두 개의 과금 자원이 추가된다. 후속 apply는 변경 plan·비용을 확인한 뒤 진행한다. destroy는 사용 중인 자원과 삭제 범위를 별도로 검토한다. API 키가 들어가는 provider 설정·state·plan 출력은 공유 전에 검토한다.

## Bastion 설정

`enable_management=true`와 한 줄의 `management_ssh_public_key`(ed25519 공개키)를 로컬 tfvars에 설정하면 Bastion NIC·Init Script·서버·공인 IP를 생성한다. 개인키는 tfvars에 넣지 않는다. `counciladmin` 공개키 인증만 허용하며 root·비밀번호 로그인을 차단한다. `ncloud_login_key`의 반환 SSH 개인키가 state에 들어가므로 state와 plan은 비공개로 보관한다. Terraform 1.9 이상이 필요하다.

2026-10-10 실제 추가 apply는 완료됐다. G2 생성 한도 0대 오류 후 콘솔의 G3 한도를 확인하고 Rocky 8.10 KVM image 25495367 / c2-g3로 변경했다. 새 plan으로 서버·공인 IP를 추가해 기존 자원 변경 없이 관리 단계 총 9개가 생성됐고 전체 state는 36개, 적용 후 변경 0개다. 공개키 SSH·sudo·root/비밀번호 로그인 차단과 NAT·라우팅을 검증했다. Private App/Worker NAT 패키지 다운로드·실제 CLOVA 호출도 확인했다.

## 다음 단계

2026-10-10 배포 진행: App 이미지 준비 서버·Worker·두 존 MySQL HA·두 존 LB·비공개 버킷을 생성했다. ASG 단계에서 6개 자원을 추가해 전체 state는 52개다. 전체 모의 테스트 11개, 앱 테스트 86개가 통과했다. 가상 데이터에 한해 사용자가 내부 비암호화 DB 연결을 요청하여 App·Worker를 기동했다. SSH 터널에서 실제 CLOVA 분석·승인·업무보드·그룹 요청 완료와 401/403/409를 확인했다. 공개 LB에서도 실제 분석·승인·그룹 요청 완료와 401/403/409를 확인했다. DB 통신 TLS와 HTTPS는 실제 데이터 사용 전 과제다.

`enable_application=true`는 유료 서비스 자원을 추가하며 `enable_management`, `enable_nat`, 생성한 DB 비밀번호와 고유 버킷 이름이 필요하다. G3 DB 스토리지는 기본 CB2를 사용한다. provider 4.0.5가 G3의 스토리지 응답 필드를 null로 읽으므로 타입을 명시하지 않는다. 공식 API가 ‘추후 적용’으로 표시한 DB 저장 암호화 옵션은 실제 false였으며 적용됐다고 주장하지 않는다.

`enable_autoscaling=true`는 두 존에 각각 min1/max2 App ASG와 증감 정책을 추가한다. provider 4.0.5가 G3 Launch Configuration의 `serverImageNo`/`serverSpecCode`를 지원하지 않으므로 공식 API로 준비한 번호를 `app_launch_configuration_no`에 설정한다. 이 자원과 이미지·스냅샷은 별도 수명 관리가 필요하다. 정책을 정의하는 것과 Cloud Insight CPU 이벤트를 연결하는 것은 별개이며 공식 API로 현재 Ubuntu CPU 이벤트 11911~11914을 연결하고 재조회했다. 재현 요청과 자원 번호는 docs/infra.md에 있다. 부하에 따른 실제 증감은 후속 실험이다.

jar 실행 설정은 `infra/runtime/`에 있다. 환경변수 파일은 root 0600으로 배포하며 App은 `prod`, Worker는 `prod,worker`를 사용한다. CLOVA 키는 Worker에만 두고 같은 JWT를 두 App에 사용한다. 가상 시드는 이미지 준비 서버에서 강한 비밀번호로 한 번만 생성하고, 이미지 제작 전에 시드를 비활성화한다. 현재 Ubuntu Java 실행은 `/usr/bin/java`를 사용하며 설치 전에 Java 21 여부를 확인한다.

네트워크 생성 완료 → Bastion·존별 NAT 생성·SSH 검증 완료 → G3 App/Worker·DB 견적/ASG 방식 확인 → LB·App·DB → 두 ASG 각 최소 1대 → Worker·Object Storage → Cloud Insight·부하/장애 검증. 실제 자원 ID·IP·도메인·삭제 예정일은 생성 후 출력과 관리표에 기록한다.

공식 근거: [Subnet](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/subnet.md), [ACG Rules](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/access_control_group_rule.md), [NAT](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/nat_gateway.md), [Routes](https://github.com/NaverCloudPlatform/terraform-provider-ncloud/blob/main/docs/resources/route.md).

## 현재 Ubuntu 운영 구성

최신 자원·역할·이름은 [자원 관리표](../../docs/infra.md)의 현재 운영 상태를 기준으로 한다. 위 초기 Rocky 생성 기록은 전환 전 이력이다. 현재 운영에는 `enable_ubuntu_migration=true`, `enable_ubuntu_app_groups=true`, `enable_legacy_compute=false`, `enable_legacy_app_groups=false`를 사용하며 management/application/nat/autoscaling은 활성화한다. 기존 기본값은 이전 사용자의 구성을 보존하므로, 현재 환경을 재구성할 때 이 플래그를 생략하지 않는다. LC는 39685이며 다른 계정에서는 이미지·LC·자원 번호를 새로 준비한다.

App은 웹·API를 함께 제공한다. Worker는 HTTP 서버 없이 AI 작업을 처리한다. ASG 서버 접두사는 `app-kr1`, `app-kr2`이며 초기 서버명은 자원 관리표에 기록했다. 현재 모의 테스트 15개가 통과했고 실제 적용 후 변경 0개를 확인했다. 녹음 전사는 아직 구현하지 않았다.
