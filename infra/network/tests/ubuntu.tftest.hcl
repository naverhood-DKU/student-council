mock_provider "ncloud" {}

variables {
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
