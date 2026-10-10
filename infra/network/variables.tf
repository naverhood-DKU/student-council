variable "admin_ipv4_cidr" {
  description = "Bastion에 접속할 관리자의 실제 공인 IPv4 /32. 예시는 문서용이며 실제 접속에 사용하지 않는다."
  type        = string
  validation {
    condition     = can(cidrnetmask(var.admin_ipv4_cidr)) && can(regex("/32$", var.admin_ipv4_cidr))
    error_message = "관리 IP는 IPv4 /32 하나로 지정해야 합니다. 0.0.0.0/0은 허용하지 않습니다."
  }
}

variable "enable_nat" {
  description = "존별 유료 NAT 2개 생성. 실제 비용·plan 확인 후 true로 변경."
  type        = bool
  default     = false
}

variable "allow_bootstrap_http" {
  description = "OS 패키지·모니터링 에이전트 설치에 필요한 외부 HTTP 80 허용. 설치 경로 확인 후 끌 수 있다."
  type        = bool
  default     = true
}
