import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../state/dashboard_state.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Dashboard (§16): an operations overview — KPI cards, breakdowns by classification / source topic /
/// owning app, active-incident banner and a recent-failures table — updating live from the SSE feed.
/// The whole page scrolls so the recent-failures table is always fully usable.
class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<DashboardState>(
      builder: (context, state, _) {
        return SingleChildScrollView(
          padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              _header(context, state),
              const SizedBox(height: 14),
              if (state.activeIncidents.isNotEmpty) ...[
                _incidentBanner(context, state),
                const SizedBox(height: 14),
              ],
              _kpiRow(state),
              const SizedBox(height: 14),
              SizedBox(height: 250, child: _breakdowns(state)),
              const SizedBox(height: 18),
              Row(
                children: [
                  Text('Recent failures', style: Theme.of(context).textTheme.titleMedium),
                  const SizedBox(width: 8),
                  Text('newest ${state.recentFailures.length > 15 ? 15 : state.recentFailures.length}'
                      ' · open Failures for the full list',
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
              const SizedBox(height: 8),
              _recentTable(context, state),
            ],
          ),
        );
      },
    );
  }

  Widget _header(BuildContext context, DashboardState state) {
    return Row(
      children: [
        Text('Dashboard', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(width: 12),
        Icon(Icons.circle, size: 10, color: state.connected ? Colors.green : Colors.grey),
        const SizedBox(width: 4),
        Text(state.connected ? 'live' : 'reconnecting…',
            style: Theme.of(context).textTheme.bodySmall),
        const Spacer(),
        IconButton(
          tooltip: 'Refresh',
          icon: const Icon(Icons.refresh, size: 20),
          onPressed: () => state.refresh(),
        ),
      ],
    );
  }

  Widget _incidentBanner(BuildContext context, DashboardState state) {
    final inc = state.activeIncidents.first;
    return Card(
      color: Colors.red.withOpacity(0.06),
      shape: RoundedRectangleBorder(
        borderRadius: BorderRadius.circular(8),
        side: BorderSide(color: Colors.red.withOpacity(0.35)),
      ),
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 14, vertical: 10),
        child: Row(
          children: [
            const Icon(Icons.warning_amber, color: Colors.red, size: 20),
            const SizedBox(width: 10),
            Expanded(
              child: Text(
                '${state.activeIncidents.length} active incident(s) — ${inc.rootCause} '
                'on ${inc.sourceTopic} (${inc.count} failures)',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---- KPI cards (exact totals from REST) ----

  Widget _kpiRow(DashboardState state) {
    final counts = state.classificationCounts;
    int c(String k) => counts[k] ?? 0;
    final autoRecovering = c('TRANSIENT') + c('INFRASTRUCTURE');
    final needsAttention = c('BUSINESS') + c('POISON') + c('UNKNOWN');
    return Row(
      children: [
        Expanded(child: _kpi('Total failures', '${state.totalFailureEvents}',
            Icons.report_gmailerrorred, Colors.blueGrey)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Auto-recovering', '$autoRecovering', Icons.autorenew, Colors.blue)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Needs attention', '$needsAttention', Icons.flag, Colors.deepOrange)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Active incidents', '${state.activeIncidents.length}',
            Icons.warning_amber, Colors.red)),
      ],
    );
  }

  Widget _kpi(String label, String value, IconData icon, Color color) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(
          children: [
            Container(
              padding: const EdgeInsets.all(9),
              decoration: BoxDecoration(
                color: color.withOpacity(0.12),
                borderRadius: BorderRadius.circular(8),
              ),
              child: Icon(icon, color: color, size: 20),
            ),
            const SizedBox(width: 12),
            Expanded(
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                mainAxisSize: MainAxisSize.min,
                children: [
                  Text(value,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 22, fontWeight: FontWeight.bold)),
                  Text(label,
                      maxLines: 1,
                      overflow: TextOverflow.ellipsis,
                      style: const TextStyle(fontSize: 11.5, color: Colors.black54)),
                ],
              ),
            ),
          ],
        ),
      ),
    );
  }

  // ---- Breakdown panels (fixed-height row; each panel scrolls internally if needed) ----

  Widget _breakdowns(DashboardState state) {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.stretch,
      children: [
        Expanded(child: _classificationPanel(state)),
        const SizedBox(width: 12),
        Expanded(
            child: _barPanel('Top source topics (recent)',
                _countBy(state.recentFailures, (f) => f.originalTopic), Colors.indigo)),
        const SizedBox(width: 12),
        Expanded(
            child: _barPanel('Top source apps (recent)',
                _countBy(state.recentFailures, (f) => f.sourceApp), Colors.teal)),
      ],
    );
  }

  Widget _classificationPanel(DashboardState state) {
    const order = ['TRANSIENT', 'INFRASTRUCTURE', 'BUSINESS', 'POISON', 'UNKNOWN'];
    final data = {for (final c in order) c: state.classificationCounts[c] ?? 0};
    final max = data.values.isEmpty ? 1 : data.values.fold<int>(1, (a, b) => a > b ? a : b);
    return _panel(
      'By classification',
      Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: data.entries
            .map((e) => _bar(e.key, e.value, max, classificationColor(e.key)))
            .toList(),
      ),
    );
  }

  Widget _barPanel(String title, Map<String, int> data, Color color) {
    final max = data.values.isEmpty ? 1 : data.values.fold<int>(1, (a, b) => a > b ? a : b);
    return _panel(
      title,
      data.isEmpty
          ? const Padding(
              padding: EdgeInsets.symmetric(vertical: 8),
              child: Text('No recent failures.', style: TextStyle(color: Colors.black54)))
          : Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: data.entries.map((e) => _bar(e.key, e.value, max, color)).toList(),
            ),
    );
  }

  Widget _bar(String label, int value, int max, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Expanded(
                child: Text(label,
                    overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12)),
              ),
              Text('$value', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
            ],
          ),
          const SizedBox(height: 3),
          ClipRRect(
            borderRadius: BorderRadius.circular(3),
            child: LinearProgressIndicator(
              value: max == 0 ? 0 : value / max,
              minHeight: 6,
              backgroundColor: Colors.black.withOpacity(0.06),
              color: color,
            ),
          ),
        ],
      ),
    );
  }

  Widget _panel(String title, Widget child) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 13)),
            const SizedBox(height: 8),
            Expanded(child: SingleChildScrollView(child: child)),
          ],
        ),
      ),
    );
  }

  /// Count occurrences of a key across the recent failures, top 6 descending.
  Map<String, int> _countBy(List<FailureSummary> failures, String? Function(FailureSummary) key) {
    final m = <String, int>{};
    for (final f in failures) {
      final k = key(f);
      if (k == null || k.isEmpty) continue;
      m[k] = (m[k] ?? 0) + 1;
    }
    final entries = m.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
    return {for (final e in entries.take(6)) e.key: e.value};
  }

  // ---- Recent failures table (intrinsic height; the page scrolls) ----

  Widget _recentTable(BuildContext context, DashboardState state) {
    final rows = state.recentFailures.take(15).toList();
    if (rows.isEmpty) {
      return Card(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(24),
            child: Text(state.connected ? 'Waiting for failures…' : 'Connecting…',
                style: Theme.of(context).textTheme.bodyMedium),
          ),
        ),
      );
    }
    return Card(
      clipBehavior: Clip.antiAlias,
      child: SingleChildScrollView(
        scrollDirection: Axis.horizontal,
        child: ConstrainedBox(
          constraints: BoxConstraints(minWidth: MediaQuery.of(context).size.width - 120),
          child: DataTable(
            showCheckboxColumn: false,
            columns: const [
              DataColumn(label: Text('Class')),
              DataColumn(label: Text('State')),
              DataColumn(label: Text('Source topic')),
              DataColumn(label: Text('Exception')),
              DataColumn(label: Text('Updated')),
            ],
            rows: rows.map((f) => _recentRow(context, f)).toList(),
          ),
        ),
      ),
    );
  }

  DataRow _recentRow(BuildContext context, FailureSummary f) {
    return DataRow(
      onSelectChanged: (_) => Navigator.of(context).push(
          MaterialPageRoute(builder: (_) => FailureDetailScreen(correlationId: f.correlationId))),
      cells: [
        DataCell(TagChip(label: f.classification, color: classificationColor(f.classification))),
        DataCell(TagChip(label: f.state, color: stateColor(f.state))),
        DataCell(Text(f.originalTopic ?? '—')),
        DataCell(ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 420),
          child: Text(f.exceptionClass ?? f.rootCauseSignature ?? f.correlationId,
              overflow: TextOverflow.ellipsis),
        )),
        DataCell(Text(formatRelative(f.updatedAt))),
      ],
    );
  }
}
