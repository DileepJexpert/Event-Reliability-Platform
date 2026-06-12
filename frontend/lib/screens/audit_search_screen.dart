import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../widgets/common.dart';

/// Audit search (§16): look up a correlation id and review its complete, immutable audit timeline —
/// the record of what failed and how it was resolved (§2). The timeline is served by the backend's
/// failure-detail endpoint (the audit history aggregated into a full-local GlobalKTable view).
class AuditSearchScreen extends StatefulWidget {
  const AuditSearchScreen({super.key});

  @override
  State<AuditSearchScreen> createState() => _AuditSearchScreenState();
}

class _AuditSearchScreenState extends State<AuditSearchScreen> {
  final _ctrl = TextEditingController();
  List<AuditEvent>? _events;
  bool _loading = false;
  String? _error;

  @override
  void dispose() {
    _ctrl.dispose();
    super.dispose();
  }

  Future<void> _search() async {
    final id = _ctrl.text.trim();
    if (id.isEmpty) return;
    setState(() {
      _loading = true;
      _error = null;
      _events = null;
    });
    try {
      final detail = await context.read<ApiClient>().getFailure(id);
      setState(() => _events = detail.auditTimeline);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Text('Audit search', style: Theme.of(context).textTheme.headlineSmall),
          const SizedBox(height: 12),
          Row(
            children: [
              Expanded(
                child: TextField(
                  controller: _ctrl,
                  decoration: const InputDecoration(
                      labelText: 'Correlation id', isDense: true, border: OutlineInputBorder()),
                  onSubmitted: (_) => _search(),
                ),
              ),
              const SizedBox(width: 8),
              FilledButton.icon(
                  onPressed: _search, icon: const Icon(Icons.search), label: const Text('Search')),
            ],
          ),
          const SizedBox(height: 16),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
          if (_loading) const Center(child: Padding(padding: EdgeInsets.all(24), child: CircularProgressIndicator())),
          if (_events != null)
            Expanded(
              child: _events!.isEmpty
                  ? const Center(child: Text('No audit history for that correlation id.'))
                  : ListView.separated(
                      itemCount: _events!.length,
                      separatorBuilder: (_, __) => const Divider(height: 1),
                      itemBuilder: (context, i) {
                        final e = _events![i];
                        return ListTile(
                          leading: TagChip(label: e.toState, color: stateColor(e.toState)),
                          title: Text('${e.action} · by ${e.actor}'),
                          subtitle: Text('${e.detail ?? ''}\n${formatTimestamp(e.at)}'),
                          isThreeLine: true,
                        );
                      },
                    ),
            ),
        ],
      ),
    );
  }
}
