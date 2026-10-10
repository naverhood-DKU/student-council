# Ubuntu Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [x]`) syntax for tracking.

**Goal:** Deploy the reviewed meeting-branch UI and migrate self-managed Bastion, App and Worker to Ubuntu without replacing the production database.

**Architecture:** Provision parallel Ubuntu 24.04 LTS hosts in the existing VPC. Validate SSH and Java 21 before installing the combined UI/API jar. Create an Ubuntu image/launch configuration, replace App instances one zone at a time, switch the Worker, and retire replaced Rocky hosts after verifying the new management path.

**Tech Stack:** Ubuntu 24.04 LTS image 104630229, Java 21, systemd, Terraform ncloud 4.0.5, NCP G3, React/Vite.

**Spec:** User request: finish UI improvement then replace Rocky servers with Ubuntu. Existing operational topology: `docs/infra.md`; runtime and auth constraints: `CLAUDE.md`.

## Global Constraints
- Java 21; preserve real CLOVA Studio and all group/object authorization.
- UI only: recording upload and Speech remain unimplemented and visibly marked 준비 중.
- Keep DB, VPC, subnets, LB, ACGs and private bucket; never log or commit credentials/state.
- Existing HTTP/plaintext DB exception remains synthetic-data-only.
- Existing App minimum 1 per zone, maximum 2 per zone; retain the healthy zone during migration.
- Existing groups and all application data remain in the same managed MySQL service.

## Review Focus
- Ubuntu has no SELinux restorecon: initialize public-key access without failing halfway.
- Ubuntu SSH unit is ssh.service: retain password/root-login prohibition.
- JDK path differs from Rocky: refuse deployment when selected Java is not version 21.
- ASG configuration changes affect newly created instances only: prove OS of every active instance.
- Worker cutover: do not leave the old worker polling while validating a real job on the replacement.

### Task 1: Portable SSH and Java runtime
Files: `infra/network/templates/ubuntu-init.sh.tftpl`, `infra/runtime/install-runtime.sh`, `infra/runtime/council-flow.service`, `infra/runtime/tests/test_install_runtime.py`.
- [x] Add test asserting `/usr/bin/java` is used by the service and installer, and test Java 17 refuses artifact installation. Run `python3 -m unittest discover -s infra/runtime/tests -v`; expect failure before change.
- [x] Require Java 21 with `java_version=$(/usr/bin/java -version 2>&1); grep -Eq 'version "21[.]' <<< "$java_version"`; abort before copying artifacts on mismatch.
- [x] Change ExecStart to `/usr/bin/java`; keep restart and is-active verification.
- [x] Guard `restorecon` with `command -v restorecon`; reload ssh.service if present, otherwise sshd.service; validate `/usr/sbin/sshd -t` first.
- [x] Run runtime tests and `bash -n infra/runtime/install-runtime.sh`; commit portability change.

### Task 2: Parallel Ubuntu infrastructure
Files: `infra/network/ubuntu.tf`, `infra/network/variables.tf`, `infra/network/outputs.tf`, `docs/infra.md`; local protected migration scripts under outputs.
Consumes: existing VPC/ACGs/login-key and Task 1 SSH template. Produces: parallel Ubuntu Bastion/Worker/image-builder addresses.
- [x] Add opt-in `enable_ubuntu_migration=false`; new hosts use image 104630229 and private addresses 10.30.1.11, 10.30.20.11, 10.30.10.11. Bastion c2-g3, others s2-g3; use existing SSH public key/login key/ACGs.
- [x] Validate `terraform fmt -check`, `terraform validate`, existing `terraform test`; inspect saved real plan. Only creation of three NICs, three servers, one init script and one public IP is permitted in this stage.
- [x] Back up protected state. Apply reviewed plan; record IDs. Verify `/etc/os-release`, SSH effective config, Java 21 apt installation and DB reachability via SSH.
- [x] Build jar with JDK21 `./mvnw verify`; install reviewed new UI jar on builder, private environment, seed disabled. Verify login/current groups unchanged.
- [x] Commit and push branch; share evidence, not keys.

### Task 3: App and Worker cutover
Consumes: verified builder and jar. Produces: Ubuntu image/LC and live Ubuntu services.
- [x] Stop builder; create private snapshot/image using official NCP API. Wait until image state CREAT; create G3 launch configuration using image and s2-g3.
- [x] Update Terraform app_launch_configuration_no to the new LC. Inspect plan before the corresponding migration step; preserve DB/network. Final cutover uses isolated Ubuntu groups. Apply and read actual API configurations.
- [x] Create isolated Ubuntu ASGs in both zones using `infra/network/ubuntu-autoscaling.tf`. Verify both new servers in LB and by SSH/login. Only then retire the entire previous groups (109051/109052); ambiguous mixed-group scale-in was rejected by approval review and must not be retried. Record public API errors across the cutover.
- [x] Install Worker runtime on replacement with production worker profile and existing real CLOVA key. Stop old Worker, start replacement, submit one synthetic meeting analysis and prove SUCCEEDED with valid source references.
- [x] Switch management SSH config to new Bastion; verify both App zones and Worker through the new management path before retiring old Bastion/Worker/builder.

### Task 4: Verification, documentation and review
Files: `docs/infra.md`, `infra/monitoring/`, existing deployment plan; protected evidence outputs.
- [x] Verify all active self-managed hosts report Ubuntu 24.04; real API approvals/group workflow, bucket unsigned 403, no public DB, both LB targets healthy.
- [x] Update Cloud Insight bootstrap targets to new hosts; retain ASG CPU events. Query actual metric data.
- [x] Update resource IDs, private IPs, OS, rollback artifacts and deletion/cost inventory; final Terraform plan must have zero changes.
- [ ] Open public UI and verify desktop/mobile meeting branches, preview badge for recording, draft persistence and group permissions; save screenshot.
- [ ] Run whole-branch fresh review, fix critical/important findings with regression evidence, push draft PR and attach. Shared main is not merged automatically.
