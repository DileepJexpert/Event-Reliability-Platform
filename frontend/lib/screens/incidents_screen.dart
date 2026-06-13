import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Incidents (§16): active systemic patterns with drill-in and the one-click bulk-replay action,
/// which recovers the whole cohort once the upstream is fixed (§13). Bulk replay is OPERATOR-only.
class IncidentsScreen extends StatefulWidget {
  const IncidentsScreen({super.key});

  @override
  State<IncidentsScreen> createState() => _IncidentsScreenState();
}

class _IncidentsScreenState extends State<IncidentsScreen> {
  List<Incident>? _incidents;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final incidents = await context.read<ApiClient>().listIncidents();
      setState(() => _incidents = incidents);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _bulkReplay(Incident incident) async {
    final reason = await _askReason(incident);
    if (reason == null) return;
    try {
      await context.read<ApiClient>().bulkReplay(incident.id, reason: reason.isEmpty ? null : reason);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Bulk replay requested — awaiting approval')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Bulk replay failed: $e')));
    }
  }

  Future<String?> _askReason(Incident incident) {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Bulk replay — ${incident.count} messages'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Root cause: ${incident.rootCause}'),
            Text('Source topic: ${incident.sourceTopic}'),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              decoration: const InputDecoration(labelText: 'Reason (audited)'),
              autofocus: true,
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, ctrl.text.trim()),
              child: const Text('Replay cohort')),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isOperator = context.watch<AuthService>().state.isOperator;
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('Incidents', style: Theme.of(context).textTheme.headlineSmall),
              const Spacer(),
              IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
            ],
          ),
          const SizedBox(height: 12),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
          Expanded(child: _list(isOperator)),
        ],
      ),
    );
  }

  Widget _list(bool isOperator) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final incidents = _incidents ?? const [];
    if (incidents.isEmpty) {
      return const Center(child: Text('No incidents — nothing systemic detected.'));
    }
    return ListView.separated(
      itemCount: incidents.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, i) {
        final inc = incidents[i];
        return Card(
          child: Padding(
            padding: const EdgeInsets.all(14),
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                Row(
                  children: [
                    TagChip(
                        label: inc.status,
                        color: inc.isActive ? Colors.red : Colors.green),
                    const SizedBox(width: 8),
                    Expanded(
                      child: Text(inc.rootCause,
                          style: const TextStyle(fontWeight: FontWeight.bold, fontSize: 16)),
                    ),
                    Text('${inc.count} failures',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                  ],
                ),
                const SizedBox(height: 6),
                Text('Source: ${inc.sourceTopic} · threshold ${inc.threshold} · '
                    'window ${formatTimestamp(inc.windowStart)}'),
                const SizedBox(height: 10),
                Row(
                  children: [
                    if (inc.exampleCorrelationId != null)
                      OutlinedButton.icon(
                        icon: const Icon(Icons.open_in_new, size: 16),
                        label: const Text('Example message'),
                        onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                            builder: (_) =>
                                FailureDetailScreen(correlationId: inc.exampleCorrelationId!))),
                      ),
                    const Spacer(),
                    if (isOperator && inc.isActive)
                      FilledButton.icon(
                        icon: const Icon(Icons.replay_circle_filled),
                        label: const Text('Bulk replay'),
                        onPressed: () => _bulkReplay(inc),
                      ),
                  ],
                ),
              ],
            ),
          ),
        );
      },
    );
  }
}
