mock_provider "ncloud" {}

variables {
  enable_legacy_compute     = true
  enable_legacy_app_groups  = true
  enable_ubuntu_app_groups  = false
  enable_ubuntu_migration   = false
  enable_application        = false
  enable_autoscaling        = false
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_management         = true
  enable_nat                = true
  management_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8x4RdB1cAVZVDHK4nvLKisADJG8MwjsWnEWzMrDmKX council-test"
}

run "application_disabled_by_default" {
  command = plan
  assert {
    condition     = length(ncloud_mysql.council) == 0 && length(ncloud_server.bootstrap) == 0 && length(ncloud_lb.council) == 0
    error_message = "Network-only plans must not provision paid application resources."
  }
}

run "enabled_application_has_private_compute_ha_db_and_lb_health" {
  command = apply
  variables {
    enable_application    = true
    database_password     = "TestPassword12345!"
    recording_bucket_name = "council-test-recordings"
  }
  assert {
    condition     = ncloud_mysql.council[0].is_ha && ncloud_mysql.council[0].is_multi_zone && ncloud_mysql.council[0].subnet_no != ncloud_mysql.council[0].standby_master_subnet_no && ncloud_mysql.council[0].backup_file_retention_period == 7
    error_message = "Database must span two subnets with HA and daily retained backups."
  }
  assert {
    condition     = ncloud_server.bootstrap["app"].subnet_no == ncloud_subnet.council["app_a"].id && ncloud_server.bootstrap["worker"].subnet_no == ncloud_subnet.council["worker_a"].id && length(ncloud_public_ip.bastion) == 1
    error_message = "App and Worker must use private subnets; only Bastion receives a server public IP."
  }
  assert {
    condition     = length(ncloud_lb.council[0].subnet_no_list) == 2 && ncloud_lb_target_group.app[0].health_check[0].url_path == "/actuator/health" && ncloud_lb_target_group.app[0].port == 8080 && !ncloud_lb_target_group.app[0].use_sticky_session
    error_message = "Both LB zones must route to stateless App servers with the deployed health endpoint."
  }
}

run "application_requires_management_and_nat" {
  command = plan
  variables {
    enable_application    = true
    enable_nat            = false
    database_password     = "TestPassword12345!"
    recording_bucket_name = "council-test-recordings"
  }
  expect_failures = [var.enable_application]
}
