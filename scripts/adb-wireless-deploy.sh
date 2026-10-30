#!/usr/bin/env bash
#
# adb-wireless-deploy (adb-deploy)
# Universal tool to pair, connect, and install (-r) APKs over Wi-Fi Wireless Debugging.
# Works in ANY Android app repository or with any standalone APK file.
#

set -e

# Colors
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

CONFIG_DIR="${HOME}/.config/adb-deploy"
CONFIG_FILE="${CONFIG_DIR}/last_device.conf"

mkdir -p "${CONFIG_DIR}"

log_info() {
    echo -e "${CYAN}[INFO]${NC} $1"
}

log_success() {
    echo -e "${GREEN}[SUCCESS]${NC} $1"
}

log_warn() {
    echo -e "${YELLOW}[WARN]${NC} $1"
}

log_error() {
    echo -e "${RED}[ERROR]${NC} $1"
}

show_help() {
    echo -e "${BOLD}adb-wireless-deploy${NC} - Universal Wireless ADB Tool

${BOLD}USAGE:${NC}
  adb-deploy [OPTIONS] [PATH_TO_APK]

${BOLD}OPTIONS:${NC}
  -p, --pair <IP:PORT> <CODE>   Pair a new device using IP:Port and 6-digit pairing code
  -c, --connect <IP:PORT>       Connect to wireless debugging IP:Port
  -a, --apk <FILE>              Path to the APK file to install
  -b, --build                   Run './gradlew assembleDebug' before installing
  -l, --launch [PACKAGE]        Launch the app after installation
  -s, --serial <DEVICE_SERIAL>  Target specific ADB device (useful if multiple connected)
  -d, --disconnect              Disconnect all wireless ADB connections
  -i, --info                    Display connected devices
  -h, --help                    Show this help message

${BOLD}EXAMPLES:${NC}
  # Interactive mode (Guides you step-by-step, auto-finds/builds APK):
  adb-deploy

  # Direct pair + connect + install current project debug build:
  adb-deploy -p 192.168.1.50:37123 123456 -c 192.168.1.50:41235

  # Connect already paired device and install:
  adb-deploy -c 192.168.1.50:41235

  # Build first and then install on connected device:
  adb-deploy --build -l

  # Install a specific APK file:
  adb-deploy path/to/app-release.apk
"
}

check_adb() {
    if ! command -v adb &> /dev/null; then
        log_error "'adb' command not found. Please install Android Platform Tools and add 'adb' to your PATH."
        exit 1
    fi
}

load_last_device() {
    if [[ -f "${CONFIG_FILE}" ]]; then
        # shellcheck disable=SC1090
        source "${CONFIG_FILE}"
    fi
}

save_last_device() {
    local ip="$1"
    cat << EOF > "${CONFIG_FILE}"
LAST_IP="${ip}"
EOF
}

get_connected_devices() {
    # Returns list of devices in "device" state (authorized)
    adb devices | awk 'NR>1 && $2=="device" {print $1}'
}

do_pair() {
    local pair_addr="$1"
    local pair_code="$2"

    log_info "Pairing with ${pair_addr} using pairing code..."
    local pair_output
    if pair_output=$(adb pair "${pair_addr}" "${pair_code}" 2>&1); then
        log_success "Pairing output: ${pair_output}"
        # Save IP portion for convenience
        local ip="${pair_addr%%:*}"
        save_last_device "${ip}"
        return 0
    else
        log_error "Pairing failed: ${pair_output}"
        log_warn "Tips: Check that your phone and computer are on the same Wi-Fi network,"
        log_warn "and that the 6-digit code has not expired on the phone."
        return 1
    fi
}

do_connect() {
    local connect_addr="$1"

    log_info "Connecting to ${connect_addr}..."
    local connect_output
    connect_output=$(adb connect "${connect_addr}" 2>&1)

    if echo "${connect_output}" | grep -qi "connected to"; then
        log_success "${connect_output}"
        local ip="${connect_addr%%:*}"
        save_last_device "${ip}"
        TARGET_DEVICE="${connect_addr}"
        return 0
    else
        log_error "Connection failed: ${connect_output}"
        return 1
    fi
}

find_apk() {
    local custom_apk="$1"

    if [[ -n "${custom_apk}" && -f "${custom_apk}" ]]; then
        echo "${custom_apk}"
        return 0
    fi

    # Check Gradle default debug output path
    local standard_debug_apk
    standard_debug_apk=$(find . -maxdepth 6 -type f -path "*/build/outputs/apk/debug/*.apk" 2>/dev/null | head -n 1)
    if [[ -n "${standard_debug_apk}" && -f "${standard_debug_apk}" ]]; then
        echo "${standard_debug_apk}"
        return 0
    fi

    # Look for any apk sorted by newest modification time, excluding intermediates/caches
    local any_apk
    any_apk=$(find . -maxdepth 6 -type f -name "*.apk" ! -path "*/intermediates/*" 2>/dev/null | head -n 1)
    if [[ -n "${any_apk}" && -f "${any_apk}" ]]; then
        echo "${any_apk}"
        return 0
    fi

    echo ""
}

extract_package_name() {
    local apk_file="$1"

    # Try gradle files in current directory first
    if [[ -f "app/build.gradle.kts" ]]; then
        local pkg
        pkg=$(grep -Po 'applicationId\s*=\s*"\K[^"]+' app/build.gradle.kts 2>/dev/null || true)
        if [[ -z "${pkg}" ]]; then
            pkg=$(grep -Po 'namespace\s*=\s*"\K[^"]+' app/build.gradle.kts 2>/dev/null || true)
        fi
        if [[ -n "${pkg}" ]]; then
            echo "${pkg}"
            return 0
        fi
    fi

    if [[ -f "app/build.gradle" ]]; then
        local pkg
        pkg=$(grep -Po 'applicationId\s+["'"'"']\K[^"'"'"']+' app/build.gradle 2>/dev/null || true)
        if [[ -n "${pkg}" ]]; then
            echo "${pkg}"
            return 0
        fi
    fi

    # Fallback: inspect AndroidManifest.xml package attribute
    if [[ -f "app/src/main/AndroidManifest.xml" ]]; then
        local pkg
        pkg=$(grep -Po 'package\s*=\s*"\K[^"]+' app/src/main/AndroidManifest.xml 2>/dev/null || true)
        if [[ -n "${pkg}" ]]; then
            echo "${pkg}"
            return 0
        fi
    fi

    echo ""
}

# --- Parse Arguments ---

PAIR_ARG=""
PAIR_CODE_ARG=""
CONNECT_ARG=""
APK_ARG=""
DO_BUILD=false
DO_LAUNCH=false
LAUNCH_PACKAGE=""
TARGET_DEVICE=""

while [[ $# -gt 0 ]]; do
    case "$1" in
        -p|--pair)
            PAIR_ARG="$2"
            PAIR_CODE_ARG="$3"
            shift 3
            ;;
        -c|--connect)
            CONNECT_ARG="$2"
            shift 2
            ;;
        -a|--apk)
            APK_ARG="$2"
            shift 2
            ;;
        -b|--build)
            DO_BUILD=true
            shift
            ;;
        -l|--launch)
            DO_LAUNCH=true
            if [[ -n "$2" && "$2" != -* ]]; then
                LAUNCH_PACKAGE="$2"
                shift 2
            else
                shift
            fi
            ;;
        -s|--serial)
            TARGET_DEVICE="$2"
            shift 2
            ;;
        -d|--disconnect)
            check_adb
            log_info "Disconnecting all wireless devices..."
            adb disconnect
            exit 0
            ;;
        -i|--info)
            check_adb
            echo -e "${BOLD}Current ADB Devices:${NC}"
            adb devices -l
            exit 0
            ;;
        -h|--help)
            show_help
            exit 0
            ;;
        *)
            if [[ -f "$1" && "$1" == *.apk ]]; then
                APK_ARG="$1"
            elif [[ -z "${APK_ARG}" ]]; then
                APK_ARG="$1"
            else
                log_error "Unknown argument: $1"
                show_help
                exit 1
            fi
            shift
            ;;
    esac
done

check_adb
load_last_device

# --- Step 1: Wireless Connection ---

# If explicit pair arguments provided
if [[ -n "${PAIR_ARG}" && -n "${PAIR_CODE_ARG}" ]]; then
    do_pair "${PAIR_ARG}" "${PAIR_CODE_ARG}"
fi

# If explicit connect argument provided
if [[ -n "${CONNECT_ARG}" ]]; then
    do_connect "${CONNECT_ARG}"
fi

# If no device specified or connected yet, enter interactive connection setup
CONNECTED_DEVICES=$(get_connected_devices)
DEVICE_COUNT=$(echo "${CONNECTED_DEVICES}" | grep -c . || true)

if [[ -z "${TARGET_DEVICE}" ]]; then
    if [[ ${DEVICE_COUNT} -eq 1 ]]; then
        TARGET_DEVICE=$(echo "${CONNECTED_DEVICES}" | head -n 1)
        log_info "Using already connected device: ${BOLD}${TARGET_DEVICE}${NC}"
    elif [[ ${DEVICE_COUNT} -gt 1 ]]; then
        log_info "Multiple devices connected:"
        select dev in ${CONNECTED_DEVICES}; do
            if [[ -n "${dev}" ]]; then
                TARGET_DEVICE="${dev}"
                break
            fi
        done
    else
        # No connected device found, interactive prompt
        echo ""
        echo -e "${BOLD}=== Android Wireless Debugging Setup ===${NC}"
        echo "1. On your Android phone (Android 11+):"
        echo "   Go to: Settings -> Developer options -> Wireless debugging"
        echo "   Ensure 'Wireless debugging' is toggled ON."
        echo ""
        echo "Select an action:"
        echo "  [1] Connect to an already paired device (Enter IP:ConnectPort)"
        echo "  [2] Pair a new device first (Enter IP:PairPort + 6-digit Code)"
        echo ""

        read -rp "Choice [1/2] (default: 1): " CHOICE
        CHOICE="${CHOICE:-1}"

        DEFAULT_IP="${LAST_IP:-192.168.1.}"

        if [[ "${CHOICE}" == "2" ]]; then
            echo ""
            echo "Tap 'Pair device with pairing code' on your phone."
            read -rp "Enter device IP [default: ${DEFAULT_IP}]: " INPUT_IP
            INPUT_IP="${INPUT_IP:-${DEFAULT_IP}}"

            read -rp "Enter pairing PORT (from 'Pair with pairing code' dialog): " PAIR_PORT
            read -rp "Enter 6-digit PAIRING CODE: " PAIR_CODE

            do_pair "${INPUT_IP}:${PAIR_PORT}" "${PAIR_CODE}"

            echo ""
            echo "Now look at the main 'Wireless debugging' screen for the connect port."
            read -rp "Enter connect PORT (from main Wireless debugging screen): " CONNECT_PORT
            do_connect "${INPUT_IP}:${CONNECT_PORT}"
        else
            echo ""
            echo "Look at the main 'Wireless debugging' screen (IP address & Port)."
            read -rp "Enter device IP [default: ${DEFAULT_IP}]: " INPUT_IP
            INPUT_IP="${INPUT_IP:-${DEFAULT_IP}}"

            read -rp "Enter connect PORT: " CONNECT_PORT
            do_connect "${INPUT_IP}:${CONNECT_PORT}"
        fi
    fi
fi

# Verify device is responsive
if [[ -n "${TARGET_DEVICE}" ]]; then
    DEVICE_STATE=$(adb -s "${TARGET_DEVICE}" get-state 2>/dev/null || true)
    if [[ "${DEVICE_STATE}" != "device" ]]; then
        log_error "Device '${TARGET_DEVICE}' is not authorized or offline (state: ${DEVICE_STATE})."
        log_warn "Please check your Android screen and tap 'Always allow on this network'."
        exit 1
    fi
fi

# --- Step 2: Build / Locate APK ---

if ${DO_BUILD}; then
    if [[ -x "./gradlew" ]]; then
        log_info "Building debug APK with './gradlew assembleDebug'..."
        ./gradlew assembleDebug
    elif [[ -f "gradlew" ]]; then
        chmod +x gradlew
        log_info "Building debug APK with './gradlew assembleDebug'..."
        ./gradlew assembleDebug
    else
        log_warn "No ./gradlew found in current directory. Skipping build step."
    fi
fi

FOUND_APK=$(find_apk "${APK_ARG}")

if [[ -z "${FOUND_APK}" ]]; then
    # If in gradle project, ask to build
    if [[ -f "./gradlew" ]]; then
        read -rp "No APK found. Build with './gradlew assembleDebug' now? [Y/n]: " BUILD_CHOICE
        BUILD_CHOICE="${BUILD_CHOICE:-Y}"
        if [[ "${BUILD_CHOICE}" =~ ^[Yy]$ ]]; then
            chmod +x gradlew 2>/dev/null || true
            ./gradlew assembleDebug
            FOUND_APK=$(find_apk "")
        fi
    fi
fi

if [[ -z "${FOUND_APK}" || ! -f "${FOUND_APK}" ]]; then
    read -rp "Enter full path to the .apk file: " FOUND_APK
fi

if [[ ! -f "${FOUND_APK}" ]]; then
    log_error "APK file not found: '${FOUND_APK}'"
    exit 1
fi

# --- Step 3: Install APK (-r) ---

log_info "Target device: ${BOLD}${TARGET_DEVICE}${NC}"
log_info "Installing APK: ${BOLD}${FOUND_APK}${NC} (options: -r -t -d)"

ADB_TARGET_CMD=(adb)
if [[ -n "${TARGET_DEVICE}" ]]; then
    ADB_TARGET_CMD=(adb -s "${TARGET_DEVICE}")
fi

INSTALL_OUTPUT=$("${ADB_TARGET_CMD[@]}" install -r -t -d "${FOUND_APK}" 2>&1)
INSTALL_STATUS=$?

if [[ ${INSTALL_STATUS} -eq 0 ]] && echo "${INSTALL_OUTPUT}" | grep -q "Success"; then
    log_success "Application successfully installed on ${TARGET_DEVICE}!"
else
    log_error "Installation failed!"
    echo "${INSTALL_OUTPUT}"
    exit 1
fi

# --- Step 4: Optional Launch ---

if ${DO_LAUNCH}; then
    if [[ -z "${LAUNCH_PACKAGE}" ]]; then
        LAUNCH_PACKAGE=$(extract_package_name "${FOUND_APK}")
    fi

    if [[ -n "${LAUNCH_PACKAGE}" ]]; then
        log_info "Launching app: ${LAUNCH_PACKAGE}..."
        "${ADB_TARGET_CMD[@]}" shell monkey -p "${LAUNCH_PACKAGE}" -c android.intent.category.LAUNCHER 1 > /dev/null 2>&1 || true
        log_success "App launched!"
    else
        log_warn "Could not auto-detect package name. You can launch manually or pass -l <package_name>."
    fi
fi

echo ""
echo -e "${GREEN}${BOLD}✓ All done!${NC}"
