mock_provider "ncloud" {}

variables {
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_management         = true
  enable_nat                = true
  enable_application        = true
  management_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8x4RdB1cAVZVDHK4nvLKisADJG8MwjsWnEWzMrDmKX council-test"
  database_password         = "TestPassword12345!"
  recording_bucket_name     = "council-test-recordings"
}

run "one_healthy_app_per_zone_uses_lb_and_shared_launch_config" {
  command = apply
  variables {
    enable_autoscaling          = true
    app_launch_configuration_no = "123456"
  }
  assert {
    condition     = length(ncloud_auto_scaling_group.app) == 2 && alltrue([for a in ncloud_auto_scaling_group.app : a.min_size == 1 && a.max_size == 2 && a.health_check_type_code == "LOADB" && a.launch_configuration_no == "123456" && contains(a.target_group_list, ncloud_lb_target_group.app[0].id)])
    error_message = "Each zone must retain one App and replace unhealthy targets via the shared deployment image."
  }
  assert {
    condition     = ncloud_auto_scaling_group.app["a"].subnet_no == ncloud_subnet.council["app_a"].id && ncloud_auto_scaling_group.app["b"].subnet_no == ncloud_subnet.council["app_b"].id
    error_message = "The two groups must use distinct private App subnets in different zones."
  }
  assert {
    condition     = alltrue([for a in ncloud_auto_scaling_group.app : length(a.server_name_prefix) >= 3 && length(a.server_name_prefix) <= 7])
    error_message = "NCP Auto Scaling server name prefixes must be between 3 and 7 characters."
  }
}

run "no_asg_without_prepared_image_launch_config" {
  command = plan
  variables {
    enable_autoscaling          = true
    app_launch_configuration_no = ""
  }
  expect_failures = [var.app_launch_configuration_no]
}
