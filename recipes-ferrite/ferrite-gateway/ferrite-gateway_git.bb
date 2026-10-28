SUMMARY = "ferrite edge gateway"
DESCRIPTION = "Bridges microcontrollers running the ferrite SDK to \
ferrite-server: reads chunks from USB serial ports, BLE and an HTTP ingest \
port, buffers them in SQLite while offline and forwards them. Configured in \
/etc/ferrite/gateway.env."

# ferrite-gateway itself is MIT. The other licences belong to crates linked
# into the binary (for dual-licensed crates the MIT option is used):
# Unicode-3.0 (ICU data via url/idna), rustls/ring/webpki (Apache-2.0, ISC,
# BSD-3-Clause, CDLA-Permissive-2.0 for the bundled Mozilla root
# certificates), sync_wrapper (Apache-2.0) and, with usb, serialport
# (MPL-2.0). Recheck with `cargo tree -e normal -f '{l}'` when SRCREV changes.
LICENSE = "MIT & Apache-2.0 & ISC & BSD-3-Clause & Unicode-3.0 & CDLA-Permissive-2.0${@bb.utils.contains('PACKAGECONFIG', 'usb', ' & MPL-2.0', '', d)}"

require recipes-ferrite/include/ferrite-sdk.inc
require ${BPN}-crates.inc

SRC_URI += " \
    file://ferrite-gateway.service \
    file://gateway.env \
"

inherit pkgconfig systemd useradd

# libsqlite3-sys links the system SQLite (cargo_common sets
# LIBSQLITE3_SYS_USE_PKG_CONFIG) instead of its bundled copy.
DEPENDS += "sqlite3"

# usb: serial ports (serialport crate, needs libudev).
# ble: BLE scanning through BlueZ over D-Bus (btleplug).
PACKAGECONFIG ??= "usb ${@bb.utils.contains('DISTRO_FEATURES', 'bluetooth', 'ble', '', d)}"
PACKAGECONFIG[usb] = "--features usb,,udev"
PACKAGECONFIG[ble] = "--features ble,,dbus,bluez5"

do_install:append() {
    install -d ${D}${sysconfdir}/ferrite
    install -m 0640 -g ferrite-gateway ${WORKDIR}/gateway.env ${D}${sysconfdir}/ferrite/gateway.env

    if ${@bb.utils.contains('DISTRO_FEATURES', 'systemd', 'true', 'false', d)}; then
        install -d ${D}${systemd_system_unitdir}
        install -m 0644 ${WORKDIR}/ferrite-gateway.service ${D}${systemd_system_unitdir}/
        sed -i -e 's,@BINDIR@,${bindir},g' -e 's,@SYSCONFDIR@,${sysconfdir},g' \
            -e 's,@LOCALSTATEDIR@,${localstatedir},g' \
            ${D}${systemd_system_unitdir}/ferrite-gateway.service
    fi

    if ${@bb.utils.contains('PACKAGECONFIG', 'usb', 'true', 'false', d)}; then
        # Stable /dev/ferrite-* names for the boards ferrite-sdk supports,
        # taken from upstream. Upstream makes the nodes world-writable (0666);
        # here only the dialout group gets access, which the service joins.
        install -d ${D}${nonarch_base_libdir}/udev/rules.d
        sed -e 's/MODE="0666"/GROUP="dialout", MODE="0660"/' \
            ${S}/deploy/rpi-gateway/99-ferrite.rules \
            > ${D}${nonarch_base_libdir}/udev/rules.d/99-ferrite.rules
        if grep -q '0666' ${D}${nonarch_base_libdir}/udev/rules.d/99-ferrite.rules; then
            bbfatal "99-ferrite.rules still contains world-writable device modes"
        fi
    fi
}

USERADD_PACKAGES = "${PN}"
USERADD_PARAM:${PN} = "--system --no-create-home --home-dir ${localstatedir}/lib/ferrite-gateway \
    --shell ${base_sbindir}/nologin --user-group --comment 'ferrite edge gateway' ferrite-gateway"

SYSTEMD_SERVICE:${PN} = "ferrite-gateway.service"

CONFFILES:${PN} = "${sysconfdir}/ferrite/gateway.env"

FILES:${PN} += "${nonarch_base_libdir}/udev/rules.d"
