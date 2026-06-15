import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Failures list (§16). A service owner self-serves "show my failures" by filtering on their source
/// topic and/or owning app (plus status / classification), or jumps straight to a correlation id.
/// Rendered as a compact, web-first data table.
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
  final _dlqTopicCtrl = TextEditingController();
  final _sourceAppCtrl = TextEditingController();
  final _searchCtrl = TextEditingController();
  final _topicFocus = FocusNode();
  final _dlqTopicFocus = FocusNode();
  final _sourceAppFocus = FocusNode();
  Facets _facets = const Facets();

  int _page = 0;
  PageResult<FailureSummary>? _result;
  bool _loading = false;
  String? _error;

  @override
  void initState() {
    super.initState();
    WidgetsBinding.instance.addPostFrameCallback((_) {
      _load();
      _loadFacets();
    });
  }

  @override
  void dispose() {
    _topicCtrl.dispose();
    _dlqTopicCtrl.dispose();
    _sourceAppCtrl.dispose();
    _searchCtrl.dispose();
    _topicFocus.dispose();
    _dlqTopicFocus.dispose();
    _sourceAppFocus.dispose();
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
            dlqTopic: _dlqTopicCtrl.text.trim().isEmpty ? null : _dlqTopicCtrl.text.trim(),
            sourceApp: _sourceAppCtrl.text.trim().isEmpty ? null : _sourceAppCtrl.text.trim(),
            page: _page,
          );
      setState(() => _result = result);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _loadFacets() async {
    try {
      final f = await context.read<ApiClient>().getFacets();
      if (mounted) setState(() => _facets = f);
    } catch (_) {
      // Non-fatal: the autocomplete simply won't suggest until facets load.
    }
  }

  void _apply() {
    _page = 0;
    _load();
  }

  void _clear() {
    setState(() {
      _status = '';
      _classification = '';
      _topicCtrl.clear();
      _dlqTopicCtrl.clear();
      _sourceAppCtrl.clear();
    });
    _apply();
  }

  void _openDetail(String correlationId) {
    Navigator.of(context).push(
        MaterialPageRoute(builder: (_) => FailureDetailScreen(correlationId: correlationId)));
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.fromLTRB(20, 18, 20, 12),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          _header(),
          const SizedBox(height: 12),
          _filters(),
          const SizedBox(height: 12),
          if (_error != null)
            Padding(
              padding: const EdgeInsets.only(bottom: 8),
              child: Text(_error!, style: const TextStyle(color: Colors.red)),
            ),
          Expanded(child: _tableCard()),
          const SizedBox(height: 8),
          _pager(),
        ],
      ),
    );
  }

  Widget _header() {
    return Row(
      crossAxisAlignment: CrossAxisAlignment.center,
      children: [
        Text('Failures', style: Theme.of(context).textTheme.titleLarge),
        const SizedBox(width: 10),
        if (_result != null)
          Text('${_result!.totalElements} total', style: Theme.of(context).textTheme.bodySmall),
        const Spacer(),
        SizedBox(
          width: 320,
          child: TextField(
            controller: _searchCtrl,
            decoration: InputDecoration(
              hintText: 'Go to correlation id…',
              prefixIcon: const Icon(Icons.search, size: 18),
              suffixIcon: IconButton(
                icon: const Icon(Icons.arrow_forward, size: 18),
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

  Widget _filters() {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(12),
        child: Wrap(
          spacing: 12,
          runSpacing: 12,
          crossAxisAlignment: WrapCrossAlignment.center,
          children: [
            _dropdown('Status', _states, _status, (v) => setState(() => _status = v ?? '')),
            _dropdown('Classification', _classes, _classification,
                (v) => setState(() => _classification = v ?? '')),
            _autoField(_topicCtrl, _topicFocus, 'Source topic', 'type to match…', 230, _facets.topics),
            _autoField(_dlqTopicCtrl, _dlqTopicFocus, 'DLQ topic', 'type to match…', 230, _facets.dlqTopics),
            _autoField(_sourceAppCtrl, _sourceAppFocus, 'Source app', 'type to match…', 200, _facets.sourceApps),
            FilledButton.icon(
              onPressed: _apply,
              icon: const Icon(Icons.filter_alt, size: 18),
              label: const Text('Search'),
            ),
            TextButton.icon(
              onPressed: _clear,
              icon: const Icon(Icons.clear, size: 18),
              label: const Text('Clear'),
            ),
            if (_loading)
              const SizedBox(width: 18, height: 18, child: CircularProgressIndicator(strokeWidth: 2)),
          ],
        ),
      ),
    );
  }

  /// A topic/app filter with type-ahead: shows matching known values in a dropdown as you type
  /// (char-based), and the backend filters by a case-insensitive "contains" match.
  Widget _autoField(TextEditingController c, FocusNode f, String label, String hint, double width,
      List<String> options) {
    return SizedBox(
      width: width,
      child: Autocomplete<String>(
        textEditingController: c,
        focusNode: f,
        optionsBuilder: (TextEditingValue value) {
          final q = value.text.trim().toLowerCase();
          if (q.isEmpty) return const Iterable<String>.empty();
          return options.where((o) => o.toLowerCase().contains(q)).take(8);
        },
        onSelected: (_) => _apply(),
        fieldViewBuilder: (context, controller, focusNode, onFieldSubmitted) {
          return TextField(
            controller: controller,
            focusNode: focusNode,
            decoration: InputDecoration(labelText: label, hintText: hint),
            onSubmitted: (_) => _apply(),
          );
        },
      ),
    );
  }

  Widget _dropdown(String label, List<String> items, String value, ValueChanged<String?> onChanged) {
    return SizedBox(
      width: 190,
      child: InputDecorator(
        decoration: InputDecoration(labelText: label),
        child: DropdownButtonHideUnderline(
          child: DropdownButton<String>(
            value: value,
            isExpanded: true,
            isDense: true,
            items: items
                .map((s) => DropdownMenuItem(value: s, child: Text(s.isEmpty ? 'Any' : s)))
                .toList(),
            onChanged: onChanged,
          ),
        ),
      ),
    );
  }

  Widget _tableCard() {
    if (_loading && _result == null) {
      return const Center(child: CircularProgressIndicator());
    }
    final rows = _result?.content ?? const <FailureSummary>[];
    if (rows.isEmpty) {
      return Card(
        child: Center(
          child: Padding(
            padding: const EdgeInsets.all(32),
            child: Text('No failures match the filter.',
                style: Theme.of(context).textTheme.bodyMedium),
          ),
        ),
      );
    }
    return Card(
      clipBehavior: Clip.antiAlias,
      child: Scrollbar(
        child: SingleChildScrollView(
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
                  DataColumn(label: Text('DLQ topic')),
                  DataColumn(label: Text('Source app')),
                  DataColumn(label: Text('Team')),
                  DataColumn(label: Text('Exception')),
                  DataColumn(label: Text('Attempts'), numeric: true),
                  DataColumn(label: Text('Updated')),
                ],
                rows: rows.map(_row).toList(),
              ),
            ),
          ),
        ),
      ),
    );
  }

  DataRow _row(FailureSummary f) {
    return DataRow(
      onSelectChanged: (_) => _openDetail(f.correlationId),
      cells: [
        DataCell(TagChip(label: f.classification, color: classificationColor(f.classification))),
        DataCell(TagChip(label: f.state, color: stateColor(f.state))),
        DataCell(Text(f.originalTopic ?? '—')),
        DataCell(Text(f.dlqTopic ?? '—')),
        DataCell(Text(f.sourceApp ?? '—')),
        DataCell((f.owningTeam == null || f.owningTeam!.isEmpty)
            ? const Text('—')
            : TagChip(label: f.owningTeam, color: Colors.blueGrey)),
        DataCell(ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 380),
          child: Text(f.exceptionClass ?? f.rootCauseSignature ?? '—',
              overflow: TextOverflow.ellipsis),
        )),
        DataCell(Text('${f.attemptCount}')),
        DataCell(Text(formatRelative(f.updatedAt))),
      ],
    );
  }

  Widget _pager() {
    final r = _result;
    if (r == null) return const SizedBox.shrink();
    return Row(
      mainAxisAlignment: MainAxisAlignment.end,
      children: [
        Text('Page ${r.page + 1} / ${r.totalPages == 0 ? 1 : r.totalPages}  ·  ${r.totalElements} total',
            style: Theme.of(context).textTheme.bodySmall),
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
    );
  }
}
