import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import 'auth_service.dart';
import 'event_stream.dart';

EventStream createEventStream(AuthService auth) => IoEventStream(auth);

/// SSE over the `http` package — used on desktop/mobile, where the IO client streams the response
/// body incrementally. (On web the browser http client buffers instead of streaming, so web uses the
/// EventSource client in {@code event_stream_web.dart}.)
class IoEventStream implements EventStream {
  final AuthService auth;
  http.Client? _client;

  IoEventStream(this.auth);

  @override
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

  @override
  void close() {
    _client?.close();
    _client = null;
  }
}
