mock_provider "ncloud" {}

variables {
  enable_legacy_compute     = true
  enable_legacy_app_groups  = true
  enable_ubuntu_app_groups  = false
  enable_ubuntu_migration   = false
  enable_application        = false
  enable_autoscaling        = false
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_nat                = false
  enable_management         = false
  management_ssh_public_key = ""
}

run "network_without_paid_nat" {
  command = plan
  assert {
    condition     = length(ncloud_subnet.council) == 11 && length(ncloud_nat_gateway.egress) == 0 && length(ncloud_route.internet) == 0
    error_message = "Prepare both zones without provisioning paid NAT by default."
  }
  assert {
    condition     = alltrue([for key in ["app_a", "app_b", "worker_a", "worker_b", "db_a", "db_b"] : ncloud_subnet.council[key].subnet_type == "PRIVATE"])
    error_message = "Compute and database subnets must be private."
  }
  assert {
    condition     = alltrue([for rule in ncloud_access_control_group_rule.bastion.inbound : rule.ip_block == "198.51.100.10/32" && rule.port_range == "22"])
    error_message = "Only the configured management IP may enter Bastion."
  }
  assert {
    condition     = length(ncloud_access_control_group_rule.compute["worker"].inbound) == 1 && alltrue([for rule in ncloud_access_control_group_rule.compute["worker"].inbound : rule.port_range == "22"])
    error_message = "Worker must not expose a public HTTP port."
  }
  assert {
    condition     = toset([for rule in ncloud_access_control_group_rule.compute["app"].inbound : rule.ip_block if rule.port_range == "8080"]) == toset(["10.30.0.0/24", "10.30.100.0/24"])
    error_message = "App HTTP must allow exactly the two LB subnet ranges."
  }
}

run "nat_per_zone" {
  command = plan
  variables { enable_nat = true }
  assert {
    condition     = length(ncloud_nat_gateway.egress) == 2 && length(ncloud_route.internet) == 2 && length(ncloud_route_table_association.compute) == 4 && length(ncloud_route_table_association.db) == 2
    error_message = "Two zonal NAT paths and separate private database routing are required."
  }
  assert {
    condition     = ncloud_nat_gateway.egress["a"].zone == "KR-1" && ncloud_nat_gateway.egress["b"].zone == "KR-2"
    error_message = "NAT gateways must be distributed between zones."
  }
}

run "reject_worldwide_ssh" {
  command = plan
  variables { admin_ipv4_cidr = "0.0.0.0/0" }
  expect_failures = [var.admin_ipv4_cidr]
}

run "db_nacl_only_on_db_subnets" {
  command = plan
  variables { enable_db_nacl = true }
  assert {
    condition     = length(ncloud_network_acl.db) == 1 && length(ncloud_network_acl_rule.db) == 1
    error_message = "db nacl 하나만"
  }
  assert {
    condition     = toset([for rule in ncloud_network_acl_rule.db[0].inbound : rule.ip_block if rule.port_range == "3306" && rule.rule_action == "ALLOW"]) == toset(["10.30.10.0/24", "10.30.110.0/24", "10.30.20.0/24", "10.30.120.0/24"])
    error_message = "3306 허용은 app/worker 서브넷 4개만"
  }
  assert {
    condition     = alltrue([for rule in ncloud_network_acl_rule.db[0].inbound : rule.rule_action == "DROP" if rule.ip_block == "0.0.0.0/0" && rule.port_range == "3306"])
    error_message = "외부 3306은 drop"
  }
  assert {
    condition     = min([for rule in ncloud_network_acl_rule.db[0].inbound : rule.priority if rule.ip_block == "0.0.0.0/0"]...) > max([for rule in ncloud_network_acl_rule.db[0].inbound : rule.priority if rule.port_range == "3306" && rule.rule_action == "ALLOW"]...)
    error_message = "허용 규칙이 drop보다 먼저 와야 함"
  }
}

run "db_nacl_off_keeps_default" {
  command = plan
  assert {
    condition     = length(ncloud_network_acl.db) == 0
    error_message = "기본값은 nacl 없음"
  }
}
