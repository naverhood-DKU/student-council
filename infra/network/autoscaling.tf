resource "ncloud_auto_scaling_group" "app" {
  for_each                     = var.enable_autoscaling ? local.zones : {}
  name                         = "council-app-asg-${each.key}"
  server_name_prefix           = "cfapp-${each.key}"
  subnet_no                    = ncloud_subnet.council["app_${each.key}"].id
  access_control_group_no_list = [ncloud_access_control_group.council["app"].id]
  launch_configuration_no      = var.app_launch_configuration_no
  min_size                     = 1
  max_size                     = 2
  desired_capacity             = 1
  default_cooldown             = 300
  health_check_type_code       = "LOADB"
  health_check_grace_period    = 180
  target_group_list            = [ncloud_lb_target_group.app[0].id]
  wait_for_capacity_timeout    = "20m"
  lifecycle {
    # CPU policies manage the desired count; retain Terraform control of min/max.
    ignore_changes = [desired_capacity]
  }
}

resource "ncloud_auto_scaling_policy" "scale_out" {
  for_each              = var.enable_autoscaling ? local.zones : {}
  name                  = "council-app-${each.key}-out"
  auto_scaling_group_no = ncloud_auto_scaling_group.app[each.key].id
  adjustment_type_code  = "CHANG"
  scaling_adjustment    = 1
  cooldown              = 300
}

resource "ncloud_auto_scaling_policy" "scale_in" {
  for_each              = var.enable_autoscaling ? local.zones : {}
  name                  = "council-app-${each.key}-in"
  auto_scaling_group_no = ncloud_auto_scaling_group.app[each.key].id
  adjustment_type_code  = "CHANG"
  scaling_adjustment    = -1
  cooldown              = 600
  lifecycle {
    # Provider 4.0.5 reads the existing scale-in cooldown as null after import.
    # Keep the create value; verify actual API coolDown=600 before each change.
    ignore_changes = [cooldown]
  }
}
