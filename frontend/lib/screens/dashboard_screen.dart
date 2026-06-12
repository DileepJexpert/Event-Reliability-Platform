import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../state/dashboard_state.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Dashboard (§16): live failure rate, counts by classification and active incident banners,
/// updating in real time from the SSE feed.
class DashboardScreen extends StatelessWidget {
  const DashboardScreen({super.key});

  @override
  Widget build(BuildContext context) {
    return Consumer<DashboardState>(
      builder: (context, state, _) {
        return Padding(
          padding: const EdgeInsets.all(20),
          child: Column(
            crossAxisAlignment: CrossAxisAlignment.start,
            children: [
              Row(
                children: [
                  Text('Dashboard', style: Theme.of(context).textTheme.headlineSmall),
                  const SizedBox(width: 12),
                  Icon(Icons.circle, size: 12, color: state.connected ? Colors.green : Colors.grey),
                  const SizedBox(width: 4),
                  Text(state.connected ? 'live' : 'reconnecting…',
                      style: Theme.of(context).textTheme.bodySmall),
                  const Spacer(),
                  Text('${state.totalFailureEvents} failure events this session',
                      style: Theme.of(context).textTheme.bodySmall),
                ],
              ),
              const SizedBox(height: 16),
              if (state.activeIncidents.isNotEmpty) _incidentBanner(context, state),
              const SizedBox(height: 16),
              _classificationCards(state),
              const SizedBox(height: 16),
              Text('Recent failures', style: Theme.of(context).textTheme.titleMedium),
              const SizedBox(height: 8),
              Expanded(child: _recentList(context, state)),
            ],
          ),
        );
      },
    );
  }

  Widget _incidentBanner(BuildContext context, DashboardState state) {
    return Card(
      color: Colors.red.withOpacity(0.08),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Row(
          children: [
            const Icon(Icons.warning_amber, color: Colors.red),
            const SizedBox(width: 8),
            Expanded(
              child: Text(
                '${state.activeIncidents.length} active incident(s) — '
                '${state.activeIncidents.first.rootCause} '
                '(${state.activeIncidents.first.count} failures)',
                style: const TextStyle(fontWeight: FontWeight.w600),
              ),
            ),
          ],
        ),
      ),
    );
  }

  Widget _classificationCards(DashboardState state) {
    const order = ['TRANSIENT', 'INFRASTRUCTURE', 'BUSINESS', 'POISON', 'UNKNOWN'];
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      children: order.map((c) {
        final count = state.classificationCounts[c] ?? 0;
        return SizedBox(
          width: 170,
          child: Card(
            child: Padding(
              padding: const EdgeInsets.all(14),
              child: Column(
                crossAxisAlignment: CrossAxisAlignment.start,
                children: [
                  TagChip(label: c, color: classificationColor(c)),
                  const SizedBox(height: 8),
                  Text('$count', style: const TextStyle(fontSize: 26, fontWeight: FontWeight.bold)),
                ],
              ),
            ),
          ),
        );
      }).toList(),
    );
  }

  Widget _recentList(BuildContext context, DashboardState state) {
    if (state.recentFailures.isEmpty) {
      return const Center(child: Text('Waiting for failures…'));
    }
    return ListView.separated(
      itemCount: state.recentFailures.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, i) {
        final f = state.recentFailures[i];
        return ListTile(
          dense: true,
          leading: TagChip(label: f.classification, color: classificationColor(f.classification)),
          title: Text(f.exceptionClass ?? f.rootCauseSignature ?? f.correlationId),
          subtitle: Text('${f.originalTopic ?? '?'} · ${f.correlationId}'),
          trailing: TagChip(label: f.state, color: stateColor(f.state)),
          onTap: () => Navigator.of(context).push(MaterialPageRoute(
              builder: (_) => FailureDetailScreen(correlationId: f.correlationId))),
        );
      },
    );
  }
}
