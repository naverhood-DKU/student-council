output "vpc_id" {
  value = ncloud_vpc.council.id
}
output "subnets" {
  value = { for key, subnet in ncloud_subnet.council : key => {
    id = subnet.id, cidr = subnet.subnet, zone = subnet.zone, type = subnet.subnet_type
  } }
}
output "acg_ids" {
  value = { for key, acg in ncloud_access_control_group.council : key => acg.id }
}
output "nat_public_ips" {
  value = { for key, nat in ncloud_nat_gateway.egress : key => nat.public_ip }
}
