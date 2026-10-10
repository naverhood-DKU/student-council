# NCP 서비스 배포 실행 계획

**목표:** 실제 NCP 주소에서 현재 회의 분석·승인·업무 요청 서비스에 로그인하고 핵심 흐름을 끝까지 사용할 수 있게 한다.

**기준:** docs/infra.md, docs/infra-capacity.md, CLAUDE.md. 예산 2,250,000원, 마감 2026-10-16. 기존 VPC·NAT·Bastion을 유지한다. App 초기 2대(두 존), Worker 1대, Cloud DB HA 두 존, HTTP LB (사용자 요청으로 HTTPS 후속 적용), 비공개 Object Storage, Auto Scaling을 연결한다. 현재 구현되지 않은 녹음·지출 기능을 구현된 것처럼 표시하지 않는다.

## Task 1 — 코드와 환경 재검증
- [x] origin/main·배포 코드와 비밀 변수 위치 확인
- [x] JDK21 ./mvnw verify로 프론트+백엔드 테스트·jar 빌드
- [x] 운영용 DB 비밀번호·JWT·시드 비밀번호를 보호된 로컬 파일에 생성

## Task 2 — DB·LB·Worker·App 이미지 준비
- [x] G3 사양·비용·상품 호환성 확인, 기존 자원 변경 없는 실제 plan 검토
- [x] mock test로 Private 서버·DB HA·LB health path 검증
- [x] Cloud DB(8.0.45·HA·KR-1/KR-2), LB, 비공개 버킷, App 템플릿·Worker 생성
- [x] DB ACG는 App·Worker 3306만 추가하며 기본 운영 규칙 유지
- [x] JDK21·jar·prod 환경변수·systemd 배포, 강한 비밀번호로 가상 시드 1회 생성

## Task 3 — 두 존 App·Auto Scaling·공개 접속
- [x] 준비한 App 이미지와 공식 G3 API 실행 템플릿 연결
- [x] 두 ASG 각 min1/max2, LB 연결과 CPU 증설 정책
- [x] LB 기본 HTTP 주소 접속 검증; SSL 인증서·HTTPS·리다이렉트는 도메인 확보 후 별도 작업
- [x] 새로운 서버도 자동으로 jar·환경설정으로 실행되는지 확인

## Task 4 — 배포 완료 검증·기록
- [x] 외부 주소에서 로그인→회의 분석→근거 검토→승인→업무보드 및 그룹 요청 흐름 검증
- [x] 권한 없는 API 401/403, DB·Worker 외부 노출 없음, 버킷 비공개 확인
- [x] App 한 대 장애 시 남은 App 처리, Worker 실제 CLOVA 호출, DB 공유 상태 확인
- [x] Cloud Insight 지표/로그 확인, 자원 ID·IP·사양·비용·접속 방법 문서 갱신
- [x] 전체 변경 최종 독립 리뷰·적절한 테스트·실제 plan 안정성 확인

## 판단 기록
- Ruling: G2 서버 한도 모두 0대이므로 G3 사용 — 계정에서 생성 가능한 상품이며 목표 CPU/메모리 유지 — provider가 지원하지 않는 G3 실행 템플릿은 공식 API와 명시적 관리 기록으로 보완한다.
- Ruling: 처음 App 서버는 이미지 제작·배포 검증용이다. ASG 서버 두 대가 정상화되면 이미지 제작 서버는 중지해 초기 운영 App 2대 원칙을 유지한다. 원본 DB·기존 네트워크 삭제는 하지 않는다.
- Ruling: 기존 prod 설정과 실제 CLOVA 키 사용, 외부 장애 대안은 분리한다. fake-ai만 배포해 실제 분석이 완료된 것처럼 주장하지 않는다.
