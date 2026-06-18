import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';

/// Adaptive anomaly detection (§16): root-cause / topic series whose latest-bucket failure rate is
/// anomalous versus their OWN recent baseline (not a fixed threshold). Read-only projection of
/// {@code GET /api/anomalies} — catches spikes relative to normal, and brand-new signatures as novelty.
class AnomaliesScreen extends StatefulWidget {
  const AnomaliesScreen({super.key});

  @override
  State<AnomaliesScreen> createState() => _AnomaliesScreenState();
}

class _AnomaliesScreenState extends State<AnomaliesScreen> {
  AnomalyReport? _data;
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
      final data = await context.read<ApiClient>().getAnomalies();
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
              Text('Anomalies', style: Theme.of(context).textTheme.titleLarge),
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
          Text(_subtitle(), style: const TextStyle(color: Colors.black54, fontSize: 12)),
          const SizedBox(height: 16),
          if (_error != null)
            Text(_error!, style: const TextStyle(color: Colors.red))
          else if (_data != null)
            ..._content(_data!),
        ],
      ),
    );
  }

  String _subtitle() {
    final d = _data;
    if (d == null) {
      return 'Failure-rate spikes versus each series\' own baseline.';
    }
    final bucketMin = (d.bucketMillis / 60000).round();
    final lookbackMin = (d.lookbackMillis / 60000).round();
    return 'Latest ${bucketMin}m bucket vs a ${lookbackMin}m baseline, '
        'flagged above mean + ${d.sensitivity.toStringAsFixed(1)}σ. Read-only.';
  }

  List<Widget> _content(AnomalyReport d) {
    if (d.anomalies.isEmpty) {
      return const [
        Padding(
          padding: EdgeInsets.only(top: 40),
          child: Center(
            child: Column(
              children: [
                Icon(Icons.check_circle_outline, size: 48, color: Colors.green),
                SizedBox(height: 12),
                Text('No anomalies — failure rates are within normal baselines'),
              ],
            ),
          ),
        ),
      ];
    }
    return [for (final a in d.anomalies) _tile(a)];
  }

  Widget _tile(Anomaly a) {
    final color = a.dimension == 'TOPIC' ? Colors.indigo : Colors.deepOrange;
    return Card(
      elevation: 0,
      margin: const EdgeInsets.only(bottom: 10),
      shape: RoundedRectangleBorder(
        side: const BorderSide(color: Colors.black12),
        borderRadius: BorderRadius.circular(8),
      ),
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(
              children: [
                TagChip(label: a.dimension, color: color),
                const SizedBox(width: 8),
                Expanded(child: Text(a.key, style: const TextStyle(fontWeight: FontWeight.w600))),
                Container(
                  padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
                  decoration: BoxDecoration(
                    color: Colors.red.withOpacity(0.1),
                    borderRadius: BorderRadius.circular(6),
                  ),
                  child: Text('${a.score.toStringAsFixed(1)}σ',
                      style: const TextStyle(
                          color: Colors.red, fontWeight: FontWeight.w700, fontSize: 12)),
                ),
              ],
            ),
            const SizedBox(height: 8),
            Text(
              '${a.recentCount} in the latest bucket  ·  baseline ≈ '
              '${a.baselineMean.toStringAsFixed(2)}/bucket  ·  expected ≤ ${a.expected.toStringAsFixed(1)}',
              style: const TextStyle(color: Colors.black54, fontSize: 12),
            ),
            if (a.sampleCorrelationId != null && a.sampleCorrelationId!.isNotEmpty) ...[
              const SizedBox(height: 4),
              Text('e.g. ${a.sampleCorrelationId}',
                  style: const TextStyle(color: Colors.black45, fontSize: 11)),
            ],
          ],
        ),
      ),
    );
  }
}
