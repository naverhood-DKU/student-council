mock_provider "ncloud" {}

variables {
  enable_legacy_compute     = true
  enable_legacy_app_groups  = true
  enable_ubuntu_app_groups  = false
  enable_ubuntu_migration   = false
  enable_application        = false
  enable_autoscaling        = false
  admin_ipv4_cidr           = "198.51.100.10/32"
  enable_nat                = false
  enable_management         = false
  management_ssh_public_key = ""
}

run "disabled_management_has_no_server_or_public_ip" {
  command = plan
  assert {
    condition     = length(ncloud_server.bastion) == 0 && length(ncloud_public_ip.bastion) == 0
    error_message = "Management resources must not be provisioned without explicit opt-in."
  }
}

run "enabled_management_requires_a_public_key" {
  command = plan
  variables { enable_management = true }
  expect_failures = [var.management_ssh_public_key]
}

run "bastion_uses_key_auth_and_restricts_login_user" {
  command = plan
  variables {
    enable_management         = true
    management_ssh_public_key = "ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIF8x4RdB1cAVZVDHK4nvLKisADJG8MwjsWnEWzMrDmKX council-test"
  }
  assert {
    condition     = strcontains(ncloud_init_script.bastion[0].content, "PermitRootLogin no") && strcontains(ncloud_init_script.bastion[0].content, "PasswordAuthentication no") && strcontains(ncloud_init_script.bastion[0].content, "AllowUsers counciladmin") && strcontains(ncloud_init_script.bastion[0].content, "AuthenticationMethods publickey")
    error_message = "Bastion must reject root/password login and allow only the managed key-authenticated user."
  }
  assert {
    condition     = strcontains(ncloud_init_script.bastion[0].content, var.management_ssh_public_key) && strcontains(ncloud_init_script.bastion[0].content, "sshd -t")
    error_message = "Install the supplied SSH public key and validate sshd before restarting."
  }
  assert {
    condition     = ncloud_server.bastion[0].server_spec_code == "c2-g3" && ncloud_server.bastion[0].server_image_number == "25495367" && ncloud_network_interface.bastion[0].private_ip == "10.30.1.10"
    error_message = "Use the selected Bastion capacity and reserved management subnet address."
  }
}
