import 'auth_service.dart';
import 'event_stream.dart';

/// Fallback for platforms with neither dart:io nor dart:html. Never used in practice (Flutter targets
/// are always one or the other); present only so the conditional import in event_stream.dart resolves.
EventStream createEventStream(AuthService auth) =>
    throw UnsupportedError('No EventStream implementation for this platform');
