import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../widgets/common.dart';

/// Reconciliation / completeness (§16). Two views in one:
///  - <b>Backlog completeness</b>: of the captured failures, completed (RESOLVED / REPLAYED) vs the
///    open gap, by source / topic, with the oldest unreconciled worklist.
///  - <b>Declared batches</b>: producers declare "expect N events on a source"; Brod reports the
///    shortfall (still-stuck) against that declared total. Read-only except declaring an expectation.
class ReconciliationScreen extends StatefulWidget {
  const ReconciliationScreen({super.key});

  @override
  State<ReconciliationScreen> createState() => _ReconciliationScreenState();
}

class _ReconciliationScreenState extends State<ReconciliationScreen> {
  ReconciliationReport? _data;
  List<ExpectationReconciliation> _expectations = const [];
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
      final api = context.read<ApiClient>();
      final results = await Future.wait([api.getReconciliation(), api.getExpectations()]);
      if (!mounted) return;
      setState(() {
        _data = results[0] as ReconciliationReport;
        _expectations = results[1] as List<ExpectationReconciliation>;
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
    final isOperator = context.watch<AuthService>().state.isOperator;
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
              if (isOperator)
                TextButton.icon(
                  icon: const Icon(Icons.add, size: 18),
                  label: const Text('Declare batch'),
                  onPressed: _declareExpectation,
                ),
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
      _declaredBatches(),
      _breakdown('By source', d.bySource),
      const SizedBox(height: 20),
      _breakdown('By topic', d.byTopic),
      const SizedBox(height: 20),
      _worklist(d.oldestOpen),
    ];
  }

  Widget _declaredBatches() {
    if (_expectations.isEmpty) {
      return const SizedBox.shrink();
    }
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Declared batches', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        Card(
          elevation: 0,
          margin: const EdgeInsets.only(bottom: 24),
          shape: RoundedRectangleBorder(
            side: const BorderSide(color: Colors.black12),
            borderRadius: BorderRadius.circular(8),
          ),
          child: Column(
            children: [
              for (final e in _expectations)
                ListTile(
                  dense: true,
                  title: Text('${e.label?.isNotEmpty == true ? e.label : e.key}  ·  ${e.source}',
                      style: const TextStyle(fontWeight: FontWeight.w600)),
                  subtitle: Text('expected ${e.expectedCount} · ${e.completed} complete · '
                      '${e.open} stuck · ${_pct(e.completionRate)}'),
                  trailing: TagChip(
                    label: e.status,
                    color: e.status == 'RECONCILED' ? Colors.green : Colors.red,
                  ),
                ),
            ],
          ),
        ),
      ],
    );
  }

  Future<void> _declareExpectation() async {
    final keyCtrl = TextEditingController();
    final sourceCtrl = TextEditingController();
    final countCtrl = TextEditingController();
    final labelCtrl = TextEditingController();

    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Declare a reconciliation batch'),
        content: SizedBox(
          width: 420,
          child: Column(
            mainAxisSize: MainAxisSize.min,
            children: [
              TextField(
                controller: keyCtrl,
                autofocus: true,
                decoration: const InputDecoration(
                    labelText: 'Key', hintText: 'unique id, e.g. eod-2026-06-19'),
              ),
              TextField(
                controller: sourceCtrl,
                decoration: const InputDecoration(
                    labelText: 'Source', hintText: 'topic, e.g. settlement.events'),
              ),
              TextField(
                controller: countCtrl,
                keyboardType: TextInputType.number,
                decoration: const InputDecoration(labelText: 'Expected count'),
              ),
              TextField(
                controller: labelCtrl,
                decoration: const InputDecoration(labelText: 'Label (optional)'),
              ),
            ],
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Declare')),
        ],
      ),
    );
    if (ok != true) return;

    final count = int.tryParse(countCtrl.text.trim()) ?? 0;
    if (keyCtrl.text.trim().isEmpty || sourceCtrl.text.trim().isEmpty || count <= 0) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          const SnackBar(content: Text('Key, source and a positive expected count are required')));
      return;
    }
    try {
      await context.read<ApiClient>().declareExpectation(
            key: keyCtrl.text.trim(),
            source: sourceCtrl.text.trim(),
            expectedCount: count,
            label: labelCtrl.text.trim(),
          );
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(const SnackBar(content: Text('Batch declared')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Declare failed: $e')));
    }
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
