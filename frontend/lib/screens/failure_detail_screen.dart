import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../widgets/common.dart';

/// Failure detail (§16): metadata, classification, the full audit timeline and the operator actions
/// (replay, quarantine). Actions are only shown to OPERATORs, mirroring the backend's authorization.
class FailureDetailScreen extends StatefulWidget {
  final String correlationId;
  const FailureDetailScreen({super.key, required this.correlationId});

  @override
  State<FailureDetailScreen> createState() => _FailureDetailScreenState();
}

class _FailureDetailScreenState extends State<FailureDetailScreen> {
  FailureDetail? _detail;
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
      final detail = await context.read<ApiClient>().getFailure(widget.correlationId);
      setState(() => _detail = detail);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _runAction(String verb, Future<void> Function(String? reason) action) async {
    final reason = await _askReason(verb);
    if (reason == null) return; // cancelled
    try {
      await action(reason.isEmpty ? null : reason);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(SnackBar(content: Text('$verb accepted')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('$verb failed: $e')));
    }
  }

  /// Maker action: raise a replay request, optionally redirecting the topic and/or correcting the
  /// payload. In maker-checker mode a different checker must approve it before it executes (§13).
  Future<void> _requestReplay() async {
    final d = _detail!;
    final originalText = _decode(d.payloadBase64);
    final reasonCtrl = TextEditingController();
    final topicCtrl = TextEditingController();
    final payloadCtrl = TextEditingController(text: originalText);

    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Request replay — ${widget.correlationId}'),
        content: SizedBox(
          width: 560,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                TextField(
                  controller: reasonCtrl,
                  autofocus: true,
                  decoration: const InputDecoration(labelText: 'Reason (audited)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: topicCtrl,
                  decoration: InputDecoration(
                      labelText: 'Target topic (optional)',
                      hintText: d.originalTopic ?? 'original topic'),
                ),
                const SizedBox(height: 12),
                const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Payload — edit to correct before replay:',
                        style: TextStyle(color: Colors.black54))),
                const SizedBox(height: 4),
                TextField(
                  controller: payloadCtrl,
                  maxLines: 8,
                  style: const TextStyle(fontFamily: 'monospace', fontSize: 12),
                  decoration: const InputDecoration(border: OutlineInputBorder()),
                ),
              ],
            ),
          ),
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context, false), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, true), child: const Text('Submit request')),
        ],
      ),
    );
    if (ok != true) return;

    final reason = reasonCtrl.text.trim();
    final topic = topicCtrl.text.trim();
    final newPayload = payloadCtrl.text;
    final payloadB64 = newPayload == originalText ? null : base64.encode(utf8.encode(newPayload));

    try {
      await context.read<ApiClient>().replay(widget.correlationId,
          reason: reason.isEmpty ? null : reason,
          targetTopic: topic.isEmpty ? null : topic,
          payloadBase64: payloadB64);
      if (!mounted) return;
      ScaffoldMessenger.of(context)
          .showSnackBar(const SnackBar(content: Text('Replay requested — awaiting approval')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Replay request failed: $e')));
    }
  }

  String _decode(String? b64) {
    if (b64 == null || b64.isEmpty) return '';
    try {
      return utf8.decode(base64.decode(b64));
    } catch (_) {
      return '';
    }
  }

  Future<String?> _askReason(String verb) {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('$verb — ${widget.correlationId}'),
        content: TextField(
          controller: ctrl,
          decoration: const InputDecoration(labelText: 'Reason (audited)'),
          autofocus: true,
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(
              onPressed: () => Navigator.pop(context, ctrl.text.trim()), child: Text(verb)),
        ],
      ),
    );
  }

  @override
  Widget build(BuildContext context) {
    final isOperator = context.watch<AuthService>().state.isOperator;
    final api = context.read<ApiClient>();
    return Scaffold(
      appBar: AppBar(
        title: Text('Failure ${widget.correlationId}'),
        actions: [
          if (isOperator && _detail != null) ...[
            TextButton.icon(
              icon: const Icon(Icons.replay),
              label: const Text('Request replay'),
              onPressed: _requestReplay,
            ),
            TextButton.icon(
              icon: const Icon(Icons.block),
              label: const Text('Quarantine'),
              onPressed: () => _runAction(
                  'Quarantine', (reason) => api.quarantine(widget.correlationId, reason: reason)),
            ),
          ],
          IconButton(icon: const Icon(Icons.refresh), onPressed: _load),
        ],
      ),
      body: _body(),
    );
  }

  Widget _body() {
    if (_loading) return const Center(child: CircularProgressIndicator());
    if (_error != null) return Center(child: Text(_error!, style: const TextStyle(color: Colors.red)));
    final d = _detail!;
    return ListView(
      padding: const EdgeInsets.all(20),
      children: [
        Wrap(spacing: 8, runSpacing: 8, children: [
          TagChip(label: d.classification, color: classificationColor(d.classification)),
          TagChip(label: d.state, color: stateColor(d.state)),
          if (d.recommendedAction != null)
            TagChip(label: d.recommendedAction, color: Colors.blueGrey),
          if (d.currentTier != null) TagChip(label: 'tier ${d.currentTier}', color: Colors.teal),
        ]),
        const SizedBox(height: 16),
        _kv('Root cause', d.rootCauseSignature),
        _kv('Original topic', d.originalTopic),
        _kv('Partition / offset', '${d.originalPartition ?? '—'} / ${d.originalOffset ?? '—'}'),
        _kv('Source app', d.sourceApp),
        _kv('Owning team', d.owningTeam),
        _kv('Exception', d.exceptionClass),
        _kv('Message', d.exceptionMessage),
        _kv('Attempts', '${d.attemptCount}'),
        _kv('Reason', d.reason),
        if (d.lastError != null) _kv('Last error', d.lastError),
        if (d.lastActor != null) _kv('Last actor', d.lastActor),
        _kv('First failed', formatTimestamp(d.firstFailedAt)),
        _kv('Updated', formatTimestamp(d.updatedAt)),
        const SizedBox(height: 16),
        if (d.stacktrace != null) ...[
          Text('Stacktrace', style: Theme.of(context).textTheme.titleMedium),
          Container(
            margin: const EdgeInsets.symmetric(vertical: 8),
            padding: const EdgeInsets.all(10),
            color: Colors.black12,
            child: Text(d.stacktrace!, style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
          ),
        ],
        const SizedBox(height: 8),
        Text('Audit timeline', style: Theme.of(context).textTheme.titleMedium),
        const SizedBox(height: 8),
        ..._detail!.auditTimeline.map(_auditTile),
      ],
    );
  }

  Widget _kv(String key, String? value) {
    return Padding(
      padding: const EdgeInsets.symmetric(vertical: 3),
      child: Row(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          SizedBox(width: 160, child: Text(key, style: const TextStyle(color: Colors.black54))),
          Expanded(child: Text(value ?? '—')),
        ],
      ),
    );
  }

  Widget _auditTile(AuditEvent e) {
    return ListTile(
      dense: true,
      leading: const Icon(Icons.fiber_manual_record, size: 12),
      title: Text('${e.action}  ·  ${e.fromState ?? '—'} → ${e.toState ?? '—'}'),
      subtitle: Text('${e.detail ?? ''}\nby ${e.actor} · ${formatTimestamp(e.at)}'),
      isThreeLine: true,
    );
  }
}
