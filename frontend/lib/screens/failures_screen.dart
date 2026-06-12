import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Failures list (§16): filter by status / topic / classification and search by correlation id.
class FailuresScreen extends StatefulWidget {
  const FailuresScreen({super.key});

  @override
  State<FailuresScreen> createState() => _FailuresScreenState();
}

class _FailuresScreenState extends State<FailuresScreen> {
  static const _states = [
    '', 'RECEIVED', 'CLASSIFIED', 'RETRY_SCHEDULED', 'RETRYING', 'RESOLVED',
    'EXHAUSTED_PARKED', 'ROUTED_BUSINESS', 'QUARANTINED_POISON', 'PARKED_UNKNOWN', 'REPLAYED',
  ];
  static const _classes = ['', 'TRANSIENT', 'INFRASTRUCTURE', 'BUSINESS', 'POISON', 'UNKNOWN'];

  String _status = '';
  String _classification = '';
  final _topicCtrl = TextEditingController();
  final _searchCtrl = TextEditingController();

  int _page = 0;
  PageResult<FailureSummary>? _result;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) => _load());
  }

  @override
  void dispose() {
    _topicCtrl.dispose();
    _searchCtrl.dispose();
    super.dispose();
  }

  Future<void> _load() async {
    setState(() {
      _loading = true;
      _error = null;
    });
    try {
      final result = await context.read<ApiClient>().listFailures(
            status: _status.isEmpty ? null : _status,
            classification: _classification.isEmpty ? null : _classification,
            topic: _topicCtrl.text.trim().isEmpty ? null : _topicCtrl.text.trim(),
            page: _page,
          );
      setState(() => _result = result);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _openDetail(String correlationId) {
    Navigator.of(context).push(MaterialPageRoute(
        builder: (_) => FailureDetailScreen(correlationId: correlationId)));
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Failures', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 12),
          _filters(),
          const SizedBox(height: 12),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
          Expanded(child: _table()),
          _pager(),
        ],
      ),
    );
  }

  Widget _filters() {
    return Wrap(
      spacing: 12,
      runSpacing: 12,
      crossAxisAlignment: WrapCrossAlignment.center,
      children: [
        _dropdown('Status', _states, _status, (v) => setState(() => _status = v ?? '')),
        _dropdown('Classification', _classes, _classification,
            (v) => setState(() => _classification = v ?? '')),
        SizedBox(
          width: 200,
          child: TextField(
            controller: _topicCtrl,
            decoration: const InputDecoration(labelText: 'Original topic', isDense: true),
          ),
        ),
        FilledButton.icon(
          onPressed: () {
            _page = 0;
            _load();
          },
          icon: const Icon(Icons.filter_alt),
          label: const Text('Apply'),
        ),
        SizedBox(
          width: 260,
          child: TextField(
            controller: _searchCtrl,
            decoration: InputDecoration(
              labelText: 'Go to correlation id',
              isDense: true,
              suffixIcon: IconButton(
                icon: const Icon(Icons.search),
                onPressed: () {
                  final id = _searchCtrl.text.trim();
                  if (id.isNotEmpty) _openDetail(id);
                },
              ),
            ),
            onSubmitted: (id) {
              if (id.trim().isNotEmpty) _openDetail(id.trim());
            },
          ),
        ),
      ],
    );
  }

  Widget _dropdown(String label, List<String> items, String value, ValueChanged<String?> onChanged) {
    return SizedBox(
      width: 200,
      child: InputDecorator(
        decoration: InputDecoration(labelText: label, isDense: true),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: value,
            isExpanded: true,
            items: items
                .map((s) => DropdownMenuItem(value: s, child: Text(s.isEmpty ? 'Any' : s)))
                .toList(),
            onChanged: onChanged,
          ),
        ),
      ),
    );
  }

  Widget _table() {
    if (_loading && _result == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final rows = _result?.content ?? const [];
    if (rows.isEmpty) {
      return const Center(child: Text('No failures match the filter.'));
    }
    return ListView.separated(
      itemCount: rows.length,
      separatorBuilder: (_, __) => const Divider(height: 1),
      itemBuilder: (context, i) {
        final f = rows[i];
        return ListTile(
          dense: true,
          leading: TagChip(label: f.classification, color: classificationColor(f.classification)),
          title: Text(f.exceptionClass ?? f.rootCauseSignature ?? '—'),
          subtitle: Text(
              '${f.originalTopic ?? '?'} · attempt ${f.attemptCount}'
              '${f.currentTier != null ? ' · tier ${f.currentTier}' : ''} · ${f.correlationId}'),
          trailing: Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              TagChip(label: f.state, color: stateColor(f.state)),
              const SizedBox(width: 8),
              Text(formatRelative(f.updatedAt), style: Theme.of(context).textTheme.bodySmall),
            ],
          ),
          onTap: () => _openDetail(f.correlationId),
        );
      },
    );
  }

  Widget _pager() {
    final r = _result;
    if (r == null) return const SizedBox.shrink();
    return Padding(
      padding: const EdgeInsets.only(top: 8),
      child: Row(
        mainAxisAlignment: MainAxisAlignment.end,
        children: [
          Text('${r.totalElements} total · page ${r.page + 1}/${r.totalPages == 0 ? 1 : r.totalPages}'),
          IconButton(
            icon: const Icon(Icons.chevron_left),
            onPressed: r.page > 0
                ? () {
                    _page = r.page - 1;
                    _load();
                  }
                : null,
          ),
          IconButton(
            icon: const Icon(Icons.chevron_right),
            onPressed: (r.page + 1) < r.totalPages
                ? () {
                    _page = r.page + 1;
                    _load();
                  }
                : null,
          ),
        ],
      ),
    );
  }
}
