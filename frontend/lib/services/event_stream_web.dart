import 'dart:async';
import 'dart:convert';
import 'dart:html' as html;

import '../config/app_config.dart';
import 'auth_service.dart';
import 'event_stream.dart';

EventStream createEventStream(AuthService auth) => WebEventStream(auth);

/// SSE over the browser-native `EventSource` — the correct, real-time push API for Flutter web.
/// EventSource also reconnects automatically if the connection drops.
///
/// Note: `EventSource` cannot send an `Authorization` header, so this targets the dev/no-auth setup
/// (which matches the backend's default profile). An OIDC deployment would need a token-bearing SSE
/// proxy or a different transport.
class WebEventStream implements EventStream {
  final AuthService auth;
  html.EventSource? _source;

  WebEventStream(this.auth);

  @override
  Stream<LiveEvent> connect() {
    final controller = StreamController<LiveEvent>();
    final source = html.EventSource('${AppConfig.apiBaseUrl}/api/stream');
    _source = source;

    void emit(String name, html.Event event) {
      final data = event is html.MessageEvent ? event.data : null;
      if (data is! String) return;
      try {
        final decoded = jsonDecode(data);
        if (decoded is Map<String, dynamic>) {
          controller.add(LiveEvent(name, decoded));
        }
      } catch (_) {
        // ignore non-JSON frames (e.g. the initial "connected" ping)
      }
    }

    // The backend emits named events (event: failure / event: incident).
    source.addEventListener('failure', (e) => emit('failure', e));
    source.addEventListener('incident', (e) => emit('incident', e));
    source.onMessage.listen((e) => emit('message', e));
    // EventSource reconnects on its own; no manual handling needed.
    source.onError.listen((_) {});

    controller.onCancel = () => source.close();
    return controller.stream;
  }

  @override
  void close() {
    _source?.close();
    _source = null;
  }
}
