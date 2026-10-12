# Management is opt-in; existing network-only callers do not create a server.
resource "ncloud_login_key" "council" {
  count    = var.enable_management ? 1 : 0
  key_name = "council-infra-key"
}

resource "ncloud_init_script" "bastion" {
  count   = var.enable_management ? 1 : 0
  name    = "council-bastion-init"
  os_type = "LNX"
  content = templatefile("${path.module}/templates/bastion-init.sh.tftpl", {
    ssh_public_key = var.management_ssh_public_key
  })
}

resource "ncloud_network_interface" "bastion" {
  count                 = var.enable_management ? 1 : 0
  name                  = "council-bastion-nic"
  subnet_no             = ncloud_subnet.council["bastion_a"].id
  private_ip            = "10.30.1.10"
  access_control_groups = [ncloud_access_control_group.council["bastion"].id]
}

resource "ncloud_server" "bastion" {
  count                = var.enable_management ? 1 : 0
  name                 = "council-bastion-a"
  zone                 = "KR-1"
  subnet_no            = ncloud_subnet.council["bastion_a"].id
  server_image_number  = var.bastion_image_number
  server_spec_code     = "c2-g3"
  login_key_name       = ncloud_login_key.council[0].key_name
  init_script_no       = ncloud_init_script.bastion[0].id
  fee_system_type_code = "MTRAT"
  network_interface {
    network_interface_no = ncloud_network_interface.bastion[0].id
    order                = 0
  }
}

resource "ncloud_public_ip" "bastion" {
  count              = var.enable_management ? 1 : 0
  server_instance_no = ncloud_server.bastion[0].id
  description        = "Council management SSH; one admin IPv4 /32 only"
}
