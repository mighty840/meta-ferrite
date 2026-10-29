SUMMARY = "ferrite device agent for embedded Linux"
DESCRIPTION = "Reports an embedded Linux device to ferrite-server: image \
identity, system metrics, reboot reasons and the health of selected systemd \
units. Configured in /etc/ferrite/agent.conf."

# ferrite-agent itself is MIT. The other licences belong to crates linked
# into the binary (for dual-licensed crates the MIT option is used):
# Unicode-3.0 (ICU data via url/idna), subtle (BSD-3-Clause, via the AES-CCM
# crates that seal reports with a device credential); with tls,
# rustls/ring/webpki (Apache-2.0, ISC, CDLA-Permissive-2.0 for the bundled
# Mozilla root certificates). Recheck with `cargo tree -e normal -f '{l}'`
# when SRCREV changes.
LICENSE = "MIT & BSD-3-Clause & Unicode-3.0${@bb.utils.contains('PACKAGECONFIG', 'tls', ' & Apache-2.0 & ISC & CDLA-Permissive-2.0', '', d)}"

require recipes-ferrite/include/ferrite-sdk.inc
require ${BPN}-crates.inc

inherit systemd useradd

# tls: HTTPS via rustls. Without it the agent speaks plain HTTP only, for
# example to a ferrite-gateway on the local network, and is about 1 MB smaller.
PACKAGECONFIG ??= "tls"
PACKAGECONFIG[tls] = "--features tls"

do_install:append() {
    install -d ${D}${sysconfdir}/ferrite
    install -m 0640 -g ferrite-agent ${S}/ferrite-agent/dist/agent.conf.example \
        ${D}${sysconfdir}/ferrite/agent.conf

    if ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'true', 'false', d)}; then
        install -d ${D}${systemd_system_unitdir}
        install -m 0644 ${S}/ferrite-agent/dist/ferrite-agent.service \
            ${D}${systemd_system_unitdir}/
        sed -i -e 's,/usr/bin/,${bindir}/,g' -e 's,/etc/,${sysconfdir}/,g' \
            ${D}${systemd_system_unitdir}/ferrite-agent.service
        install -d ${D}${nonarch_libdir}/sysusers.d
        install -m 0644 ${S}/ferrite-agent/dist/ferrite-agent.sysusers \
            ${D}${nonarch_libdir}/sysusers.d/ferrite-agent.conf
    fi
}

# The unit runs as this static system user. Created at build time so that
# read-only root filesystems work; matches dist/ferrite-agent.sysusers.
USERADD_PACKAGES = "${PN}"
USERADD_PARAM:${PN} = "--system --no-create-home --home-dir ${localstatedir}/lib/ferrite-agent \
    --shell ${base_sbindir}/nologin --user-group --comment 'ferrite device agent' ferrite-agent"

SYSTEMD_SERVICE:${PN} = "ferrite-agent.service"

# The agent ships with a placeholder server_url; it starts, fails to deliver
# and spools reports until agent.conf points at a real server.
CONFFILES:${PN} = "${sysconfdir}/ferrite/agent.conf"

FILES:${PN} += "${nonarch_libdir}/sysusers.d"
