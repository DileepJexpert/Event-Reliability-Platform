import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';

/// Trends (§16): on-demand analytics over the current failure set — headline counts, resolution rate
/// and MTTR, a 14-day daily series, and breakdowns by classification / state and top source topics /
/// apps. Reads {@code GET /api/trends}; refresh re-fetches. Pure projection of the backend DTO.
class TrendsScreen extends StatefulWidget {
  const TrendsScreen({super.key});

  @override
  State<TrendsScreen> createState() => _TrendsScreenState();
}

class _TrendsScreenState extends State<TrendsScreen> {
  Trends? _data;
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
      final data = await context.read<ApiClient>().getTrends();
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
              Text('Trends', style: Theme.of(context).textTheme.titleLarge),
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
          Text('Aggregated over the current failure set; the daily chart covers the last 14 days.',
              style: Theme.of(context).textTheme.bodySmall),
          const SizedBox(height: 14),
          if (_error != null)
            _errorCard()
          else if (_data == null)
            const Padding(
                padding: EdgeInsets.all(48), child: Center(child: CircularProgressIndicator()))
          else
            ..._content(context, _data!),
        ],
      ),
    );
  }

  Widget _errorCard() => Card(
        color: Colors.red.withOpacity(0.06),
        child: Padding(
          padding: const EdgeInsets.all(16),
          child: Row(children: [
            const Icon(Icons.error_outline, color: Colors.red),
            const SizedBox(width: 10),
            Expanded(child: Text('Could not load trends: $_error')),
            TextButton(onPressed: _load, child: const Text('Retry')),
          ]),
        ),
      );

  List<Widget> _content(BuildContext context, Trends t) {
    return [
      _kpiRow(t),
      const SizedBox(height: 18),
      Text('Failures per day (last 14 days)', style: Theme.of(context).textTheme.titleMedium),
      const SizedBox(height: 8),
      Card(
        child: Padding(
          padding: const EdgeInsets.fromLTRB(14, 14, 14, 10),
          child: _dailyChart(t.daily),
        ),
      ),
      const SizedBox(height: 16),
      SizedBox(
        height: 260,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: _classificationPanel(t)),
            const SizedBox(width: 12),
            Expanded(child: _statePanel(t)),
          ],
        ),
      ),
      const SizedBox(height: 12),
      SizedBox(
        height: 260,
        child: Row(
          crossAxisAlignment: CrossAxisAlignment.stretch,
          children: [
            Expanded(child: _namePanel('Top source topics', t.topTopics, Colors.indigo)),
            const SizedBox(width: 12),
            Expanded(child: _namePanel('Top source apps', t.topSourceApps, Colors.teal)),
          ],
        ),
      ),
    ];
  }

  Widget _kpiRow(Trends t) {
    final rate = '${(t.resolutionRate * 100).toStringAsFixed(0)}%';
    return Row(
      children: [
        Expanded(child: _kpi('Total failures', '${t.total}', Icons.report_gmailerrorred, Colors.blueGrey)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Open', '${t.open}', Icons.pending_actions, Colors.blue)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Resolved', '${t.resolved}', Icons.check_circle, Colors.green,
            sub: '$rate resolution rate')),
        const SizedBox(width: 12),
        Expanded(child: _kpi('Parked', '${t.parked}', Icons.inventory_2, Colors.deepOrange)),
        const SizedBox(width: 12),
        Expanded(child: _kpi('MTTR', _formatMttr(t.mttrMillis), Icons.timer, Colors.purple,
            sub: 'mean time to resolve')),
      ],
    );
  }

  static String _formatMttr(int? ms) {
    if (ms == null) return '—';
    final d = Duration(milliseconds: ms);
    if (d.inHours >= 1) return '${d.inHours}h ${d.inMinutes % 60}m';
    if (d.inMinutes >= 1) return '${d.inMinutes}m ${d.inSeconds % 60}s';
    return '${d.inSeconds}s';
  }

  Widget _kpi(String label, String value, IconData icon, Color color, {String? sub}) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Row(children: [
          Container(
            padding: const EdgeInsets.all(9),
            decoration:
                BoxDecoration(color: color.withOpacity(0.12), borderRadius: BorderRadius.circular(8)),
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
                Text(sub ?? label,
                    maxLines: 1,
                    overflow: TextOverflow.ellipsis,
                    style: const TextStyle(fontSize: 11.5, color: Colors.black54)),
              ],
            ),
          ),
        ]),
      ),
    );
  }

  Widget _dailyChart(List<DailyCount> daily) {
    if (daily.isEmpty) {
      return const Padding(
          padding: EdgeInsets.all(12),
          child: Text('No data.', style: TextStyle(color: Colors.black54)));
    }
    final max = daily.fold<int>(1, (a, b) => a > b.count ? a : b.count);
    return SizedBox(
      height: 150,
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.end,
        children: daily.map((d) {
          final label = d.date.length >= 10 ? d.date.substring(5) : d.date; // MM-dd
          final barHeight = (d.count / max * 120).clamp(d.count > 0 ? 3.0 : 0.0, 120.0);
          return Expanded(
            child: Padding(
              padding: const EdgeInsets.symmetric(horizontal: 3),
              child: Tooltip(
                message: '${d.date}: ${d.count}',
                child: Column(
                  mainAxisAlignment: MainAxisAlignment.end,
                  children: [
                    Container(
                      height: barHeight,
                      decoration: BoxDecoration(
                        color: Colors.indigo.withOpacity(0.85),
                        borderRadius: const BorderRadius.vertical(top: Radius.circular(3)),
                      ),
                    ),
                    const SizedBox(height: 4),
                    Text(label, style: const TextStyle(fontSize: 9, color: Colors.black54)),
                  ],
                ),
              ),
            ),
          );
        }).toList(),
      ),
    );
  }

  Widget _classificationPanel(Trends t) {
    const order = ['TRANSIENT', 'INFRASTRUCTURE', 'BUSINESS', 'POISON', 'UNKNOWN'];
    final data = {for (final c in order) c: t.byClassification[c] ?? 0};
    return _panel('By classification', _bars(data, classificationColor));
  }

  Widget _statePanel(Trends t) {
    final entries = t.byState.entries.toList()..sort((a, b) => b.value.compareTo(a.value));
    final data = {for (final e in entries) e.key: e.value};
    return _panel('By state', data.isEmpty ? _empty() : _bars(data, stateColor));
  }

  Widget _namePanel(String title, List<NameCount> items, Color color) {
    final data = {for (final e in items) e.name: e.count};
    return _panel(title, data.isEmpty ? _empty() : _bars(data, (_) => color));
  }

  Widget _bars(Map<String, int> data, Color Function(String) color) {
    final max = data.values.isEmpty ? 1 : data.values.fold<int>(1, (a, b) => a > b ? a : b);
    return Column(
      crossAxisAlignment: CrossAxisAlignment.start,
      children: data.entries.map((e) => _bar(e.key, e.value, max, color(e.key))).toList(),
    );
  }

  Widget _empty() => const Padding(
      padding: EdgeInsets.symmetric(vertical: 8),
      child: Text('No data yet.', style: TextStyle(color: Colors.black54)));

  Widget _bar(String label, int value, int max, Color color) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 5),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Expanded(
                child: Text(label,
                    overflow: TextOverflow.ellipsis, style: const TextStyle(fontSize: 12))),
            Text('$value', style: const TextStyle(fontSize: 12, fontWeight: FontWeight.w700)),
          ]),
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
}
