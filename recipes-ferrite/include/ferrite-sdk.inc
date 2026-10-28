# Shared by the recipes that build a binary from the ferrite-sdk workspace.
#
# To move to a newer ferrite-sdk commit, change SRCREV below and regenerate
# the crate lists of every recipe that requires this file:
#
#   bitbake -c update_crates ferrite-agent ferrite-gateway

HOMEPAGE = "https://github.com/mighty840/ferrite-sdk"
BUGTRACKER = "https://github.com/mighty840/ferrite-sdk/issues"

LIC_FILES_CHKSUM = "file://LICENSE;md5=037172940eb9078f2002044edf36809a"

SRC_URI = "git://github.com/mighty840/ferrite-sdk.git;protocol=https;branch=main"
SRCREV = "d6f4704af1f951677daa7a7422bf51388bd69a5e"
PV = "0.1.0+git"

S = "${WORKDIR}/git"

inherit cargo cargo-update-recipe-crates

# Each binary is one member of the ferrite-sdk Cargo workspace. The lock file
# lives at the workspace root and covers every member, so the crate list holds
# the whole workspace's dependencies: cargo needs all of them vendored to
# resolve the workspace offline, but compiles only what the binary uses.
CARGO_SRC_DIR = "${BPN}"
CARGO_LOCK_PATH = "${S}/Cargo.lock"

# The repository also contains firmware examples with their own Cargo.lock
# files. do_update_crates collects every Cargo.lock below CARGO_LOCK_SRC_DIR,
# so point it at a copy of the workspace lock file only.
CARGO_LOCK_SRC_DIR = "${WORKDIR}/workspace-lock"
do_update_crates[prefuncs] += "ferrite_stage_workspace_lock"
python ferrite_stage_workspace_lock() {
    import shutil
    dest = d.getVar('CARGO_LOCK_SRC_DIR')
    bb.utils.remove(dest, recurse=True)
    bb.utils.mkdirhier(dest)
    shutil.copy(d.getVar('CARGO_LOCK_PATH'), dest)
}

# Cargo features are selected through PACKAGECONFIG only.
CARGO_BUILD_FLAGS += "--no-default-features"

# Cargo.lock format v4 needs cargo >= 1.78 and some locked crates use the 2024
# edition (cargo >= 1.85). Fail with a useful hint rather than a cargo error.
FERRITE_MIN_RUST_VERSION ?= "1.85"
python () {
    rustver = (d.getVar('PREFERRED_VERSION_rust-native') or d.getVar('RUSTVERSION') or '').rstrip('%')
    minver = d.getVar('FERRITE_MIN_RUST_VERSION')
    if rustver and bb.utils.vercmp_string_op(rustver, minver, '<'):
        raise bb.parse.SkipRecipe(
            'ferrite-sdk needs Rust >= %s but this build uses %s. On scarthgap, add the '
            'meta-lts-mixins layer (branch scarthgap/rust) from git.yoctoproject.org.'
            % (minver, rustver))
}
