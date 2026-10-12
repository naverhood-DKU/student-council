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

variable "enable_management" {
  description = "유료 Bastion 관리 서버·공인 IP 생성. plan·비용 확인 후 활성화."
  type        = bool
  default     = false
}

variable "management_ssh_public_key" {
  description = "Bastion counciladmin 계정에 설치할 로컬 Ed25519 SSH 공개키. 개인키를 넣지 않는다."
  type        = string
  default     = ""
  validation {
    condition     = !var.enable_management || can(regex("^ssh-ed25519 [A-Za-z0-9+/]+={0,2}( [^\r\n]+)?$", var.management_ssh_public_key))
    error_message = "관리 서버를 켤 때는 한 줄의 Ed25519 SSH 공개키가 필요합니다."
  }
}

variable "bastion_image_number" {
  description = "NCP API로 확인한 Rocky Linux 8.10 KVM(G3) 이미지 번호."
  type        = string
  default     = "25495367"
}

variable "enable_application" {
  description = "DB HA, App image builder, Worker, LB and private recording bucket. Requires management and both NAT gateways."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_application || (var.enable_management && var.enable_nat)
    error_message = "Application bootstrap requires Bastion management and NAT for private servers."
  }
}

variable "database_password" {
  description = "Generated database password; never commit tfvars/state or expose this output."
  type        = string
  sensitive   = true
  default     = ""
  validation {
    condition     = !var.enable_application || (length(var.database_password) >= 12 && length(var.database_password) <= 20 && can(regex("[A-Za-z]", var.database_password)) && can(regex("[0-9]", var.database_password)) && can(regex("[!@#%^*()_=;:,.?-]", var.database_password)))
    error_message = "Provide a 12–20 character password with letters, digits and a MySQL-supported special character."
  }
}

variable "recording_bucket_name" {
  type        = string
  default     = ""
  description = "Globally unique private Object Storage bucket for future Speech uploads."
  validation {
    condition     = !var.enable_application || can(regex("^[a-z][a-z0-9-]{2,62}$", var.recording_bucket_name))
    error_message = "Provide a lowercase bucket name when deploying the application."
  }
}

variable "enable_autoscaling" {
  description = "Enable dual-zone App ASGs only after the prepared image and G3 launch configuration are ready."
  type        = bool
  default     = false
  validation {
    condition     = !var.enable_autoscaling || var.enable_application
    error_message = "Auto Scaling requires the application layer."
  }
}

variable "app_launch_configuration_no" {
  description = "G3 launch configuration number created through the official API; provider 4.0.5 lacks KVM fields."
  type        = string
  default     = ""
  validation {
    condition     = !var.enable_autoscaling || can(regex("^[0-9]+$", var.app_launch_configuration_no))
    error_message = "Provide the prepared G3 launch configuration number before enabling Auto Scaling."
  }
}
