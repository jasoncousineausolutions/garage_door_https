#!/bin/bash
# =============================================================================
# Compile and flash the garage door firmware.
#
# Usage:
#   ./reflash.sh                  flash over the network (OTA)
#   ./reflash.sh /dev/ttyACM0     flash over USB
#   ./reflash.sh usb              same thing, auto-detects the port
#
# The ESP32-S3 board uses a CH343 bridge which appears as /dev/ttyACM0.
# The older ESP32 board used a CH340, which appeared as /dev/ttyUSB0.
# "usb" picks whichever is present.
# =============================================================================

set -e

VENV_DIR="esphome-venv"

# Which config to build. Change this line when switching boards - and note it
# prints below before doing anything, because a stale value here silently
# flashed the wrong config for two weeks once.
YAML_FILE="garage_door.yaml"

# ---------------------------------------------------------------------------

cd "$(dirname "$0")"

if [ ! -f "$YAML_FILE" ]; then
    echo "ERROR: $YAML_FILE not found in $(pwd)"
    exit 1
fi

DEVICE_ARG=""
case "$1" in
    "")
        TARGET="network (OTA)"
        ;;
    usb|USB)
        if   [ -e /dev/ttyACM0 ]; then PORT=/dev/ttyACM0
        elif [ -e /dev/ttyUSB0 ]; then PORT=/dev/ttyUSB0
        else
            echo "ERROR: no /dev/ttyACM0 or /dev/ttyUSB0 found. Is the board plugged in?"
            exit 1
        fi
        DEVICE_ARG="--device $PORT"
        TARGET="$PORT"
        ;;
    *)
        if [ ! -e "$1" ]; then
            echo "ERROR: $1 does not exist."
            exit 1
        fi
        DEVICE_ARG="--device $1"
        TARGET="$1"
        ;;
esac

echo "Config: $YAML_FILE"
echo "Target: $TARGET"
echo

if [ ! -d "$VENV_DIR" ]; then
    echo "Creating virtual environment..."
    python3 -m venv "$VENV_DIR"
fi

source "$VENV_DIR/bin/activate"

if ! command -v esphome &> /dev/null; then
    echo "Installing ESPHome..."
    pip install esphome
fi

echo "Running ESPHome..."
esphome run "$YAML_FILE" $DEVICE_ARG

deactivate

echo
echo "Done. If the upload failed, hold BOOT while plugging the board in to force"
echo "the bootloader, then run again."
