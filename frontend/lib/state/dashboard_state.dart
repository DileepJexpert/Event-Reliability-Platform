import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/event_stream.dart';

/// Live view state for the dashboard (§16).
///
/// Reactive, push-driven: it subscribes to the SSE live feed and, on every push, reconciles the
/// dashboard from the REST API (debounced, so the burst of state-change events one failure produces
/// collapses into a single update). REST is the source of truth for the *numbers* (so the cards are
/// always exact, never double-counted), and SSE makes them update in real time — on web via the
/// browser EventSource, on desktop via http streaming. A short backstop poll covers any missed push
/// or a dropped stream. Holds no business logic — it just projects the backend's data for display.
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

  /// Backstop reconcile in case an SSE push is missed or the stream is down.
  static const Duration _pollInterval = Duration(seconds: 15);

  /// Collapse a burst of pushes (one failure emits several state-change events) into one refresh.
  static const Duration _debounce = Duration(milliseconds: 400);

  final Map<String, int> classificationCounts = {};
  final List<FailureSummary> recentFailures = [];
  List<Incident> incidents = [];
  bool connected = false;
  int totalFailureEvents = 0;

  StreamSubscription<LiveEvent>? _sub;
  Timer? _pollTimer;
  Timer? _debounceTimer;
  bool _disposed = false;
  bool _refreshing = false;

  DashboardState(this.api, this.stream);

  Future<void> start() async {
    await refresh();
    _pollTimer = Timer.periodic(_pollInterval, (_) => refresh());
    _listen();
  }

  void _listen() {
    _sub = stream.connect().listen(
      (_) => _scheduleRefresh(), // a live push arrived → reconcile right away (debounced)
      onError: (_) => _scheduleReconnect(),
      onDone: _scheduleReconnect,
      cancelOnError: true,
    );
  }

  void _scheduleRefresh() {
    _debounceTimer?.cancel();
    _debounceTimer = Timer(_debounce, () {
      if (!_disposed) refresh();
    });
  }

  void _scheduleReconnect() {
    // The REST poll keeps the dashboard correct regardless, so a dropped SSE stream doesn't blank it.
    if (!_disposed) {
      Future.delayed(const Duration(seconds: 3), () {
        if (!_disposed) _listen();
      });
    }
  }

  /// Load current totals + recent failures + incidents over REST (source of truth, every platform).
  Future<void> refresh() async {
    if (_disposed || _refreshing) {
      return;
    }
    _refreshing = true;
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
      // Backend unreachable — surface "reconnecting"; the next push or poll retries.
      connected = false;
      _notify();
    } finally {
      _refreshing = false;
    }
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
    _debounceTimer?.cancel();
    _sub?.cancel();
    stream.close();
    super.dispose();
  }
}
