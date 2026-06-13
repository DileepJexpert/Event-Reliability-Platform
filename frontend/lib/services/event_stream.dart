import 'auth_service.dart';
// Pick the platform implementation at compile time: browser-native EventSource on web (true
// real-time push), http streaming on desktop/mobile.
import 'event_stream_stub.dart'
    if (dart.library.io) 'event_stream_io.dart'
    if (dart.library.html) 'event_stream_web.dart';

/// One Server-Sent Event from the backend live feed (§14): a named event (`failure` / `incident`)
/// and its decoded JSON payload.
class LiveEvent {
  final String event;
  final Map<String, dynamic> data;
  LiveEvent(this.event, this.data);
}

/// SSE client for `GET /api/stream`. Use {@code EventStream(auth)} — the factory returns the right
/// implementation for the current platform (browser `EventSource` on web, `http` streaming on IO).
abstract class EventStream {
  factory EventStream(AuthService auth) => createEventStream(auth);

  /// Connect and stream live events.
  Stream<LiveEvent> connect();

  /// Close the underlying connection.
  void close();
}
