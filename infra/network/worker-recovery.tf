# Fixed capacity provides VM replacement, not additional analysis throughput.
# Stop the standalone Worker before activating this group's Worker.
resource "ncloud_auto_scaling_group" "worker" {
  count                        = var.enable_worker_recovery ? 1 : 0
  name                         = "council-ubuntu-worker-asg-a"
  server_name_prefix           = "wrk-kr1"
  subnet_no                    = ncloud_subnet.council["worker_a"].id
  access_control_group_no_list = [ncloud_access_control_group.council["worker"].id]
  launch_configuration_no      = var.worker_launch_configuration_no
  min_size                     = 1
  max_size                     = 1
  desired_capacity             = 1
  default_cooldown             = 300
  health_check_type_code       = "SVR"
  health_check_grace_period    = 300
  target_group_list            = []
  wait_for_capacity_timeout    = "20m"
}
output "worker_recovery" {
  value = var.enable_worker_recovery ? {
    group_id                = ncloud_auto_scaling_group.worker[0].id
    launch_configuration_no = var.worker_launch_configuration_no
    capacity                = 1 + length(ncloud_auto_scaling_group.worker_b)
    second_zone_group_id    = var.enable_worker_second_zone ? ncloud_auto_scaling_group.worker_b[0].id : null
    health_check            = "SVR"
  } : null
}

# Enable only after single-Worker canary and lease/heartbeat verification.
# Each zone remains fixed at one; extra capacity provides takeover, not quota bypass.
resource "ncloud_auto_scaling_group" "worker_b" {
  count                        = var.enable_worker_second_zone ? 1 : 0
  name                         = "council-ubuntu-worker-asg-b"
  server_name_prefix           = "wrk-kr2"
  subnet_no                    = ncloud_subnet.council["worker_b"].id
  access_control_group_no_list = [ncloud_access_control_group.council["worker"].id]
  launch_configuration_no      = var.worker_launch_configuration_no
  min_size                     = 1
  max_size                     = 1
  desired_capacity             = 1
  default_cooldown             = 300
  health_check_type_code       = "SVR"
  health_check_grace_period    = 300
  target_group_list            = []
  wait_for_capacity_timeout    = "20m"
}
