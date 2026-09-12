// SocketSerialInterface.h: MeshCore's companion protocol over a TCP socket.
//
// MeshCore carries the same binary protocol over BLE, USB and TCP, and only the
// framing differs. This mirrors ArduinoSerialInterface exactly, including its
// receive state machine, and swaps the Stream for a socket:
//
//   outbound   '>' then a little-endian uint16 length, then the frame
//   inbound    '<' then a little-endian uint16 length, then the frame
//
// Port 5000 is what stock MeshCore clients expect of a WiFi companion, so an
// unmodified app finds the node with no special casing, the way 4403 works for
// meshtasticd today.
//
// One client at a time, and a new connection evicts the old one. That matches
// both the firmware and openHop's CompanionFrameServer. If Meshdroid ever needs
// the app and an external client attached at once, that is a MeshProxy
// equivalent rather than a change here.
//
// Header-only and living in the variant, so it stays ours across a MeshCore
// version bump. Patch 0001 only instantiates and registers it.
#pragma once

#ifdef __cplusplus

#include <helpers/BaseSerialInterface.h>

#include <arpa/inet.h>
#include <cerrno>
#include <cstdio>
#include <cstring>
#include <fcntl.h>
#include <netinet/in.h>
#include <netinet/tcp.h>
#include <poll.h>
#include <sys/socket.h>
#include <unistd.h>

class SocketSerialInterface : public BaseSerialInterface {
  // Same states and order as ArduinoSerialInterface, so the two can be diffed.
  enum { ST_IDLE = 0, ST_HDR, ST_LEN1, ST_BODY };

  bool _isEnabled = false;
  int _listen_fd = -1;
  int _client_fd = -1;
  uint16_t _port = 5000;
  char _bind_addr[64] = "0.0.0.0";

  // How long a write waits for a client that has stopped reading before the
  // client is dropped. See writeAll().
  static const int WRITE_TIMEOUT_MS = 2000;

  uint8_t _state = ST_IDLE;
  uint16_t _frame_len = 0;
  uint16_t _rx_len = 0;
  uint8_t _rx_buf[MAX_FRAME_SIZE];

  static void set_nonblocking(int fd) {
    int flags = fcntl(fd, F_GETFL, 0);
    if (flags >= 0) fcntl(fd, F_SETFL, flags | O_NONBLOCK);
  }

  void closeClient(const char* why) {
    if (_client_fd < 0) return;
    ::close(_client_fd);
    _client_fd = -1;
    _state = ST_IDLE;
    _rx_len = 0;
    _frame_len = 0;
    fprintf(stderr, "companion: client disconnected (%s)\n", why);
  }

public:
  SocketSerialInterface() { }

  void begin(const char* bind_addr, uint16_t port) {
    if (bind_addr != NULL && bind_addr[0] != '\0') {
      strncpy(_bind_addr, bind_addr, sizeof(_bind_addr) - 1);
      _bind_addr[sizeof(_bind_addr) - 1] = '\0';
    }
    _port = port;
  }

  // ---- BaseSerialInterface -------------------------------------------------

  void enable() override {
    if (_isEnabled) return;

    _listen_fd = ::socket(AF_INET, SOCK_STREAM, 0);
    if (_listen_fd < 0) {
      fprintf(stderr, "companion: socket() failed: %s\n", strerror(errno));
      return;
    }

    // Without this, a restart within the TIME_WAIT window cannot rebind, which
    // on a phone means the daemon fails to come back after a profile switch.
    int one = 1;
    ::setsockopt(_listen_fd, SOL_SOCKET, SO_REUSEADDR, &one, sizeof(one));

    struct sockaddr_in addr;
    memset(&addr, 0, sizeof(addr));
    addr.sin_family = AF_INET;
    addr.sin_port = htons(_port);
    if (::inet_pton(AF_INET, _bind_addr, &addr.sin_addr) != 1) {
      addr.sin_addr.s_addr = htonl(INADDR_ANY);
    }

    if (::bind(_listen_fd, (struct sockaddr*)&addr, sizeof(addr)) != 0) {
      fprintf(stderr, "companion: bind(%s:%u) failed: %s\n",
              _bind_addr, (unsigned)_port, strerror(errno));
      ::close(_listen_fd);
      _listen_fd = -1;
      return;
    }
    if (::listen(_listen_fd, 1) != 0) {
      fprintf(stderr, "companion: listen() failed: %s\n", strerror(errno));
      ::close(_listen_fd);
      _listen_fd = -1;
      return;
    }

    set_nonblocking(_listen_fd);
    _isEnabled = true;
    _state = ST_IDLE;
    fprintf(stderr, "companion: listening on %s:%u\n", _bind_addr, (unsigned)_port);
  }

  void disable() override {
    closeClient("interface disabled");
    if (_listen_fd >= 0) {
      ::close(_listen_fd);
      _listen_fd = -1;
    }
    _isEnabled = false;
  }

  bool isEnabled() const override { return _isEnabled; }

  bool isConnected() const override { return _client_fd >= 0; }

  // Accepts a pending connection, if any. Non-blocking, so this returns
  // immediately when nobody is knocking.
  void loop() override {
    if (!_isEnabled || _listen_fd < 0) return;

    int fd = ::accept(_listen_fd, NULL, NULL);
    if (fd < 0) return;   // EAGAIN when there is nothing waiting

    // Eviction, as the firmware and openHop both do.
    if (_client_fd >= 0) closeClient("evicted by a new connection");

    set_nonblocking(fd);
    int one = 1;
    ::setsockopt(fd, IPPROTO_TCP, TCP_NODELAY, &one, sizeof(one));
    _client_fd = fd;
    _state = ST_IDLE;
    _rx_len = 0;
    fprintf(stderr, "companion: client connected\n");
  }

  bool isWriteBusy() const override { return false; }

  size_t writeFrame(const uint8_t src[], size_t len) override {
    if (_client_fd < 0 || len > MAX_FRAME_SIZE) return 0;

    uint8_t hdr[3];
    hdr[0] = '>';
    hdr[1] = (uint8_t)(len & 0xFF);
    hdr[2] = (uint8_t)(len >> 8);

    if (!writeAll(hdr, 3)) return 0;
    if (!writeAll(src, len)) return 0;
    return len;
  }

  size_t checkRecvFrame(uint8_t dest[]) override {
    loop();                       // pick up a connection even if nobody calls loop()

    // Bytes carried over from a read that contained more than one frame. A
    // client is free to pipeline, and recv() hands over whatever arrived, so
    // the remainder has to be kept rather than dropped.
    size_t got = drainPending(dest);
    if (got > 0) return got;

    if (_client_fd < 0) return 0;

    uint8_t chunk[256];
    for (;;) {
      ssize_t n = ::recv(_client_fd, chunk, sizeof(chunk), 0);
      if (n == 0) { closeClient("closed by peer"); return 0; }
      if (n < 0) {
        if (errno == EAGAIN || errno == EWOULDBLOCK || errno == EINTR) return 0;
        closeClient(strerror(errno));
        return 0;
      }

      for (ssize_t i = 0; i < n; i++) {
        size_t frame = feed(chunk[i], dest);
        if (frame > 0) {
          if (i + 1 < n) stash(&chunk[i + 1], (size_t)(n - i - 1));
          return frame;
        }
      }
    }
  }

private:
  // Bytes read past the end of a completed frame, kept until the next call.
  uint8_t _pending[512];
  size_t _pending_len = 0;

  void stash(const uint8_t* p, size_t n) {
    if (n > sizeof(_pending)) n = sizeof(_pending);
    memcpy(_pending, p, n);
    _pending_len = n;
  }

  bool writeAll(const uint8_t* p, size_t n) {
    size_t sent = 0;
    while (sent < n) {
      // MSG_NOSIGNAL: writing to a socket the app has closed would otherwise
      // raise SIGPIPE and kill the daemon outright.
      ssize_t w = ::send(_client_fd, p + sent, n - sent, MSG_NOSIGNAL);
      if (w > 0) { sent += (size_t)w; continue; }
      if (w < 0 && errno == EINTR) continue;

      if (w < 0 && (errno == EAGAIN || errno == EWOULDBLOCK)) {
        // The socket is non-blocking, so a client that has stopped reading
        // fills the send buffer and every send returns EAGAIN. Retrying
        // straight away spins this loop at full speed on the daemon's only
        // thread, which stops it servicing the radio and heats the phone. Wait
        // for the socket to become writable instead, and drop a client that
        // never does: a new connection evicts the old one anyway, so a client
        // that comes back reconnects.
        struct pollfd pfd;
        pfd.fd = _client_fd;
        pfd.events = POLLOUT;
        pfd.revents = 0;
        int r = ::poll(&pfd, 1, WRITE_TIMEOUT_MS);
        if (r > 0 && (pfd.revents & POLLOUT)) continue;
        if (r < 0 && errno == EINTR) continue;
        closeClient(r == 0 ? "write timed out" : "poll failed");
        return false;
      }

      closeClient(w < 0 ? strerror(errno) : "short write");
      return false;
    }
    return true;
  }

  // The receive state machine, byte for byte the same as
  // ArduinoSerialInterface::checkRecvFrame, so the two stay comparable.
  size_t feed(uint8_t c, uint8_t dest[]) {
    switch (_state) {
      case ST_IDLE:
        if (c == '<') _state = ST_HDR;
        break;
      case ST_HDR:
        _frame_len = c;                          // LSB
        _state = ST_LEN1;
        break;
      case ST_LEN1:
        _frame_len |= ((uint16_t)c) << 8;        // MSB
        _rx_len = 0;
        _state = _frame_len > 0 ? ST_BODY : ST_IDLE;
        break;
      default:
        if (_rx_len < MAX_FRAME_SIZE) _rx_buf[_rx_len] = c;
        _rx_len++;
        if (_rx_len >= _frame_len) {
          if (_frame_len > MAX_FRAME_SIZE) _frame_len = MAX_FRAME_SIZE;  // truncate
          memcpy(dest, _rx_buf, _frame_len);
          _state = ST_IDLE;
          return _frame_len;
        }
    }
    return 0;
  }

  // Feeds anything stashed after the previous frame back through the state
  // machine, so a client that pipelines two frames in one packet does not lose
  // the second.
  size_t drainPending(uint8_t dest[]) {
    while (_pending_len > 0) {
      uint8_t c = _pending[0];
      memmove(_pending, _pending + 1, --_pending_len);
      size_t got = feed(c, dest);
      if (got > 0) return got;
    }
    return 0;
  }
};

#endif  // __cplusplus
