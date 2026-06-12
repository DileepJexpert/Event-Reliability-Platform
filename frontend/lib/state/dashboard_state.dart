import 'dart:async';

import 'package:flutter/foundation.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/event_stream.dart';

/// Live view state for the dashboard (§16). Subscribes to the SSE feed and maintains in-memory
/// counters, a rolling list of recent failures and the set of active incidents. Holds no business
/// logic — it just projects the backend's events for display, and auto-reconnects if the feed drops.
class DashboardState extends ChangeNotifier {
  final ApiClient api;
  final EventStream stream;

  final Map<String, int> classificationCounts = {};
  final List<FailureSummary> recentFailures = [];
  List<Incident> incidents = [];
  bool connected = false;
  int totalFailureEvents = 0;

  StreamSubscription<LiveEvent>? _sub;
  bool _disposed = false;

  DashboardState(this.api, this.stream);

  Future<void> start() async {
    await refreshIncidents();
    _listen();
  }

  void _listen() {
    _sub = stream.connect().listen(
      (event) {
        connected = true;
        if (event.event == 'failure') {
          _onFailure(FailureSummary.fromJson(event.data));
        } else if (event.event == 'incident') {
          _onIncident(Incident.fromJson(event.data));
        }
        _notify();
      },
      onError: (_) => _onDisconnect(),
      onDone: _onDisconnect,
      cancelOnError: true,
    );
  }

  void _onDisconnect() {
    connected = false;
    _notify();
    if (!_disposed) {
      Future.delayed(const Duration(seconds: 3), () {
        if (!_disposed) _listen();
      });
    }
  }

  void _onFailure(FailureSummary failure) {
    totalFailureEvents++;
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

  Future<void> refreshIncidents() async {
    try {
      incidents = await api.listIncidents();
      _notify();
    } catch (_) {
      // leave existing incidents; the dashboard banner will simply not update
    }
  }

  List<Incident> get activeIncidents => incidents.where((i) => i.isActive).toList();

  void _notify() {
    if (!_disposed) notifyListeners();
  }

  @override
  void dispose() {
    _disposed = true;
    _sub?.cancel();
    stream.close();
    super.dispose();
  }
}
