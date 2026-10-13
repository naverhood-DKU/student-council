locals {
  # db 서브넷 nacl. 3306은 app/worker 서브넷만, 나머지는 막음
  db_client_cidrs = [for key in ["app_a", "app_b", "worker_a", "worker_b"] : local.subnets[key].cidr]
  db_peer_cidrs   = [local.subnets.db_a.cidr, local.subnets.db_b.cidr]

  db_nacl_inbound = concat(
    [for i, cidr in local.db_client_cidrs : { priority = 100 + i, protocol = "TCP", action = "ALLOW", cidr = cidr, ports = "3306" }],
    # master/standby 복제
    flatten([for i, cidr in local.db_peer_cidrs : [
      { priority = 110 + i * 3, protocol = "TCP", action = "ALLOW", cidr = cidr, ports = "1-65535" },
      { priority = 111 + i * 3, protocol = "UDP", action = "ALLOW", cidr = cidr, ports = "1-65535" },
      { priority = 112 + i * 3, protocol = "ICMP", action = "ALLOW", cidr = cidr, ports = null },
    ]]),
    [
      { priority = 150, protocol = "TCP", action = "DROP", cidr = "0.0.0.0/0", ports = "3306" },
      # stateless라 응답 포트 열어둠
      { priority = 160, protocol = "TCP", action = "ALLOW", cidr = "0.0.0.0/0", ports = "1024-65535" },
      { priority = 161, protocol = "UDP", action = "ALLOW", cidr = "0.0.0.0/0", ports = "1024-65535" },
      { priority = 197, protocol = "TCP", action = "DROP", cidr = "0.0.0.0/0", ports = "1-65535" },
      { priority = 198, protocol = "UDP", action = "DROP", cidr = "0.0.0.0/0", ports = "1-65535" },
      { priority = 199, protocol = "ICMP", action = "DROP", cidr = "0.0.0.0/0", ports = null },
    ],
  )
  db_nacl_outbound = [
    { priority = 100, protocol = "TCP", action = "ALLOW", cidr = "0.0.0.0/0", ports = "1-65535" },
    { priority = 101, protocol = "UDP", action = "ALLOW", cidr = "0.0.0.0/0", ports = "1-65535" },
    { priority = 102, protocol = "ICMP", action = "ALLOW", cidr = "0.0.0.0/0", ports = null },
  ]
}

resource "ncloud_network_acl" "db" {
  count       = var.enable_db_nacl ? 1 : 0
  vpc_no      = ncloud_vpc.council.id
  name        = "council-db-nacl"
  description = "db subnet 3306 from app/worker only"
}

resource "ncloud_network_acl_rule" "db" {
  count          = var.enable_db_nacl ? 1 : 0
  network_acl_no = ncloud_network_acl.db[0].id

  dynamic "inbound" {
    for_each = local.db_nacl_inbound
    content {
      priority    = inbound.value.priority
      protocol    = inbound.value.protocol
      rule_action = inbound.value.action
      ip_block    = inbound.value.cidr
      port_range  = inbound.value.ports
    }
  }

  dynamic "outbound" {
    for_each = local.db_nacl_outbound
    content {
      priority    = outbound.value.priority
      protocol    = outbound.value.protocol
      rule_action = outbound.value.action
      ip_block    = outbound.value.cidr
      port_range  = outbound.value.ports
    }
  }
}
