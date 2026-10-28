SUMMARY = "ferrite field observability"
DESCRIPTION = "Installs the ferrite device agent and the ferrite edge \
gateway. Devices that only report themselves need just ferrite-agent."

inherit packagegroup

PACKAGES = "${PN} ${PN}-agent ${PN}-gateway"

RDEPENDS:${PN} = "${PN}-agent ${PN}-gateway"
RDEPENDS:${PN}-agent = "ferrite-agent"
RDEPENDS:${PN}-gateway = "ferrite-gateway"

SUMMARY:${PN}-agent = "ferrite device agent"
SUMMARY:${PN}-gateway = "ferrite edge gateway"
