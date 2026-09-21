#ifdef USE_ESP32

#include <cstdarg>
#include <memory>
#include <cstring>
#include <cctype>
#include <cinttypes>

#include "esphome/core/helpers.h"
#include "esphome/core/log.h"

#include "esp_tls_crypto.h"

// FORK: TLS support. See __init__.py for why this component is forked.
#include <esp_https_server.h>
#include "cert.h"
#include <freertos/FreeRTOS.h>
#include <freertos/task.h>

#include "utils.h"
#include "web_server_idf.h"

#ifdef USE_WEBSERVER_OTA
#include <multipart_parser.h>
#include "multipart.h"  // For parse_multipart_boundary and other utils
#endif

#ifdef USE_WEBSERVER
#include "esphome/components/web_server/web_server.h"
#include "esphome/components/web_server/list_entities.h"
#endif  // USE_WEBSERVER

// Include socket headers after Arduino headers to avoid IPADDR_NONE/INADDR_NONE macro conflicts
#include <cerrno>
#include <sys/socket.h>

namespace esphome {
namespace web_server_idf {

#ifndef HTTPD_409
#define HTTPD_409 "409 Conflict"
#endif

#define CRLF_STR "\r\n"
#define CRLF_LEN (sizeof(CRLF_STR) - 1)

static const char *const TAG = "web_server_idf";

// DIAGNOSTIC (temporary) --------------------------------------------------
// The server has been observed accepting a TCP connection and then resetting
// it immediately, with NOTHING logged - curl gets "Connection reset by peer"
// right after Client Hello, while the device is otherwise healthy (heap flat,
// loop time normal, BLE working, sensors updating).
//
// The suspicion is that session slots are being leaked: esp_http_server keeps
// a fixed table of them (default 7, of which 3 are reserved, leaving 4 for
// clients). If slots are left marked in-use with dead sockets behind them, the
// listener still accepts at the TCP layer but has nowhere to put the
// connection, and drops it below the level anything logs.
//
// This reports the table so we can watch it fill. Call from the YAML:
//   interval:
//     - interval: 30s
//       then:
//         - lambda: 'esphome::web_server_idf::log_session_stats();'
//
// Remove once the question is settled.
static httpd_handle_t g_server_handle = nullptr;

void log_session_stats() {
  if (g_server_handle == nullptr) {
    ESP_LOGW(TAG, "sessions: server not running");
    return;
  }
  int client_fds[16];
  size_t fds = sizeof(client_fds) / sizeof(client_fds[0]);
  esp_err_t err = httpd_get_client_list(g_server_handle, &fds, client_fds);
  if (err != ESP_OK) {
    ESP_LOGW(TAG, "sessions: httpd_get_client_list failed: %s", esp_err_to_name(err));
    return;
  }
  std::string list;
  for (size_t i = 0; i < fds; i++) {
    if (i != 0)
      list += ",";
    list += std::to_string(client_fds[i]);
  }
  ESP_LOGI(TAG, "sessions: %u in use [%s]", (unsigned) fds, list.c_str());
}
// end DIAGNOSTIC ----------------------------------------------------------

// Global instance to avoid guard variable (saves 8 bytes)
// This is initialized at program startup before any threads
namespace {
// NOLINTNEXTLINE(cppcoreguidelines-avoid-non-const-global-variables)
DefaultHeaders default_headers_instance;
}  // namespace

DefaultHeaders &DefaultHeaders::Instance() { return default_headers_instance; }

namespace {
// Non-blocking send function to prevent watchdog timeouts when TCP buffers are full
/**
 * Sends data on a socket in non-blocking mode.
 *
 * @param hd      HTTP server handle (unused).
 * @param sockfd  Socket file descriptor.
 * @param buf     Buffer to send.
 * @param buf_len Length of buffer.
 * @param flags   Flags for send().
 * @return
 *   - Number of bytes sent on success.
 *   - HTTPD_SOCK_ERR_INVALID if buf is nullptr.
 *   - HTTPD_SOCK_ERR_TIMEOUT if the send buffer is full (EAGAIN/EWOULDBLOCK).
 *   - HTTPD_SOCK_ERR_FAIL for other errors.
 */
// FORK: unused now - the TLS build must not override the session send function.
// Kept (rather than deleted) so the diff against upstream stays small.
[[maybe_unused]] int nonblocking_send(httpd_handle_t hd, int sockfd, const char *buf, size_t buf_len, int flags) {
  if (buf == nullptr) {
    return HTTPD_SOCK_ERR_INVALID;
  }

  // Use MSG_DONTWAIT to prevent blocking when TCP send buffer is full
  int ret = send(sockfd, buf, buf_len, flags | MSG_DONTWAIT);
  if (ret < 0) {
    if (errno == EAGAIN || errno == EWOULDBLOCK) {
      // Buffer full - retry later
      return HTTPD_SOCK_ERR_TIMEOUT;
    }
    // Real error
    ESP_LOGD(TAG, "send error: errno %d", errno);
    return HTTPD_SOCK_ERR_FAIL;
  }
  return ret;
}
}  // namespace

void AsyncWebServer::safe_close_with_shutdown(httpd_handle_t hd, int sockfd) {
  // CRITICAL: Shut down receive BEFORE closing to prevent lwIP race conditions
  //
  // The race condition occurs because close() initiates lwIP teardown while
  // the TCP/IP thread can still receive packets, causing assertions when
  // recv_tcp() sees partially-torn-down state.
  //
  // By shutting down receive first, we tell lwIP to stop accepting new data BEFORE
  // the teardown begins, eliminating the race window. We only shutdown RD (not RDWR)
  // to allow the FIN packet to be sent cleanly during close().
  //
  // Note: This function may be called with an already-closed socket if the network
  // stack closed it. In that case, shutdown() will fail but close() is safe to call.
  //
  // See: https://github.com/esphome/esphome-webserver/issues/163

  // Attempt shutdown - ignore errors as socket may already be closed
  shutdown(sockfd, SHUT_RD);

  // Always close - safe even if socket is already closed by network stack
  close(sockfd);
}

void AsyncWebServer::end() {
  if (this->server_) {
    // FORK: must be httpd_ssl_stop, not httpd_stop. The SSL variant frees the
    // TLS context and server certificate state as well as stopping the server;
    // plain httpd_stop would leave those allocated and leak heap on restart.
    httpd_ssl_stop(this->server_);
    this->server_ = nullptr;
    g_server_handle = nullptr;   // DIAGNOSTIC
  }
}

void AsyncWebServer::set_lru_purge_enable(bool enable) {
  if (this->lru_purge_enable_ == enable) {
    return;  // No change needed
  }
  this->lru_purge_enable_ = enable;
  // If server is already running, restart it with new config
  if (this->server_) {
    this->end();
    this->begin();
  }
}

void AsyncWebServer::begin() {
  if (this->server_) {
    this->end();
  }
  // FORK: HTTPS instead of plain HTTP.
  //
  // httpd_ssl_config_t wraps the ordinary httpd_config_t as a member named
  // `httpd`, so every option below is the same as upstream, just one level
  // deeper. httpd_ssl_start() then layers mbedTLS underneath the identical
  // request handlers - nothing else in this file needs to know about TLS.
  httpd_ssl_config_t config = HTTPD_SSL_CONFIG_DEFAULT();

  // Note: port_secure, not httpd.server_port. httpd_ssl_start() copies this
  // into httpd.server_port itself depending on transport_mode; setting the
  // inner one directly gets overwritten.
  config.port_secure = this->port_;
  config.transport_mode = HTTPD_SSL_TRANSPORT_SECURE;

  config.httpd.uri_match_fn = [](const char * /*unused*/, const char * /*unused*/, size_t /*unused*/) {
    return true;
  };
  // FORK: always on, rather than only when something asks for it (upstream
  // enables this from the captive portal to cope with probe bursts).
  //
  // Without it, a new connection arriving with no free session slot is simply
  // refused - the listener accepts at the TCP layer and then resets, logging
  // nothing, because the failure is below anything the application sees. That
  // is exactly the observed failure: curl gets "Connection reset by peer"
  // straight after Client Hello while the device is otherwise healthy.
  //
  // And slots do leak. Session logging showed one fd held continuously for
  // ~20 hours: event-source connections are long-lived by design so the
  // receive timeout never applies to them, and a client that vanishes leaving
  // a half-open socket is never noticed, because ESPHome only detects a dead
  // session when a send to it fails. With only 4 usable slots (7 minus 3
  // reserved internally), a few of those and the server stops accepting.
  //
  // With LRU purging the leak stops mattering: the least-recently-used session
  // is evicted to make room instead of the new connection being refused. A
  // stale session is a far better thing to lose than a live one.
  config.httpd.lru_purge_enable = true;

  // FORK: socket timeouts. These default to 0, meaning "block forever".
  //
  // Upstream can live with that because it installs a non-blocking send
  // override (see the note further down about why we can't). Without either,
  // a single write to a client that has gone away - a browser tab closed
  // mid-stream, a phone that dropped off WiFi - blocks the HTTP task
  // permanently. The device stays otherwise healthy (memory fine, sockets
  // fine, sensors still updating) but never answers another request, which is
  // exactly the failure this had: works for a while, then goes silent.
  //
  // With a timeout the stuck write fails, the task is released, and the server
  // carries on. 5 seconds is far longer than any real response needs.
  config.httpd.send_wait_timeout = 5;

  // recv_wait_timeout doubles as the idle timeout on a kept-alive connection,
  // because the server waits in recv() for the next request. Two competing
  // pressures:
  //
  //  - it must be LONGER than a client's polling interval, or the server closes
  //    connections just as the client wants to reuse one (the app polls every
  //    1s, and its pool discards after 25s)
  //  - it must be SHORT enough that a client which vanished without closing -
  //    a phone that slept or walked out of range - releases its slot promptly.
  //    At 30s the server was found wedged after several hours: WiFi up, API
  //    responding, memory flat, but nothing accepted on 443 and no log entry
  //    when a connection was attempted, i.e. blocked before accept.
  //
  // 10s satisfies both.
  config.httpd.recv_wait_timeout = 10;

  // Left at the ESP-IDF default (7, of which 3 are reserved internally, leaving
  // 4 for clients).
  //
  // This was raised to 10 to give headroom against stranded connections. After
  // that change the failure mode got WORSE rather than better: instead of the
  // HTTPS port going quiet while the API still worked, both ports began
  // accepting TCP and then immediately dropping it, which looks like memory
  // starvation rather than a blocked task. Each concurrent TLS session costs
  // internal RAM for its control structures even though the bulk of the
  // handshake lives in PSRAM, so more sessions is not free.
  //
  // Raise it again only with evidence, and watch free heap if you do.
  // config.httpd.max_open_sockets = 10;

  // FORK: upstream sets config.close_fn = safe_close_with_shutdown here to work
  // around an lwIP teardown race. We deliberately do NOT, because
  // httpd_ssl_start() installs its own open/close hooks to set up and free the
  // per-connection TLS context. Overriding close_fn would leak that context and
  // most likely crash on the second connection.

  // Self-signed certificate, generated by make-cert.sh and embedded at compile
  // time. Long expiry, so there is no renewal to automate.
  config.servercert = (const uint8_t *) GARAGE_SERVER_CERT_PEM;
  config.servercert_len = sizeof(GARAGE_SERVER_CERT_PEM);
  config.prvtkey_pem = (const uint8_t *) GARAGE_SERVER_KEY_PEM;
  config.prvtkey_len = sizeof(GARAGE_SERVER_KEY_PEM);

  esp_err_t err = httpd_ssl_start(&this->server_, &config);
  g_server_handle = this->server_;   // DIAGNOSTIC
  if (err != ESP_OK) {
    // Most likely cause is not enough contiguous heap for the TLS context.
    ESP_LOGE(TAG, "httpd_ssl_start failed: %s", esp_err_to_name(err));
  }
  if (err == ESP_OK) {
    const httpd_uri_t handler_get = {
        .uri = "",
        .method = HTTP_GET,
        .handler = AsyncWebServer::request_handler,
        .user_ctx = this,
    };
    httpd_register_uri_handler(this->server_, &handler_get);

    const httpd_uri_t handler_post = {
        .uri = "",
        .method = HTTP_POST,
        .handler = AsyncWebServer::request_post_handler,
        .user_ctx = this,
    };
    httpd_register_uri_handler(this->server_, &handler_post);

    const httpd_uri_t handler_options = {
        .uri = "",
        .method = HTTP_OPTIONS,
        .handler = AsyncWebServer::request_handler,
        .user_ctx = this,
    };
    httpd_register_uri_handler(this->server_, &handler_options);
  }
}

esp_err_t AsyncWebServer::request_post_handler(httpd_req_t *r) {
  ESP_LOGVV(TAG, "Enter AsyncWebServer::request_post_handler. uri=%s", r->uri);
  auto content_type = request_get_header(r, "Content-Type");

  if (!request_has_header(r, "Content-Length")) {
    ESP_LOGW(TAG, "Content length is required for post: %s", r->uri);
    httpd_resp_send_err(r, HTTPD_411_LENGTH_REQUIRED, nullptr);
    return ESP_OK;
  }

  if (content_type.has_value()) {
    const char *content_type_char = content_type.value().c_str();

    // Check most common case first
    if (stristr(content_type_char, "application/x-www-form-urlencoded") != nullptr) {
      // Normal form data - proceed with regular handling
#ifdef USE_WEBSERVER_OTA
    } else if (stristr(content_type_char, "multipart/form-data") != nullptr) {
      auto *server = static_cast<AsyncWebServer *>(r->user_ctx);
      return server->handle_multipart_upload_(r, content_type_char);
#endif
    } else {
      ESP_LOGW(TAG, "Unsupported content type for POST: %s", content_type_char);
      // fallback to get handler to support backward compatibility
      return AsyncWebServer::request_handler(r);
    }
  }

  // Handle regular form data
  if (r->content_len > CONFIG_HTTPD_MAX_REQ_HDR_LEN) {
    ESP_LOGW(TAG, "Request size is to big: %zu", r->content_len);
    httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, nullptr);
    return ESP_FAIL;
  }

  std::string post_query;
  if (r->content_len > 0) {
    post_query.resize(r->content_len);
    const int ret = httpd_req_recv(r, &post_query[0], r->content_len + 1);
    if (ret <= 0) {  // 0 return value indicates connection closed
      if (ret == HTTPD_SOCK_ERR_TIMEOUT) {
        httpd_resp_send_err(r, HTTPD_408_REQ_TIMEOUT, nullptr);
        return ESP_ERR_TIMEOUT;
      }
      httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, nullptr);
      return ESP_FAIL;
    }
  }

  AsyncWebServerRequest req(r, std::move(post_query));
  return static_cast<AsyncWebServer *>(r->user_ctx)->request_handler_(&req);
}

esp_err_t AsyncWebServer::request_handler(httpd_req_t *r) {
  ESP_LOGVV(TAG, "Enter AsyncWebServer::request_handler. method=%u, uri=%s", r->method, r->uri);
  AsyncWebServerRequest req(r);
  return static_cast<AsyncWebServer *>(r->user_ctx)->request_handler_(&req);
}

esp_err_t AsyncWebServer::request_handler_(AsyncWebServerRequest *request) const {
  for (auto *handler : this->handlers_) {
    if (handler->canHandle(request)) {
      // At now process only basic requests.
      // OTA requires multipart request support and handleUpload for it
      handler->handleRequest(request);
      return ESP_OK;
    }
  }
  if (this->on_not_found_) {
    this->on_not_found_(request);
    return ESP_OK;
  }
  return ESP_ERR_NOT_FOUND;
}

AsyncWebServerRequest::~AsyncWebServerRequest() {
  delete this->rsp_;
  for (auto *param : this->params_) {
    delete param;  // NOLINT(cppcoreguidelines-owning-memory)
  }
}

bool AsyncWebServerRequest::hasHeader(const char *name) const { return request_has_header(*this, name); }

optional<std::string> AsyncWebServerRequest::get_header(const char *name) const {
  return request_get_header(*this, name);
}

std::string AsyncWebServerRequest::url() const {
  auto *str = strchr(this->req_->uri, '?');
  if (str == nullptr) {
    return this->req_->uri;
  }
  return std::string(this->req_->uri, str - this->req_->uri);
}

std::string AsyncWebServerRequest::host() const { return this->get_header("Host").value(); }

void AsyncWebServerRequest::send(AsyncWebServerResponse *response) {
  httpd_resp_send(*this, response->get_content_data(), response->get_content_size());
}

void AsyncWebServerRequest::send(int code, const char *content_type, const char *content) {
  this->init_response_(nullptr, code, content_type);
  if (content) {
    httpd_resp_send(*this, content, HTTPD_RESP_USE_STRLEN);
  } else {
    httpd_resp_send(*this, nullptr, 0);
  }
}

void AsyncWebServerRequest::redirect(const std::string &url) {
  httpd_resp_set_status(*this, "302 Found");
  httpd_resp_set_hdr(*this, "Location", url.c_str());
  httpd_resp_set_hdr(*this, "Connection", "close");
  httpd_resp_send(*this, nullptr, 0);
}

void AsyncWebServerRequest::init_response_(AsyncWebServerResponse *rsp, int code, const char *content_type) {
  // Set status code - use constants for common codes, default to 500 for unknown codes
  const char *status;
  switch (code) {
    case 200:
      status = HTTPD_200;
      break;
    case 404:
      status = HTTPD_404;
      break;
    case 409:
      status = HTTPD_409;
      break;
    default:
      status = HTTPD_500;
      break;
  }
  httpd_resp_set_status(*this, status);

  if (content_type && *content_type) {
    httpd_resp_set_type(*this, content_type);
  }
  httpd_resp_set_hdr(*this, "Accept-Ranges", "none");

  for (const auto &pair : DefaultHeaders::Instance().headers_) {
    httpd_resp_set_hdr(*this, pair.first.c_str(), pair.second.c_str());
  }

  delete this->rsp_;
  this->rsp_ = rsp;
}

#ifdef USE_WEBSERVER_AUTH
bool AsyncWebServerRequest::authenticate(const char *username, const char *password) const {
  if (username == nullptr || password == nullptr || *username == 0) {
    return true;
  }
  auto auth = this->get_header("Authorization");
  if (!auth.has_value()) {
    return false;
  }

  auto *auth_str = auth.value().c_str();

  const auto auth_prefix_len = sizeof("Basic ") - 1;
  if (strncmp("Basic ", auth_str, auth_prefix_len) != 0) {
    ESP_LOGW(TAG, "Only Basic authorization supported yet");
    return false;
  }

  std::string user_info;
  user_info += username;
  user_info += ':';
  user_info += password;

  size_t n = 0, out;
  esp_crypto_base64_encode(nullptr, 0, &n, reinterpret_cast<const uint8_t *>(user_info.c_str()), user_info.size());

  auto digest = std::unique_ptr<char[]>(new char[n + 1]);
  esp_crypto_base64_encode(reinterpret_cast<uint8_t *>(digest.get()), n, &out,
                           reinterpret_cast<const uint8_t *>(user_info.c_str()), user_info.size());

  return strcmp(digest.get(), auth_str + auth_prefix_len) == 0;
}

void AsyncWebServerRequest::requestAuthentication(const char *realm) const {
  httpd_resp_set_hdr(*this, "Connection", "keep-alive");
  auto auth_val = str_sprintf("Basic realm=\"%s\"", realm ? realm : "Login Required");
  httpd_resp_set_hdr(*this, "WWW-Authenticate", auth_val.c_str());
  httpd_resp_send_err(*this, HTTPD_401_UNAUTHORIZED, nullptr);
}
#endif

AsyncWebParameter *AsyncWebServerRequest::getParam(const std::string &name) {
  // Check cache first - only successful lookups are cached
  for (auto *param : this->params_) {
    if (param->name() == name) {
      return param;
    }
  }

  // Look up value from query strings
  optional<std::string> val = query_key_value(this->post_query_, name);
  if (!val.has_value()) {
    auto url_query = request_get_url_query(*this);
    if (url_query.has_value()) {
      val = query_key_value(url_query.value(), name);
    }
  }

  // Don't cache misses to avoid wasting memory when handlers check for
  // optional parameters that don't exist in the request
  if (!val.has_value()) {
    return nullptr;
  }

  auto *param = new AsyncWebParameter(name, val.value());  // NOLINT(cppcoreguidelines-owning-memory)
  this->params_.push_back(param);
  return param;
}

void AsyncWebServerResponse::addHeader(const char *name, const char *value) {
  httpd_resp_set_hdr(*this->req_, name, value);
}

void AsyncResponseStream::print(float value) {
  // Use stack buffer to avoid temporary string allocation
  // Size: sign (1) + digits (10) + decimal (1) + precision (6) + exponent (5) + null (1) = 24, use 32 for safety
  char buf[32];
  int len = snprintf(buf, sizeof(buf), "%f", value);
  this->content_.append(buf, len);
}

void AsyncResponseStream::printf(const char *fmt, ...) {
  va_list args;

  va_start(args, fmt);
  const int length = vsnprintf(nullptr, 0, fmt, args);
  va_end(args);

  std::string str;
  str.resize(length);

  va_start(args, fmt);
  vsnprintf(&str[0], length + 1, fmt, args);
  va_end(args);

  this->print(str);
}

#ifdef USE_WEBSERVER
AsyncEventSource::~AsyncEventSource() {
  for (auto *ses : this->sessions_) {
    delete ses;  // NOLINT(cppcoreguidelines-owning-memory)
  }
}

void AsyncEventSource::handleRequest(AsyncWebServerRequest *request) {
  // NOLINTNEXTLINE(cppcoreguidelines-owning-memory,clang-analyzer-cplusplus.NewDeleteLeaks)
  auto *rsp = new AsyncEventSourceResponse(request, this, this->web_server_);
  if (this->on_connect_) {
    this->on_connect_(rsp);
  }
  this->sessions_.push_back(rsp);
}

void AsyncEventSource::loop() {
  // Clean up dead sessions safely
  // This follows the ESP-IDF pattern where free_ctx marks resources as dead
  // and the main loop handles the actual cleanup to avoid race conditions
  for (size_t i = 0; i < this->sessions_.size();) {
    auto *ses = this->sessions_[i];
    // If the session has a dead socket (marked by destroy callback)
    if (ses->fd_.load() == 0) {
      ESP_LOGD(TAG, "Removing dead event source session");
      delete ses;  // NOLINT(cppcoreguidelines-owning-memory)
      // Remove by swapping with last element (O(1) removal, order doesn't matter for sessions)
      this->sessions_[i] = this->sessions_.back();
      this->sessions_.pop_back();
    } else {
      ses->loop();
      ++i;
    }
  }
}

void AsyncEventSource::try_send_nodefer(const char *message, const char *event, uint32_t id, uint32_t reconnect) {
  for (auto *ses : this->sessions_) {
    if (ses->fd_.load() != 0) {  // Skip dead sessions
      ses->try_send_nodefer(message, event, id, reconnect);
    }
  }
}

void AsyncEventSource::deferrable_send_state(void *source, const char *event_type,
                                             message_generator_t *message_generator) {
  // Skip if no connected clients to avoid unnecessary processing
  if (this->empty())
    return;
  for (auto *ses : this->sessions_) {
    if (ses->fd_.load() != 0) {  // Skip dead sessions
      ses->deferrable_send_state(source, event_type, message_generator);
    }
  }
}

AsyncEventSourceResponse::AsyncEventSourceResponse(const AsyncWebServerRequest *request,
                                                   esphome::web_server_idf::AsyncEventSource *server,
                                                   esphome::web_server::WebServer *ws)
    : server_(server), web_server_(ws), entities_iterator_(new esphome::web_server::ListEntitiesIterator(ws, server)) {
  httpd_req_t *req = *request;

  httpd_resp_set_status(req, HTTPD_200);
  httpd_resp_set_type(req, "text/event-stream");
  httpd_resp_set_hdr(req, "Cache-Control", "no-cache");
  httpd_resp_set_hdr(req, "Connection", "keep-alive");

  for (const auto &pair : DefaultHeaders::Instance().headers_) {
    httpd_resp_set_hdr(req, pair.first.c_str(), pair.second.c_str());
  }

  httpd_resp_send_chunk(req, CRLF_STR, CRLF_LEN);

  req->sess_ctx = this;
  req->free_ctx = AsyncEventSourceResponse::destroy;

  this->hd_ = req->handle;
  this->fd_.store(httpd_req_to_sockfd(req));

  // FORK: upstream installs a non-blocking send override here:
  //
  //   httpd_sess_set_send_override(this->hd_, this->fd_.load(), nonblocking_send);
  //
  // We must NOT do that under TLS. That override writes with a raw send() on
  // the bare socket fd, bypassing mbedTLS entirely - it would push plaintext
  // into an encrypted connection and the browser would drop it as garbage.
  // httpd_ssl_start() has already installed the correct encrypting send
  // function, so we leave it alone.
  //
  // The cost: event-stream sends are blocking again, which is what the override
  // existed to avoid. Payloads here are small (a few hundred bytes), so this is
  // expected to be fine, but a stalled client could hold up the server task.
  // If that shows up in practice, the fix is a TLS-aware non-blocking send that
  // writes through the session's SSL context rather than the socket.

  // Configure reconnect timeout and send config
  // this should always go through since the tcp send buffer is empty on connect
  std::string message = ws->get_config_json();
  this->try_send_nodefer(message.c_str(), "ping", millis(), 30000);

#ifdef USE_WEBSERVER_SORTING
  for (auto &group : ws->sorting_groups_) {
    // NOLINTBEGIN(clang-analyzer-cplusplus.NewDeleteLeaks) false positive with ArduinoJson
    json::JsonBuilder builder;
    JsonObject root = builder.root();
    root["name"] = group.second.name;
    root["sorting_weight"] = group.second.weight;
    message = builder.serialize();
    // NOLINTEND(clang-analyzer-cplusplus.NewDeleteLeaks)

    // a (very) large number of these should be able to be queued initially without defer
    // since the only thing in the send buffer at this point is the initial ping/config
    this->try_send_nodefer(message.c_str(), "sorting_group");
  }
#endif

  this->entities_iterator_->begin(ws->include_internal_);

  // just dump them all up-front and take advantage of the deferred queue
  //     on second thought that takes too long, but leaving the commented code here for debug purposes
  // while(!this->entities_iterator_->completed()) {
  //  this->entities_iterator_->advance();
  //}
}

void AsyncEventSourceResponse::destroy(void *ptr) {
  auto *rsp = static_cast<AsyncEventSourceResponse *>(ptr);
  int fd = rsp->fd_.exchange(0);  // Atomically get and clear fd
  ESP_LOGD(TAG, "Event source connection closed (fd: %d)", fd);
  // Mark as dead - will be cleaned up in the main loop
  // Note: We don't delete or remove from set here to avoid race conditions
  // httpd will call our custom close_fn (safe_close_with_shutdown) which handles
  // shutdown() before close() to prevent lwIP race conditions
}

// helper for allowing only unique entries in the queue
void AsyncEventSourceResponse::deq_push_back_with_dedup_(void *source, message_generator_t *message_generator) {
  DeferredEvent item(source, message_generator);

  // Use range-based for loop instead of std::find_if to reduce template instantiation overhead and binary size
  for (auto &event : this->deferred_queue_) {
    if (event == item) {
      return;  // Already in queue, no need to update since items are equal
    }
  }
  this->deferred_queue_.push_back(item);
}

void AsyncEventSourceResponse::process_deferred_queue_() {
  while (!deferred_queue_.empty()) {
    DeferredEvent &de = deferred_queue_.front();
    std::string message = de.message_generator_(web_server_, de.source_);
    if (this->try_send_nodefer(message.c_str(), "state")) {
      // O(n) but memory efficiency is more important than speed here which is why std::vector was chosen
      deferred_queue_.erase(deferred_queue_.begin());
    } else {
      break;
    }
  }
}

void AsyncEventSourceResponse::process_buffer_() {
  if (event_buffer_.empty()) {
    return;
  }
  if (event_bytes_sent_ == event_buffer_.size()) {
    event_buffer_.resize(0);
    event_bytes_sent_ = 0;
    return;
  }

  size_t remaining = event_buffer_.size() - event_bytes_sent_;
  int bytes_sent =
      httpd_socket_send(this->hd_, this->fd_.load(), event_buffer_.c_str() + event_bytes_sent_, remaining, 0);
  if (bytes_sent == HTTPD_SOCK_ERR_TIMEOUT) {
    // EAGAIN/EWOULDBLOCK - socket buffer full, try again later
    // NOTE: Similar logic exists in web_server/web_server.cpp in DeferredUpdateEventSource::process_deferred_queue_()
    // The implementations differ due to platform-specific APIs (HTTPD_SOCK_ERR_TIMEOUT vs DISCARDED, fd_.store(0) vs
    // close()), but the failure counting and timeout logic should be kept in sync. If you change this logic, also
    // update the Arduino implementation.
    this->consecutive_send_failures_++;
    if (this->consecutive_send_failures_ >= MAX_CONSECUTIVE_SEND_FAILURES) {
      // Too many failures, connection is likely dead
      ESP_LOGW(TAG, "Closing stuck EventSource connection after %" PRIu16 " failed sends",
               this->consecutive_send_failures_);
      this->fd_.store(0);  // Mark for cleanup
      this->deferred_queue_.clear();
    }
    return;
  }
  if (bytes_sent == HTTPD_SOCK_ERR_FAIL) {
    // Real socket error - connection will be closed by httpd and destroy callback will be called
    return;
  }
  if (bytes_sent <= 0) {
    // Unexpected error or zero bytes sent
    ESP_LOGW(TAG, "Unexpected send result: %d", bytes_sent);
    return;
  }

  // Successful send - reset failure counter
  this->consecutive_send_failures_ = 0;
  event_bytes_sent_ += bytes_sent;

  // Log partial sends for debugging
  if (event_bytes_sent_ < event_buffer_.size()) {
    ESP_LOGV(TAG, "Partial send: %d/%zu bytes (total: %zu/%zu)", bytes_sent, remaining, event_bytes_sent_,
             event_buffer_.size());
  }

  if (event_bytes_sent_ == event_buffer_.size()) {
    event_buffer_.resize(0);
    event_bytes_sent_ = 0;
  }
}

void AsyncEventSourceResponse::loop() {
  process_buffer_();
  process_deferred_queue_();
  if (!this->entities_iterator_->completed())
    this->entities_iterator_->advance();
}

bool AsyncEventSourceResponse::try_send_nodefer(const char *message, const char *event, uint32_t id,
                                                uint32_t reconnect) {
  if (this->fd_.load() == 0) {
    return false;
  }

  process_buffer_();
  if (!event_buffer_.empty()) {
    // there is still pending event data to send first
    return false;
  }

  // 8 spaces are standing in for the hexidecimal chunk length to print later
  const char chunk_len_header[] = "        " CRLF_STR;
  const int chunk_len_header_len = sizeof(chunk_len_header) - 1;

  event_buffer_.append(chunk_len_header);

  // Use stack buffer for formatting numeric fields to avoid temporary string allocations
  // Size: "retry: " (7) + max uint32 (10 digits) + CRLF (2) + null (1) = 20 bytes, use 32 for safety
  constexpr size_t num_buf_size = 32;
  char num_buf[num_buf_size];

  if (reconnect) {
    int len = snprintf(num_buf, num_buf_size, "retry: %" PRIu32 CRLF_STR, reconnect);
    event_buffer_.append(num_buf, len);
  }

  if (id) {
    int len = snprintf(num_buf, num_buf_size, "id: %" PRIu32 CRLF_STR, id);
    event_buffer_.append(num_buf, len);
  }

  if (event && *event) {
    event_buffer_.append("event: ", sizeof("event: ") - 1);
    event_buffer_.append(event);
    event_buffer_.append(CRLF_STR, CRLF_LEN);
  }

  if (message && *message) {
    event_buffer_.append("data: ", sizeof("data: ") - 1);
    event_buffer_.append(message);
    event_buffer_.append(CRLF_STR, CRLF_LEN);
  }

  if (event_buffer_.empty()) {
    return true;
  }

  event_buffer_.append(CRLF_STR, CRLF_LEN);
  event_buffer_.append(CRLF_STR, CRLF_LEN);

  // chunk length header itself and the final chunk terminating CRLF are not counted as part of the chunk
  int chunk_len = event_buffer_.size() - CRLF_LEN - chunk_len_header_len;
  char chunk_len_str[9];
  snprintf(chunk_len_str, 9, "%08x", chunk_len);
  std::memcpy(&event_buffer_[0], chunk_len_str, 8);

  event_bytes_sent_ = 0;
  process_buffer_();

  return true;
}

void AsyncEventSourceResponse::deferrable_send_state(void *source, const char *event_type,
                                                     message_generator_t *message_generator) {
  // allow all json "details_all" to go through before publishing bare state events, this avoids unnamed entries showing
  // up in the web GUI and reduces event load during initial connect
  if (!entities_iterator_->completed() && 0 != strcmp(event_type, "state_detail_all"))
    return;

  if (source == nullptr)
    return;
  if (event_type == nullptr)
    return;
  if (message_generator == nullptr)
    return;

  if (0 != strcmp(event_type, "state_detail_all") && 0 != strcmp(event_type, "state")) {
    ESP_LOGE(TAG, "Can't defer non-state event");
  }

  process_buffer_();
  process_deferred_queue_();

  if (!event_buffer_.empty() || !deferred_queue_.empty()) {
    // outgoing event buffer or deferred queue still not empty which means downstream tcp send buffer full, no point
    // trying to send first
    deq_push_back_with_dedup_(source, message_generator);
  } else {
    std::string message = message_generator(web_server_, source);
    if (!this->try_send_nodefer(message.c_str(), "state")) {
      deq_push_back_with_dedup_(source, message_generator);
    }
  }
}
#endif

#ifdef USE_WEBSERVER_OTA
esp_err_t AsyncWebServer::handle_multipart_upload_(httpd_req_t *r, const char *content_type) {
  static constexpr size_t MULTIPART_CHUNK_SIZE = 1460;       // Match Arduino AsyncWebServer buffer size
  static constexpr size_t YIELD_INTERVAL_BYTES = 16 * 1024;  // Yield every 16KB to prevent watchdog

  // Parse boundary and create reader
  const char *boundary_start;
  size_t boundary_len;
  if (!parse_multipart_boundary(content_type, &boundary_start, &boundary_len)) {
    ESP_LOGE(TAG, "Failed to parse multipart boundary");
    httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, nullptr);
    return ESP_FAIL;
  }

  AsyncWebServerRequest req(r);
  AsyncWebHandler *handler = nullptr;
  for (auto *h : this->handlers_) {
    if (h->canHandle(&req)) {
      handler = h;
      break;
    }
  }

  if (!handler) {
    ESP_LOGW(TAG, "No handler found for OTA request");
    httpd_resp_send_err(r, HTTPD_404_NOT_FOUND, nullptr);
    return ESP_OK;
  }

  // Upload state
  std::string filename;
  size_t index = 0;
  // Create reader on heap to reduce stack usage
  auto reader = std::make_unique<MultipartReader>("--" + std::string(boundary_start, boundary_len));

  // Configure callbacks
  reader->set_data_callback([&](const uint8_t *data, size_t len) {
    if (!reader->has_file() || !len)
      return;

    if (filename.empty()) {
      filename = reader->get_current_part().filename;
      ESP_LOGV(TAG, "Processing file: '%s'", filename.c_str());
      handler->handleUpload(&req, filename, 0, nullptr, 0, false);  // Start
    }

    handler->handleUpload(&req, filename, index, const_cast<uint8_t *>(data), len, false);
    index += len;
  });

  reader->set_part_complete_callback([&]() {
    if (index > 0) {
      handler->handleUpload(&req, filename, index, nullptr, 0, true);  // End
      filename.clear();
      index = 0;
    }
  });

  // Process data
  std::unique_ptr<char[]> buffer(new char[MULTIPART_CHUNK_SIZE]);
  size_t bytes_since_yield = 0;

  for (size_t remaining = r->content_len; remaining > 0;) {
    int recv_len = httpd_req_recv(r, buffer.get(), std::min(remaining, MULTIPART_CHUNK_SIZE));

    if (recv_len <= 0) {
      httpd_resp_send_err(r, recv_len == HTTPD_SOCK_ERR_TIMEOUT ? HTTPD_408_REQ_TIMEOUT : HTTPD_400_BAD_REQUEST,
                          nullptr);
      return recv_len == HTTPD_SOCK_ERR_TIMEOUT ? ESP_ERR_TIMEOUT : ESP_FAIL;
    }

    if (reader->parse(buffer.get(), recv_len) != static_cast<size_t>(recv_len)) {
      ESP_LOGW(TAG, "Multipart parser error");
      httpd_resp_send_err(r, HTTPD_400_BAD_REQUEST, nullptr);
      return ESP_FAIL;
    }

    remaining -= recv_len;
    bytes_since_yield += recv_len;

    if (bytes_since_yield > YIELD_INTERVAL_BYTES) {
      vTaskDelay(1);
      bytes_since_yield = 0;
    }
  }

  handler->handleRequest(&req);
  return ESP_OK;
}
#endif  // USE_WEBSERVER_OTA

}  // namespace web_server_idf
}  // namespace esphome

#endif  // !defined(USE_ESP32)
