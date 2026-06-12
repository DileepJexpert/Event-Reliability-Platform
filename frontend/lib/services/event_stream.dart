import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import 'auth_service.dart';

/// One Server-Sent Event from the backend live feed (§14): a named event (`failure` / `incident`)
/// and its decoded JSON payload.
class LiveEvent {
  final String event;
  final Map<String, dynamic> data;
  LiveEvent(this.event, this.data);
}

/// Minimal SSE client over the `http` package — connects to `GET /api/stream` and parses the
/// `event:` / `data:` lines into [LiveEvent]s. Kept dependency-light (no extra SSE package).
class EventStream {
  final AuthService auth;
  http.Client? _client;

  EventStream(this.auth);

  Stream<LiveEvent> connect() async* {
    _client = http.Client();
    final request = http.Request('GET', Uri.parse('${AppConfig.apiBaseUrl}/api/stream'));
    request.headers['Accept'] = 'text/event-stream';
    if (auth.token != null) {
      request.headers['Authorization'] = 'Bearer ${auth.token}';
    }

    final response = await _client!.send(request);
    var eventName = 'message';
    final dataBuffer = StringBuffer();

    await for (final line
        in response.stream.transform(utf8.decoder).transform(const LineSplitter())) {
      if (line.isEmpty) {
        // Blank line dispatches the buffered event.
        if (dataBuffer.isNotEmpty) {
          final raw = dataBuffer.toString();
          dataBuffer.clear();
          final name = eventName;
          eventName = 'message';
          try {
            final decoded = jsonDecode(raw);
            if (decoded is Map<String, dynamic>) {
              yield LiveEvent(name, decoded);
            }
          } catch (_) {
            // ignore non-JSON frames (e.g. the initial "connected" ping)
          }
        }
        continue;
      }
      if (line.startsWith(':')) continue; // comment / heartbeat
      if (line.startsWith('event:')) {
        eventName = line.substring(6).trim();
      } else if (line.startsWith('data:')) {
        dataBuffer.write(line.substring(5).trim());
      }
    }
  }

  void close() {
    _client?.close();
    _client = null;
  }
}
