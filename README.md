# meta-ferrite

Yocto Project / OpenEmbedded layer for [ferrite](https://github.com/mighty840/ferrite-sdk),
field observability for embedded devices. It packages the two ferrite programs
that run on embedded Linux:

| Recipe | What it is |
|---|---|
| `ferrite-agent` | Device agent. Reports the Linux device it runs on to ferrite-server: image identity, system metrics, reboot reasons, systemd unit health (for example the CODESYS Control runtime). See the [Linux agent guide](https://mighty840.github.io/ferrite-sdk/guide/linux-agent). |
| `ferrite-gateway` | Edge gateway. Collects data from microcontrollers running the ferrite SDK over USB serial, BLE and an HTTP ingest port, buffers it while offline and forwards it to ferrite-server. |
| `packagegroup-ferrite` | Both of the above (`packagegroup-ferrite-agent` and `packagegroup-ferrite-gateway` pull in one each). |

Most devices need only the agent:

```
IMAGE_INSTALL:append = " ferrite-agent"
```

The recipes build the binaries from a pinned commit of the ferrite-sdk
repository with oe-core's `cargo` class. All Rust crates are fetched by
bitbake from crates.io and listed with checksums in `*-crates.inc`, so builds
work offline from a download mirror like any other recipe.

## Compatibility and dependencies

| | |
|---|---|
| Yocto release | scarthgap (5.0 LTS) |
| Layers | `openembedded-core` (scarthgap); `meta-lts-mixins` branch `scarthgap/rust` |
| Rust | 1.85 or newer |
| Init system | systemd (units and sysusers files are installed only with the `systemd` distro feature) |
| Tested on | qemuarm64 (built and booted), qemux86-64 (parsed) |

ferrite-sdk needs Rust 1.85 or newer, while scarthgap ships Rust 1.75. The
Yocto Project's [meta-lts-mixins](https://git.yoctoproject.org/meta-lts-mixins/log/?h=scarthgap/rust)
layer (branch `scarthgap/rust`) provides a current Rust toolchain for
scarthgap. It is a recommendation, not a hard dependency, so that a newer Rust
from another source also works. Without one, the ferrite recipes are skipped
with a message saying so.

No other layers are needed. With the `ble` option, `ferrite-gateway` uses
BlueZ and D-Bus from oe-core.

## Adding the layer to your build

```
git clone https://github.com/mighty840/meta-ferrite.git
git clone -b scarthgap/rust https://git.yoctoproject.org/meta-lts-mixins
bitbake-layers add-layer meta-lts-mixins meta-ferrite
```

Then add `ferrite-agent`, `ferrite-gateway` or `packagegroup-ferrite` to your
image, for example in `local.conf` or your image recipe:

```
IMAGE_INSTALL:append = " ferrite-agent"
```

The services are enabled by default (`SYSTEMD_AUTO_ENABLE`).

## Quick start with kas

[kas](https://kas.readthedocs.io/) configurations in `kas/` build
`core-image-minimal` for QEMU with poky, systemd and both ferrite packages:

```
curl -fsSL -o kas-container https://raw.githubusercontent.com/siemens/kas/5.5/kas-container
chmod +x kas-container
./kas-container build kas/qemuarm64.yml        # or kas/qemux86-64.yml
./kas-container shell kas/qemuarm64.yml -c "runqemu qemuarm64 nographic slirp"
```

Log in as `root` without a password (the kas configurations build a
development image with `debug-tweaks`), then:

```
systemctl status ferrite-agent ferrite-gateway
ferrite-agent identity
```

Both services point at the placeholder `https://ferrite.example.com` until
configured; they keep running and buffer data meanwhile. A first build from an
empty cache took about 70 minutes on a 24-core machine and about 31 GB of disk
space (6.5 GB downloads, 4.6 GB shared state, 20 GB `tmp` with `rm_work`),
most of it for the Rust and LLVM toolchains.

## Configuring the agent

`ferrite-agent` reads `/etc/ferrite/agent.conf` (TOML, a conffile). The package
installs the commented example from ferrite-sdk; only `server_url` is required:

```toml
server_url = "https://ferrite.example.com"
api_key = "..."            # when the server sets INGEST_API_KEY
interval_secs = 60

# [[units]] tables go last: TOML assigns any key after them to the table.
[[units]]
name = "codesyscontrol.service"
key = "codesys"
```

To ship your own configuration, add a `ferrite-agent_%.bbappend` to your layer
that installs your file over the example:

```
FILESEXTRAPATHS:prepend := "${THISDIR}/files:"
SRC_URI += "file://agent.conf"

do_install:append() {
    install -m 0640 -g ferrite-agent ${WORKDIR}/agent.conf ${D}${sysconfdir}/ferrite/agent.conf
}
```

Keep secrets such as `api_key` out of images you distribute; provision them per
device instead. The file is readable by root and the `ferrite-agent` group only.

The agent runs as the unprivileged system user `ferrite-agent`, created at
build time by `useradd` (so read-only root filesystems work) and matching the
`sysusers.d` file shipped with it. State (boot tracking, the offline spool)
lives in `/var/lib/ferrite-agent`, which must be persistent storage.

### Image identity

The agent derives the firmware version and the numeric `build_id` that OTA
campaigns target from `/etc/os-release`: `BUILD_ID`, else `IMAGE_VERSION`, else
`VERSION_ID`. Poky does not write `BUILD_ID` to `os-release` by default. To
give every image build a distinct, increasing `build_id`, add it in your distro
or `local.conf` and set it per build, for example from CI:

```
OS_RELEASE_FIELDS:append:pn-os-release = " BUILD_ID"
BUILD_ID = "20261028153000"
```

`BUILD_ID` defaults to the build's `DATETIME`, but `os-release` is not rebuilt
just because the time changed, so set it explicitly. See
[Identity](https://mighty840.github.io/ferrite-sdk/guide/linux-agent#identity)
in the agent guide.

### Options

| `PACKAGECONFIG` | Default | Effect |
|---|---|---|
| `tls` | on | HTTPS via rustls. Without it the agent speaks plain HTTP only (for example to a gateway on the local network) and is about 1 MB smaller. |

## Configuring the gateway

`ferrite-gateway` reads `/etc/ferrite/gateway.env`, a systemd
`EnvironmentFile` (a conffile):

```
FERRITE_SERVER_URL=https://ferrite.example.com
INGEST_API_KEY=...
USB_PORTS=/dev/ferrite-nucleo-l4,/dev/ferrite-usb0
GATEWAY_ARGS=--ble
```

The service runs as the system user `ferrite-gateway` with the supplementary
group `dialout`. The offline buffer is `/var/lib/ferrite-gateway/buffer.db`.
By default the gateway also accepts chunks from Ethernet/Wi-Fi devices on HTTP
port 4001; pass `--http-ingest-port 0` in `GATEWAY_ARGS` to turn that off.

With the `usb` option the package installs udev rules
(`99-ferrite.rules` in `/usr/lib/udev/rules.d`, or `/lib/udev/rules.d` without
`usrmerge`) that create stable names such as
`/dev/ferrite-nucleo-l4` and `/dev/ferrite-usb0` for the boards ferrite
supports, accessible to the `dialout` group.

| `PACKAGECONFIG` | Default | Effect |
|---|---|---|
| `usb` | on | Serial port receivers (needs udev) and the udev rules |
| `ble` | on if `bluetooth` is in `DISTRO_FEATURES` | BLE scanning through BlueZ over D-Bus; pulls in `bluez5`. Enable scanning with `GATEWAY_ARGS=--ble`. |

## Licences

The ferrite programs are MIT licensed. Each recipe's `LICENSE` also lists the
licences of the Rust crates statically linked into its binary (for example
Apache-2.0, ISC and BSD-3-Clause from rustls, MPL-2.0 from serialport,
Unicode-3.0 from ICU data, CDLA-Permissive-2.0 for the bundled Mozilla root
certificates). Where a crate offers a choice, MIT is used.

## Updating to a newer ferrite-sdk

The ferrite-sdk commit is pinned in
`recipes-ferrite/include/ferrite-sdk.inc`. To update it:

1. Set `SRCREV` to the new commit (`git ls-remote https://github.com/mighty840/ferrite-sdk main`).
2. Regenerate the crate lists: `bitbake -c update_crates ferrite-agent ferrite-gateway`.
3. Check the crate licences (`cargo tree -e normal -f '{l}'` in ferrite-sdk) against the recipes' `LICENSE`.
4. Build an image and boot it.

## Continuous integration

The GitHub Actions workflow runs `bitbake -p` for both kas configurations and
`yocto-check-layer` on the layer. It does not build an image: from an empty
cache the build compiles a cross toolchain plus Rust and LLVM, which takes
several hours on a 4-core hosted runner and needs more disk space than the
runner offers, and the toolchain's shared-state cache (several GB) does not fit
well in the 10 GB GitHub Actions cache. A full build in CI is practical on a
self-hosted runner that keeps `DL_DIR` and `SSTATE_DIR` between runs; with a
warm cache, rebuilding after a ferrite-sdk update takes minutes.

## Contributing

Contributions are welcome as pull requests on
[GitHub](https://github.com/mighty840/meta-ferrite). Send patches as pull
requests against `main`; for bugs in the ferrite programs themselves, use the
[ferrite-sdk issues](https://github.com/mighty840/ferrite-sdk/issues).

Every commit needs a `Signed-off-by` line certifying the
[Developer Certificate of Origin](https://developercertificate.org/), matching
the commit author:

```
git commit -s      # adds: Signed-off-by: Your Name <you@example.com>
```

Maintainer: Sharang Parnerkar (GitHub: mighty840).

## License

MIT, see [LICENSE](LICENSE). Recipes are licensed under the same terms as the
layer; the software they build carries its own licences (see above).
