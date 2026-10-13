# Blue/green groups isolate retirement of the previous mixed-OS groups.
resource "ncloud_auto_scaling_group" "ubuntu_app" {
  for_each                     = var.enable_ubuntu_app_groups ? local.zones : {}
  name                         = "council-ubuntu-app-asg-${each.key}"
  server_name_prefix           = each.key == "a" ? "app-kr1" : "app-kr2"
  subnet_no                    = ncloud_subnet.council["app_${each.key}"].id
  access_control_group_no_list = [ncloud_access_control_group.council["app"].id]
  launch_configuration_no      = var.app_launch_configuration_no
  min_size                     = 1
  max_size                     = var.ubuntu_app_max_size_by_zone[each.key]
  desired_capacity             = 1
  default_cooldown             = 300
  health_check_type_code       = "LOADB"
  health_check_grace_period    = 180
  target_group_list            = [ncloud_lb_target_group.app[0].id]
  wait_for_capacity_timeout    = "20m"
  lifecycle { ignore_changes = [desired_capacity] }
}

resource "ncloud_auto_scaling_policy" "ubuntu_scale_out" {
  for_each              = var.enable_ubuntu_app_groups ? local.zones : {}
  name                  = "council-ubuntu-app-${each.key}-out"
  auto_scaling_group_no = ncloud_auto_scaling_group.ubuntu_app[each.key].id
  adjustment_type_code  = "CHANG"
  scaling_adjustment    = 1
  cooldown              = 300
}

resource "ncloud_auto_scaling_policy" "ubuntu_scale_in" {
  for_each              = var.enable_ubuntu_app_groups ? local.zones : {}
  name                  = "council-ubuntu-app-${each.key}-in"
  auto_scaling_group_no = ncloud_auto_scaling_group.ubuntu_app[each.key].id
  adjustment_type_code  = "CHANG"
  scaling_adjustment    = -1
  cooldown              = 600
  lifecycle { ignore_changes = [cooldown] }
}
