import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';

/// Reconciliation / completeness (§16): has every failed transaction reached completion? A failure is
/// completed once it reached RESOLVED (a retry succeeded) or REPLAYED (re-driven); everything else is
/// the open gap. Read-only projection of {@code GET /api/reconciliation} — headline completion, per
/// source / topic breakdowns, and the oldest unreconciled items as a worklist.
class ReconciliationScreen extends StatefulWidget {
  const ReconciliationScreen({super.key});

  @override
  State<ReconciliationScreen> createState() => _ReconciliationScreenState();
}

class _ReconciliationScreenState extends State<ReconciliationScreen> {
  ReconciliationReport? _data;
  bool _loading = true;
  String? _error;

  @override
  void initState() {
    super.initState();
    _load();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final data = await context.read<ApiClient>().getReconciliation();
      if (!mounted) return;
      setState(() {
        _data = data;
        _loading = false;
      });
    } catch (e) {
      if (!mounted) return;
      setState(() {
        _error = '$e';
        _loading = false;
      });
    }
  }

  static String _pct(double rate) => '${(rate * 100).toStringAsFixed(1)}%';

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('Reconciliation', style: Theme.of(context).textTheme.titleLarge),
              const SizedBox(width: 12),
              if (_loading)
                const SizedBox(
                    width: 14, height: 14, child: CircularProgressIndicator(strokeWidth: 2)),
              const Spacer(),
              IconButton(
                tooltip: 'Refresh',
                icon: const Icon(Icons.refresh, size: 20),
                onPressed: _loading ? null : _load,
              ),
            ],
          ),
          const SizedBox(height: 4),
          const Text('Has every failed transaction reached completion? Completed = resolved or replayed; '
              'the rest is the open gap.', style: TextStyle(color: Colors.black54, fontSize: 12)),
          const SizedBox(height: 16),
          if (_error != null)
            Text(_error!, style: const TextStyle(color: Colors.red))
          else if (_data != null)
            ..._content(_data!),
        ],
      ),
    );
  }

  List<Widget> _content(ReconciliationReport d) {
    final reconciled = d.open == 0;
    return [
      Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          _card('Completion', _pct(d.completionRate), 'of ${d.totalCaptured} captured',
              reconciled ? Colors.green : Colors.orange),
          _card('Completed', '${d.completed}', 'resolved / replayed', Colors.green),
          _card('Open gap', '${d.open}', 'not yet complete', d.open == 0 ? Colors.blueGrey : Colors.red),
          _card('Oldest open', formatRelative(d.oldestOpenAt), 'first failed', Colors.deepOrange),
        ],
      ),
      const SizedBox(height: 24),
      _breakdown('By source', d.bySource),
      const SizedBox(height: 20),
      _breakdown('By topic', d.byTopic),
      const SizedBox(height: 20),
      _worklist(d.oldestOpen),
    ];
  }

  Widget _card(String title, String value, String sub, Color color) {
    return Container(
      width: 240,
      padding: const EdgeInsets.all(16),
      decoration: BoxDecoration(
        color: color.withOpacity(0.06),
        borderRadius: BorderRadius.circular(10),
        border: Border.all(color: color.withOpacity(0.3)),
      ),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text(title, style: const TextStyle(fontSize: 12, color: Colors.black54)),
          const SizedBox(height: 6),
          Text(value, style: TextStyle(fontSize: 22, fontWeight: FontWeight.w700, color: color)),
          const SizedBox(height: 4),
          Text(sub, style: const TextStyle(fontSize: 11, color: Colors.black45)),
        ],
      ),
    );
  }

  Widget _breakdown(String title, List<ReconciliationGroup> groups) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text(title, style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (groups.isEmpty)
          const Text('—', style: TextStyle(color: Colors.black45))
        else
          for (final g in groups.take(10))
            Padding(
              padding: const EdgeInsets.symmetric(vertical: 4),
              child: Row(
                children: [
                  Expanded(flex: 4, child: Text(g.name)),
                  Expanded(
                    flex: 2,
                    child: Text('${g.completed}/${g.total} done',
                        textAlign: TextAlign.right, style: const TextStyle(color: Colors.black54)),
                  ),
                  Expanded(
                    flex: 2,
                    child: Text(g.open == 0 ? 'reconciled' : '${g.open} open',
                        textAlign: TextAlign.right,
                        style: TextStyle(
                            color: g.open == 0 ? Colors.green : Colors.red,
                            fontWeight: FontWeight.w600)),
                  ),
                  Expanded(
                    flex: 1,
                    child: Text(_pct(g.completionRate),
                        textAlign: TextAlign.right, style: const TextStyle(fontWeight: FontWeight.w600)),
                  ),
                ],
              ),
            ),
      ],
    );
  }

  Widget _worklist(List<ReconciliationItem> items) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Oldest unreconciled', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (items.isEmpty)
          const Text('Nothing open — fully reconciled.', style: TextStyle(color: Colors.green))
        else
          Card(
            elevation: 0,
            shape: RoundedRectangleBorder(
              side: const BorderSide(color: Colors.black12),
              borderRadius: BorderRadius.circular(8),
            ),
            child: Column(
              children: [
                for (final it in items)
                  ListTile(
                    dense: true,
                    title: Text(it.correlationId, style: const TextStyle(fontWeight: FontWeight.w600)),
                    subtitle: Text('${it.topic} · ${it.team} · ${formatRelative(it.firstFailedAt)}'),
                    trailing: TagChip(label: it.state, color: stateColor(it.state)),
                  ),
              ],
            ),
          ),
      ],
    );
  }
}
