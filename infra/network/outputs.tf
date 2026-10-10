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

output "management" {
  value = var.enable_management ? (var.enable_legacy_compute ? {
    server_id  = ncloud_server.bastion[0].id
    public_ip  = ncloud_public_ip.bastion[0].public_ip
    private_ip = ncloud_network_interface.bastion[0].private_ip
    ssh_user   = "counciladmin"
    } : {
    server_id  = ncloud_server.ubuntu["bastion"].id
    public_ip  = ncloud_public_ip.ubuntu_bastion[0].public_ip
    private_ip = ncloud_network_interface.ubuntu["bastion"].private_ip
    ssh_user   = "counciladmin"
  }) : null
}

output "application" {
  value = var.enable_application ? {
    bootstrap = { for role, server in ncloud_server.bootstrap : role => {
      server_id = server.id, private_ip = ncloud_network_interface.bootstrap[role].private_ip
    } }
    mysql_id        = ncloud_mysql.council[0].id
    mysql_acg_ids   = ncloud_mysql.council[0].access_control_group_no_list
    mysql_servers   = ncloud_mysql.council[0].mysql_server_list
    lb_id           = ncloud_lb.council[0].id
    lb_domain       = ncloud_lb.council[0].domain
    lb_ips          = ncloud_lb.council[0].ip_list
    target_group_id = ncloud_lb_target_group.app[0].id
    bucket          = ncloud_objectstorage_bucket.recordings[0].id
  } : null
}

output "app_autoscaling" {
  value = var.enable_ubuntu_app_groups ? { for zone, group in ncloud_auto_scaling_group.ubuntu_app : zone => {
    group_id            = group.id
    server_ids          = group.server_instance_no_list
    scale_out_policy_id = ncloud_auto_scaling_policy.ubuntu_scale_out[zone].id
    scale_in_policy_id  = ncloud_auto_scaling_policy.ubuntu_scale_in[zone].id
    } } : { for zone, group in ncloud_auto_scaling_group.app : zone => {
    group_id            = group.id
    server_ids          = group.server_instance_no_list
    scale_out_policy_id = ncloud_auto_scaling_policy.scale_out[zone].id
    scale_in_policy_id  = ncloud_auto_scaling_policy.scale_in[zone].id
  } }
}

output "ubuntu_app_autoscaling" {
  value = { for zone, group in ncloud_auto_scaling_group.ubuntu_app : zone => {
    group_id            = group.id
    server_ids          = group.server_instance_no_list
    scale_out_policy_id = ncloud_auto_scaling_policy.ubuntu_scale_out[zone].id
    scale_in_policy_id  = ncloud_auto_scaling_policy.ubuntu_scale_in[zone].id
  } }
}
