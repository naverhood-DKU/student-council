mock_provider "ncloud" {}
variables {
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_management         = true
  enable_nat                = true
  enable_application        = true
  enable_ubuntu_migration   = true
  management_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8x4RdB1cAVZVDHK4nvLKisADJG8MwjsWnEWzMrDmKX council-test"
  database_password         = "TestPassword12345!"
  recording_bucket_name     = "council-test-recordings"
}
run "worker_recovery_is_opt_in" {
  command = plan
  assert {
    condition     = length(ncloud_auto_scaling_group.worker) == 0
    error_message = "Recovery ASG must be opt-in to avoid accidental duplicate workers."
  }
}
run "single_worker_recovery_has_no_load_balancer_or_scaling_policy" {
  command = apply
  variables {
    enable_worker_recovery         = true
    worker_launch_configuration_no = "12345"
  }
  assert {
    condition     = ncloud_auto_scaling_group.worker[0].min_size == 1 && ncloud_auto_scaling_group.worker[0].max_size == 1 && ncloud_auto_scaling_group.worker[0].desired_capacity == 1 && ncloud_auto_scaling_group.worker[0].health_check_type_code == "SVR" && length(ncloud_auto_scaling_group.worker[0].target_group_list) == 0
    error_message = "Worker must recover a single server using VM checks, without an HTTP load balancer."
  }
  assert {
    condition     = ncloud_auto_scaling_group.worker[0].subnet_no == ncloud_subnet.council["worker_a"].id && ncloud_auto_scaling_group.worker[0].access_control_group_no_list == tolist([ncloud_access_control_group.council["worker"].id])
    error_message = "Recovered Worker must retain private subnet and Worker-only ACG."
  }
}
run "second_zone_is_explicit_and_counts_both_fixed_workers" {
  command = apply
  variables {
    enable_worker_recovery         = true
    enable_worker_second_zone      = true
    worker_launch_configuration_no = "12345"
  }
  assert {
    condition     = output.worker_recovery.capacity == 2
    error_message = "Explicit second-zone opt-in must provision two fixed Workers."
  }
}
run "second_zone_keeps_same_image_and_private_worker_network" {
  command = apply
  variables {
    enable_worker_recovery         = true
    enable_worker_second_zone      = true
    worker_launch_configuration_no = "12345"
  }
  assert {
    condition     = ncloud_auto_scaling_group.worker_b[0].min_size == 1 && ncloud_auto_scaling_group.worker_b[0].max_size == 1 && ncloud_auto_scaling_group.worker_b[0].desired_capacity == 1 && ncloud_auto_scaling_group.worker_b[0].health_check_type_code == "SVR" && length(ncloud_auto_scaling_group.worker_b[0].target_group_list) == 0
    error_message = "Second Worker is fixed capacity VM recovery; no HTTP LB or CPU throughput scaling."
  }
  assert {
    condition     = ncloud_auto_scaling_group.worker_b[0].subnet_no == ncloud_subnet.council["worker_b"].id && ncloud_auto_scaling_group.worker_b[0].launch_configuration_no == ncloud_auto_scaling_group.worker[0].launch_configuration_no && ncloud_auto_scaling_group.worker_b[0].access_control_group_no_list == tolist([ncloud_access_control_group.council["worker"].id])
    error_message = "Both Workers share runtime image and private Worker ACG, in separate zones."
  }
}
