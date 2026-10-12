locals {
  bootstrap_servers = var.enable_application && var.enable_legacy_compute ? {
    app    = { subnet = "app_a", ip = "10.30.10.10", acg = "app", name = "council-app-image" }
    worker = { subnet = "worker_a", ip = "10.30.20.10", acg = "worker", name = "council-worker-a" }
  } : {}
}

# This init script contains a PUBLIC SSH key only. App/AI credentials are
# installed through the authenticated management connection after provisioning.
resource "ncloud_init_script" "compute" {
  count   = var.enable_application && var.enable_legacy_compute ? 1 : 0
  name    = "council-compute-init"
  os_type = "LNX"
  content = templatefile("${path.module}/templates/bastion-init.sh.tftpl", {
    ssh_public_key = var.management_ssh_public_key
  })
}

resource "ncloud_network_interface" "bootstrap" {
  for_each              = local.bootstrap_servers
  name                  = "${each.value.name}-nic"
  subnet_no             = ncloud_subnet.council[each.value.subnet].id
  private_ip            = each.value.ip
  access_control_groups = [ncloud_access_control_group.council[each.value.acg].id]
}

resource "ncloud_server" "bootstrap" {
  for_each             = local.bootstrap_servers
  name                 = each.value.name
  subnet_no            = ncloud_subnet.council[each.value.subnet].id
  zone                 = "KR-1"
  server_image_number  = var.bastion_image_number
  server_spec_code     = "s2-g3"
  login_key_name       = ncloud_login_key.council[0].key_name
  init_script_no       = ncloud_init_script.compute[0].id
  fee_system_type_code = "MTRAT"
  network_interface {
    network_interface_no = ncloud_network_interface.bootstrap[each.key].id
    order                = 0
  }
}

resource "ncloud_mysql" "council" {
  count               = var.enable_application ? 1 : 0
  service_name        = "council-mysql"
  server_name_prefix  = "council-db"
  image_product_code  = "SW.VMYSL.OS.LNX64.ROCKY.08.MYSQL.B050.G003"
  product_code        = "SVR.VDBAS.AMD.STAND.C002.M008.NET.SSD.B050.G003"
  engine_version_code = "8.0.45"
  # G3 defaults to CB2; provider 4.0.5 reads its legacy response field as null.
  subnet_no                = ncloud_subnet.council["db_a"].id
  standby_master_subnet_no = ncloud_subnet.council["db_b"].id
  is_ha                    = true
  is_multi_zone            = true
  # Official API marks this option "to be applied later". Actual G3 readback
  # is false even when true is requested; do not claim storage encryption.
  is_storage_encryption        = false
  is_backup                    = true
  is_automatic_backup          = true
  backup_file_retention_period = 7
  user_name                    = "councilapp"
  user_password                = var.database_password
  host_ip                      = "10.30.%"
  database_name                = "councilflow"
}

resource "ncloud_objectstorage_bucket" "recordings" {
  count       = var.enable_application ? 1 : 0
  bucket_name = var.recording_bucket_name
}

resource "ncloud_lb" "council" {
  count           = var.enable_application ? 1 : 0
  name            = "council-lb"
  network_type    = "PUBLIC"
  type            = "APPLICATION"
  throughput_type = "SMALL"
  idle_timeout    = 60
  subnet_no_list  = [ncloud_subnet.council["lb_a"].id, ncloud_subnet.council["lb_b"].id]
}

resource "ncloud_lb_target_group" "app" {
  count              = var.enable_application ? 1 : 0
  name               = "council-app-tg"
  vpc_no             = ncloud_vpc.council.id
  protocol           = "HTTP"
  port               = 8080
  target_type        = "VSVR"
  algorithm_type     = "RR"
  use_sticky_session = false
  use_proxy_protocol = false
  health_check {
    protocol       = "HTTP"
    port           = 8080
    http_method    = "GET"
    url_path       = "/actuator/health"
    cycle          = 5
    up_threshold   = 2
    down_threshold = 2
  }
}

# HTTP is explicitly approved for synthetic demo data. Add a domain and
# HTTPS before real-data use; this listener does not claim transport security.
resource "ncloud_lb_listener" "http" {
  count            = var.enable_application ? 1 : 0
  load_balancer_no = ncloud_lb.council[0].id
  protocol         = "HTTP"
  port             = 80
  target_group_no  = ncloud_lb_target_group.app[0].id
}
