# =============================================================================
# web_server_idf - LOCAL FORK with HTTPS support
#
# This overrides ESPHome's built-in component of the same name. ESPHome's
# `web_server` has no TLS support (feature request #2432, open since 2023), so
# this fork swaps httpd_start() for httpd_ssl_start() and serves the exact same
# handlers over TLS instead of plain HTTP.
#
# Forked from ESPHome 2025.11.2. Everything except begin() and the SSE send
# override is byte-identical to upstream. If ESPHome is upgraded, diff those
# two areas against the new upstream before assuming this still works.
#
# The certificate is generated on the build machine and embedded at compile
# time - see cert.h and the make-cert.sh script.
# =============================================================================

from esphome.components.esp32 import add_idf_sdkconfig_option
import esphome.config_validation as cv

CODEOWNERS = ["@dentra"]

CONFIG_SCHEMA = cv.All(
    cv.Schema({}),
    cv.only_on_esp32,
)


async def to_code(config):
    # Increase the maximum supported size of headers section in HTTP request packet to be processed by the server
    add_idf_sdkconfig_option("CONFIG_HTTPD_MAX_REQ_HDR_LEN", 1024)

    # Compile in ESP-IDF's HTTPS server. It depends on ESP_TLS_USING_MBEDTLS and
    # MBEDTLS_TLS_SERVER, both of which are on by default (ESP-IDF defaults to
    # MBEDTLS_TLS_SERVER_AND_CLIENT and ESPHome doesn't override it).
    add_idf_sdkconfig_option("CONFIG_ESP_HTTPS_SERVER_ENABLE", True)

    # A TLS handshake needs a large contiguous allocation. Measured free heap on
    # a plain ESP32 running WiFi + BLE + this server was ~64KB contiguous, which
    # fits one handshake comfortably but not several at once. Cap concurrent
    # sockets so the server refuses connections cleanly instead of running out
    # of memory mid-handshake - refusing is recoverable, heap exhaustion is not.
    add_idf_sdkconfig_option("CONFIG_LWIP_MAX_SOCKETS", 16)
