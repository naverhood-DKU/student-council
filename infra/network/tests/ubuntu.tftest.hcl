mock_provider "ncloud" {}

variables {
  enable_legacy_compute     = true
  enable_legacy_app_groups  = true
  enable_ubuntu_app_groups  = false
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_management         = true
  enable_application        = true
  enable_nat                = true
  enable_autoscaling        = false
  database_password         = "TestPassword12345!"
  recording_bucket_name     = "council-test-recordings"
  management_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8x4RdB1cAVZVDHK4nvLKisADJG8MwjsWnEWzMrDmKX council-test"
  enable_ubuntu_migration   = false
}

run "migration_off_adds_no_parallel_cost" {
  command = plan
  assert {
    condition     = length(ncloud_server.ubuntu) == 0 && length(ncloud_public_ip.ubuntu_bastion) == 0
    error_message = "Migration must be explicitly enabled before parallel billable resources exist."
  }
}

run "migration_preserves_legacy_access_and_private_roles" {
  command = apply
  variables { enable_ubuntu_migration = true }
  assert {
    condition     = ncloud_server.bastion[0].server_image_number == "25495367" && ncloud_network_interface.bastion[0].private_ip == "10.30.1.10" && ncloud_network_interface.ubuntu["bastion"].private_ip == "10.30.1.11"
    error_message = "Preserve the old management path until new Ubuntu access has been verified."
  }
  assert {
    condition     = alltrue([for name, host in ncloud_server.ubuntu : host.server_image_number == "104630229"]) && ncloud_server.ubuntu["worker"].subnet_no == ncloud_subnet.council["worker_a"].id && ncloud_server.ubuntu["builder"].subnet_no == ncloud_subnet.council["app_a"].id && ncloud_public_ip.ubuntu_bastion[0].server_instance_no == ncloud_server.ubuntu["bastion"].id
    error_message = "Ubuntu compute must remain private; public management IP belongs only to Bastion."
  }
  assert {
    condition     = strcontains(ncloud_init_script.ubuntu[0].content, "command -v restorecon") && strcontains(ncloud_init_script.ubuntu[0].content, "reload ssh.service") && strcontains(ncloud_init_script.ubuntu[0].content, "PasswordAuthentication no")
    error_message = "Ubuntu key-only SSH initialization must tolerate absent SELinux and use Ubuntu's SSH unit."
  }
}

run "ubuntu_groups_isolate_verified_replacements" {
  command = apply
  variables {
    enable_ubuntu_migration     = true
    enable_ubuntu_app_groups    = true
    enable_autoscaling          = true
    app_launch_configuration_no = "39685"
  }
  assert {
    condition     = length(ncloud_auto_scaling_group.ubuntu_app) == 2 && length(ncloud_auto_scaling_group.app) == 2 && alltrue([for key, group in ncloud_auto_scaling_group.ubuntu_app : group.name == "council-ubuntu-app-asg-${key}" && group.min_size == 1 && group.max_size == 2 && group.launch_configuration_no == "39685" && group.health_check_type_code == "LOADB"])
    error_message = "New Ubuntu groups must be independent of the legacy groups with one healthy App per zone and bounded scaling."
  }
}

run "retirement_preserves_data_and_ubuntu_management" {
  command = apply
  variables {
    enable_ubuntu_migration     = true
    enable_ubuntu_app_groups    = true
    enable_autoscaling          = true
    app_launch_configuration_no = "39685"
    enable_legacy_compute       = false
    enable_legacy_app_groups    = false
  }
  assert {
    condition     = length(ncloud_server.bastion) == 0 && length(ncloud_server.bootstrap) == 0 && length(ncloud_auto_scaling_group.app) == 0 && length(ncloud_public_ip.bastion) == 0 && length(ncloud_server.ubuntu) == 3 && length(ncloud_auto_scaling_group.ubuntu_app) == 2 && length(ncloud_login_key.council) == 1 && length(ncloud_mysql.council) == 1 && length(ncloud_lb.council) == 1 && output.management.server_id == ncloud_server.ubuntu["bastion"].id && output.app_autoscaling["a"].group_id == ncloud_auto_scaling_group.ubuntu_app["a"].id
    error_message = "Retire only replaced legacy compute; preserve Ubuntu access, managed DB, LB and login key."
  }
}

run "capacity_limit_is_configurable_without_reducing_zone_minimums" {
  command = apply
  variables {
    enable_ubuntu_migration     = true
    enable_ubuntu_app_groups    = true
    enable_autoscaling          = true
    app_launch_configuration_no = "39685"
    ubuntu_app_max_size_by_zone = { a = 2, b = 1 }
  }
  assert {
    condition     = ncloud_auto_scaling_group.ubuntu_app["a"].max_size == 2 && ncloud_auto_scaling_group.ubuntu_app["b"].max_size == 1 && alltrue([for group in ncloud_auto_scaling_group.ubuntu_app : group.min_size == 1 && group.desired_capacity == 1])
    error_message = "A measured total limit of three must preserve one baseline App in each zone."
  }
}
