locals {
  zones = { a = "KR-1", b = "KR-2" }
  subnets = {
    lb_a      = { zone = "a", cidr = "10.30.0.0/24", type = "PUBLIC", usage = "LOADB" }
    bastion_a = { zone = "a", cidr = "10.30.1.0/24", type = "PUBLIC", usage = "GEN" }
    nat_a     = { zone = "a", cidr = "10.30.2.0/24", type = "PUBLIC", usage = "NATGW" }
    app_a     = { zone = "a", cidr = "10.30.10.0/24", type = "PRIVATE", usage = "GEN" }
    worker_a  = { zone = "a", cidr = "10.30.20.0/24", type = "PRIVATE", usage = "GEN" }
    db_a      = { zone = "a", cidr = "10.30.30.0/24", type = "PRIVATE", usage = "GEN" }
    lb_b      = { zone = "b", cidr = "10.30.100.0/24", type = "PUBLIC", usage = "LOADB" }
    nat_b     = { zone = "b", cidr = "10.30.102.0/24", type = "PUBLIC", usage = "NATGW" }
    app_b     = { zone = "b", cidr = "10.30.110.0/24", type = "PRIVATE", usage = "GEN" }
    worker_b  = { zone = "b", cidr = "10.30.120.0/24", type = "PRIVATE", usage = "GEN" }
    db_b      = { zone = "b", cidr = "10.30.130.0/24", type = "PRIVATE", usage = "GEN" }
  }
  private_compute = { for key, value in local.subnets : key => value if startswith(key, "app_") || startswith(key, "worker_") }
  egress_ports    = var.allow_bootstrap_http ? ["80", "443"] : ["443"]
}

resource "ncloud_vpc" "council" {
  name            = "council-vpc"
  ipv4_cidr_block = "10.30.0.0/16"
}

resource "ncloud_subnet" "council" {
  for_each       = local.subnets
  vpc_no         = ncloud_vpc.council.id
  name           = "council-${replace(each.key, "_", "-")}"
  subnet         = each.value.cidr
  zone           = local.zones[each.value.zone]
  subnet_type    = each.value.type
  usage_type     = each.value.usage
  network_acl_no = ncloud_vpc.council.default_network_acl_no
}

resource "ncloud_nat_gateway" "egress" {
  for_each  = var.enable_nat ? local.zones : {}
  vpc_no    = ncloud_vpc.council.id
  name      = "council-nat-${each.key}"
  zone      = each.value
  subnet_no = ncloud_subnet.council["nat_${each.key}"].id
}

resource "ncloud_route_table" "compute" {
  for_each              = local.zones
  vpc_no                = ncloud_vpc.council.id
  name                  = "council-compute-${each.key}"
  supported_subnet_type = "PRIVATE"
}

resource "ncloud_route" "internet" {
  for_each               = var.enable_nat ? local.zones : {}
  route_table_no         = ncloud_route_table.compute[each.key].id
  destination_cidr_block = "0.0.0.0/0"
  target_type            = "NATGW"
  target_no              = ncloud_nat_gateway.egress[each.key].id
  target_name            = ncloud_nat_gateway.egress[each.key].name
}

resource "ncloud_route_table_association" "compute" {
  for_each       = local.private_compute
  route_table_no = ncloud_route_table.compute[each.value.zone].id
  subnet_no      = ncloud_subnet.council[each.key].id
}

resource "ncloud_route_table" "db" {
  vpc_no                = ncloud_vpc.council.id
  name                  = "council-db-private"
  supported_subnet_type = "PRIVATE"
}

resource "ncloud_route_table_association" "db" {
  for_each       = local.zones
  route_table_no = ncloud_route_table.db.id
  subnet_no      = ncloud_subnet.council["db_${each.key}"].id
}

resource "ncloud_access_control_group" "council" {
  for_each = toset(["bastion", "app", "worker"])
  name     = "council-${each.key}"
  vpc_no   = ncloud_vpc.council.id
}

# One rule resource per ACG. MySQL's managed ACG will be configured with the DB in the next stage.
resource "ncloud_access_control_group_rule" "bastion" {
  access_control_group_no = ncloud_access_control_group.council["bastion"].id
  inbound {
    protocol   = "TCP"
    ip_block   = var.admin_ipv4_cidr
    port_range = "22"
  }
  dynamic "outbound" {
    for_each = toset([local.subnets.app_a.cidr, local.subnets.app_b.cidr, local.subnets.worker_a.cidr, local.subnets.worker_b.cidr])
    content {
      protocol   = "TCP"
      ip_block   = outbound.value
      port_range = "22"
    }
  }
  dynamic "outbound" {
    for_each = toset(local.egress_ports)
    content {
      protocol   = "TCP"
      ip_block   = "0.0.0.0/0"
      port_range = outbound.value
    }
  }
  outbound {
    protocol   = "UDP"
    ip_block   = "0.0.0.0/0"
    port_range = "53"
  }
  outbound {
    protocol   = "TCP"
    ip_block   = "0.0.0.0/0"
    port_range = "53"
  }
  outbound {
    protocol   = "UDP"
    ip_block   = "0.0.0.0/0"
    port_range = "123"
  }
}

resource "ncloud_access_control_group_rule" "compute" {
  for_each                = toset(["app", "worker"])
  access_control_group_no = ncloud_access_control_group.council[each.key].id
  inbound {
    protocol                       = "TCP"
    source_access_control_group_no = ncloud_access_control_group.council["bastion"].id
    port_range                     = "22"
  }
  dynamic "inbound" {
    for_each = each.key == "app" ? toset([local.subnets.lb_a.cidr, local.subnets.lb_b.cidr]) : toset([])
    content {
      protocol   = "TCP"
      ip_block   = inbound.value
      port_range = "8080"
    }
  }
  dynamic "outbound" {
    for_each = toset([local.subnets.db_a.cidr, local.subnets.db_b.cidr])
    content {
      protocol   = "TCP"
      ip_block   = outbound.value
      port_range = "3306"
    }
  }
  dynamic "outbound" {
    for_each = toset(local.egress_ports)
    content {
      protocol   = "TCP"
      ip_block   = "0.0.0.0/0"
      port_range = outbound.value
    }
  }
  outbound {
    protocol   = "UDP"
    ip_block   = "0.0.0.0/0"
    port_range = "53"
  }
  outbound {
    protocol   = "TCP"
    ip_block   = "0.0.0.0/0"
    port_range = "53"
  }
  outbound {
    protocol   = "UDP"
    ip_block   = "0.0.0.0/0"
    port_range = "123"
  }
}
