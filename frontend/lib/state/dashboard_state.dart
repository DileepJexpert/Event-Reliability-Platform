import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/event_stream.dart';

/// Live view state for the dashboard (§16).
///
/// The dashboard's source of truth is the REST API: on open (and on a short poll interval) it loads
/// the current per-classification totals, the recent failures and the active incidents. This works on
/// every platform — crucially including Flutter **web**, where the browser http client can't consume
/// the SSE stream. On top of that it *also* subscribes to the SSE live feed for instant updates where
/// streaming is supported (desktop/mobile); a dropped feed never blanks the dashboard because the poll
/// keeps it current. Holds no business logic — it just projects the backend's data for display.
class DashboardState extends ChangeNotifier {
  final ApiClient api;
  final EventStream stream;

  /// Classification cards shown on the dashboard, in display order.
  static const List<String> classifications = [
    'TRANSIENT',
    'INFRASTRUCTURE',
    'BUSINESS',
    'POISON',
    'UNKNOWN',
  ];

  static const Duration _pollInterval = Duration(seconds: 5);

  final Map<String, int> classificationCounts = {};
  final List<FailureSummary> recentFailures = [];
  List<Incident> incidents = [];
  bool connected = false;
  int totalFailureEvents = 0;

  StreamSubscription<LiveEvent>? _sub;
  Timer? _pollTimer;
  bool _disposed = false;

  DashboardState(this.api, this.stream);

  Future<void> start() async {
    await refresh();
    _pollTimer = Timer.periodic(_pollInterval, (_) => refresh());
    _listen();
  }

  /// Pull the current state over REST. Source of truth for the dashboard; works on every platform.
  Future<void> refresh() async {
    if (_disposed) {
      return;
    }
    try {
      final recent = await api.listFailures(size: 50);
      final counts = <String, int>{};
      for (final c in classifications) {
        // size=1 — we only need the per-classification total from the page envelope.
        final r = await api.listFailures(classification: c, size: 1);
        counts[c] = r.totalElements;
      }
      final inc = await api.listIncidents();

      recentFailures
        ..clear()
        ..addAll(recent.content);
      classificationCounts
        ..clear()
        ..addAll(counts);
      totalFailureEvents = recent.totalElements;
      incidents = inc;
      connected = true;
      _notify();
    } catch (_) {
      // Backend unreachable — surface "reconnecting"; the next poll tick retries.
      connected = false;
      _notify();
    }
  }

  void _listen() {
    _sub = stream.connect().listen(
      (event) {
        if (event.event == 'failure') {
          _onFailure(FailureSummary.fromJson(event.data));
        } else if (event.event == 'incident') {
          _onIncident(Incident.fromJson(event.data));
        }
        _notify();
      },
      onError: (_) => _scheduleReconnect(),
      onDone: _scheduleReconnect,
      cancelOnError: true,
    );
  }

  void _scheduleReconnect() {
    // SSE is best-effort; the REST poll keeps the dashboard correct regardless, so a dropped stream
    // does not flip the status to "reconnecting" (that now means the REST API itself is unreachable).
    if (!_disposed) {
      Future.delayed(const Duration(seconds: 3), () {
        if (!_disposed) _listen();
      });
    }
  }

  void _onFailure(FailureSummary failure) {
    final c = failure.classification ?? 'UNKNOWN';
    classificationCounts[c] = (classificationCounts[c] ?? 0) + 1;
    recentFailures.insert(0, failure);
    if (recentFailures.length > 100) {
      recentFailures.removeRange(100, recentFailures.length);
    }
  }

  void _onIncident(Incident incident) {
    incidents.removeWhere((i) => i.id == incident.id);
    incidents.insert(0, incident);
  }

  List<Incident> get activeIncidents => incidents.where((i) => i.isActive).toList();

  void _notify() {
    if (!_disposed) {
      notifyListeners();
    }
  }

  @override
  void dispose() {
    _disposed = true;
    _pollTimer?.cancel();
    _sub?.cancel();
    stream.close();
    super.dispose();
  }
}
