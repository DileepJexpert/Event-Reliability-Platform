import 'package:flutter/material.dart';
import 'package:intl/intl.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';

/// Financial exposure / "value at risk" (§16): how much money is tied up in stuck (un-recovered)
/// failures. Read-only projection of {@code GET /api/exposure} — headline totals per currency,
/// breakdowns by topic and team, and the biggest single stuck exposures. No raw payloads, just figures.
class ExposureScreen extends StatefulWidget {
  const ExposureScreen({super.key});

  @override
  State<ExposureScreen> createState() => _ExposureScreenState();
}

class _ExposureScreenState extends State<ExposureScreen> {
  static final NumberFormat _money = NumberFormat('#,##0.00');

  Exposure? _data;
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
      final data = await context.read<ApiClient>().getExposure();
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

  @override
  Widget build(BuildContext context) {
    return SingleChildScrollView(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 24),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(
            children: [
              Text('Value at risk', style: Theme.of(context).textTheme.titleLarge),
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
          const Text('Money tied up in stuck (un-recovered) failures. Read-only — aggregated figures only.',
              style: TextStyle(color: Colors.black54, fontSize: 12)),
          const SizedBox(height: 16),
          if (_error != null)
            Text(_error!, style: const TextStyle(color: Colors.red))
          else if (_data != null)
            ..._content(_data!),
        ],
      ),
    );
  }

  List<Widget> _content(Exposure d) {
    return [
      Wrap(
        spacing: 12,
        runSpacing: 12,
        children: [
          if (d.atRiskByCurrency.isEmpty)
            _card('At risk', '—', 'no amounts found', Colors.blueGrey)
          else
            for (final e in d.atRiskByCurrency.entries)
              _card('At risk · ${e.key}', '${e.key} ${_money.format(e.value)}',
                  '${d.atRiskCount} stuck event(s)', Colors.red),
          _card('Stuck events', '${d.atRiskCount}',
              '${d.withoutAmount} without an amount', Colors.deepOrange),
          _card('Oldest stuck', formatRelative(d.oldestAtRiskAt), 'first failed', Colors.orange),
        ],
      ),
      const SizedBox(height: 24),
      _breakdown('By topic', d.byTopic),
      const SizedBox(height: 20),
      _breakdown('By team', d.byTeam),
      const SizedBox(height: 20),
      _topExposures(d.topExposures),
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

  Widget _breakdown(String title, List<ExposureGroup> groups) {
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
                  Expanded(flex: 3, child: Text(g.name)),
                  Expanded(
                    flex: 1,
                    child: Text('${g.count}',
                        textAlign: TextAlign.right, style: const TextStyle(color: Colors.black54)),
                  ),
                  Expanded(
                    flex: 3,
                    child: Text(_amounts(g.amountByCurrency),
                        textAlign: TextAlign.right,
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                  ),
                ],
              ),
            ),
      ],
    );
  }

  Widget _topExposures(List<ExposureItem> items) {
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: [
        Text('Biggest stuck exposures', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        if (items.isEmpty)
          const Text('—', style: TextStyle(color: Colors.black45))
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
                    title: Text('${it.currency} ${_money.format(it.amount)}',
                        style: const TextStyle(fontWeight: FontWeight.w600)),
                    subtitle: Text('${it.topic} · ${it.team} · ${it.correlationId}'),
                    trailing: TagChip(label: it.state, color: stateColor(it.state)),
                  ),
              ],
            ),
          ),
      ],
    );
  }

  String _amounts(Map<String, double> m) {
    if (m.isEmpty) return '—';
    return m.entries.map((e) => '${e.key} ${_money.format(e.value)}').join('  ·  ');
  }
}
