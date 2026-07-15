package com.denuoweb.hnsdane.net

import com.denuoweb.hnsdane.core.IcannTlds

/**
 * Keeps WebSockets native so Chromium sends their Upgrade through the active Rust proxy, while
 * preventing an HNS page from bypassing the reverse-bypass scope with another HNS name.
 */
internal object HnsProxyWebSocketPolicy {
    fun script(): String {
        val icannTlds = IcannTlds.ALL
            .sorted()
            .joinToString(",") { "'$it'" }
        return """
(function() {
  if (window.__hnsProxyWebSocketPolicyInstalled) return;
  window.__hnsProxyWebSocketPolicyInstalled = true;

  var NativeWebSocket = window.WebSocket;
  if (!NativeWebSocket) return;
  var icannTlds = new Set([$icannTlds]);
  var specialUseSuffixes = new Set(['alt', 'example', 'internal', 'invalid', 'local', 'localhost', 'onion', 'test']);

  function normalizeHost(host) {
    return String(host || '').replace(/^\[/, '').replace(/\]${'$'}/, '').replace(/\.+${'$'}/, '').toLowerCase();
  }

  function isIpLiteral(host) {
    if (!host) return false;
    if (host.indexOf(':') !== -1) return /^[0-9a-f:.]+${'$'}/i.test(host);
    var parts = host.split('.');
    if (parts.length !== 4) return false;
    return parts.every(function(part) {
      if (!/^[0-9]{1,3}${'$'}/.test(part)) return false;
      var value = Number(part);
      return value >= 0 && value <= 255;
    });
  }

  function requiresHnsResolution(host) {
    host = normalizeHost(host);
    if (!host || isIpLiteral(host)) return false;
    var labels = host.split('.');
    var suffix = labels[labels.length - 1];
    if (specialUseSuffixes.has(suffix)) return false;
    if (labels.length === 1) return true;
    return !icannTlds.has(suffix);
  }

  function inPageScope(targetHost, pageHost) {
    return targetHost === pageHost || targetHost.endsWith('.' + pageHost);
  }

  function ProxyScopedWebSocket(url, protocols) {
    if (!(this instanceof ProxyScopedWebSocket)) {
      throw new TypeError("Failed to construct 'WebSocket': Please use the 'new' operator.");
    }
    var pageUrl;
    var targetUrl;
    try {
      pageUrl = new URL(window.location.href);
      targetUrl = new URL(url, window.location.href);
    } catch (error) {
      return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
    }
    var pageHost = normalizeHost(pageUrl.hostname);
    var targetHost = normalizeHost(targetUrl.hostname);
    if (requiresHnsResolution(targetHost) &&
        (!requiresHnsResolution(pageHost) || !inPageScope(targetHost, pageHost))) {
      throw new DOMException('HNS WebSocket target is outside the active proxy scope.', 'SecurityError');
    }
    return protocols === undefined ? new NativeWebSocket(url) : new NativeWebSocket(url, protocols);
  }

  ProxyScopedWebSocket.prototype = NativeWebSocket.prototype;
  ProxyScopedWebSocket.CONNECTING = NativeWebSocket.CONNECTING;
  ProxyScopedWebSocket.OPEN = NativeWebSocket.OPEN;
  ProxyScopedWebSocket.CLOSING = NativeWebSocket.CLOSING;
  ProxyScopedWebSocket.CLOSED = NativeWebSocket.CLOSED;
  window.WebSocket = ProxyScopedWebSocket;
})();
        """.trimIndent()
    }
}
