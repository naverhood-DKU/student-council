# Parallel migration hosts. Existing Rocky resources remain until cutover verifies.
locals {
  ubuntu_hosts = var.enable_ubuntu_migration ? {
    bastion = { subnet = "bastion_a", ip = "10.30.1.11", acg = "bastion", spec = "c2-g3" }
    builder = { subnet = "app_a", ip = "10.30.10.11", acg = "app", spec = "s2-g3" }
    worker  = { subnet = "worker_a", ip = "10.30.20.11", acg = "worker", spec = "s2-g3" }
  } : {}
}

resource "ncloud_init_script" "ubuntu" {
  count   = var.enable_ubuntu_migration ? 1 : 0
  name    = "council-ubuntu-init"
  os_type = "LNX"
  content = templatefile("${path.module}/templates/ubuntu-init.sh.tftpl", {
    ssh_public_key = var.management_ssh_public_key
  })
}

resource "ncloud_network_interface" "ubuntu" {
  for_each              = local.ubuntu_hosts
  name                  = "council-ubuntu-${each.key}-nic"
  subnet_no             = ncloud_subnet.council[each.value.subnet].id
  private_ip            = each.value.ip
  access_control_groups = [ncloud_access_control_group.council[each.value.acg].id]
}

resource "ncloud_server" "ubuntu" {
  for_each             = local.ubuntu_hosts
  name                 = "council-ubuntu-${each.key}"
  zone                 = "KR-1"
  subnet_no            = ncloud_subnet.council[each.value.subnet].id
  server_image_number  = "104630229"
  server_spec_code     = each.value.spec
  login_key_name       = ncloud_login_key.council[0].key_name
  init_script_no       = ncloud_init_script.ubuntu[0].id
  fee_system_type_code = "MTRAT"
  network_interface {
    network_interface_no = ncloud_network_interface.ubuntu[each.key].id
    order                = 0
  }
}

resource "ncloud_public_ip" "ubuntu_bastion" {
  count              = var.enable_ubuntu_migration ? 1 : 0
  server_instance_no = ncloud_server.ubuntu["bastion"].id
  description        = "Ubuntu replacement Bastion; existing admin IPv4 /32 ACG"
}

output "ubuntu_migration_hosts" {
  value = {
    for name, server in ncloud_server.ubuntu : name => {
      server_no  = server.id
      private_ip = ncloud_network_interface.ubuntu[name].private_ip
    }
  }
}

output "ubuntu_bastion_public_ip" {
  value = var.enable_ubuntu_migration ? ncloud_public_ip.ubuntu_bastion[0].public_ip : null
}
