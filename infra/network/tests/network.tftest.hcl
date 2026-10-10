mock_provider "ncloud" {}

variables {
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
