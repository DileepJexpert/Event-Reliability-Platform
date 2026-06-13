import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Maker-checker approvals (§13, §17). Two queues, toggled by the filter:
///   • PENDING  — the checker queue: approve, reject, or return-to-maker (APPROVER, ≠ maker).
///   • RETURNED — the maker queue: correct the checker's feedback and resubmit (OPERATOR).
/// A checker sees the original vs corrected payload and the target topic before deciding.
class ApprovalsScreen extends StatefulWidget {
  const ApprovalsScreen({super.key});

  @override
  State<ApprovalsScreen> createState() => _ApprovalsScreenState();
}

class _ApprovalsScreenState extends State<ApprovalsScreen> {
  String _status = 'PENDING';
  List<Approval>? _approvals;
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
      final list = await context.read<ApiClient>().listApprovals(status: _status);
      setState(() => _approvals = list);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  void _setStatus(String status) {
    if (_status == status) return;
    setState(() => _status = status);
    _load();
  }

  Future<void> _call(Future<void> Function() action, String okMessage) async {
    try {
      await action();
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text(okMessage)));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Failed: $e')));
    }
  }

  Future<void> _decide(Approval a, bool approve) async {
    final reason = await _askReason(approve ? 'Approve' : 'Reject', a);
    if (reason == null) return;
    final api = context.read<ApiClient>();
    await _call(
      () => approve
          ? api.approveRequest(a.requestId, reason: reason.isEmpty ? null : reason)
          : api.rejectRequest(a.requestId, reason: reason.isEmpty ? null : reason),
      approve ? 'Approved ${a.requestId}' : 'Rejected ${a.requestId}',
    );
  }

  Future<void> _returnToMaker(Approval a) async {
    final r = await _editDialog(a, 'Return to maker — ${a.target}', 'Return to maker');
    if (r == null) return;
    await _call(
      () => context.read<ApiClient>().returnToMaker(a.requestId,
          reason: r.reason.isEmpty ? null : r.reason, targetTopic: r.topic, payloadBase64: r.payloadB64),
      'Returned to maker',
    );
  }

  Future<void> _resubmit(Approval a) async {
    final r = await _editDialog(a, 'Correct & resubmit — ${a.target}', 'Resubmit for approval');
    if (r == null) return;
    await _call(
      () => context.read<ApiClient>().resubmit(a.requestId,
          reason: r.reason.isEmpty ? null : r.reason, targetTopic: r.topic, payloadBase64: r.payloadB64),
      'Resubmitted for approval',
    );
  }

  Future<String?> _askReason(String verb, Approval a) {
    final ctrl = TextEditingController();
    return showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('$verb — ${a.type} ${a.target}'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Text('Maker: ${a.maker ?? '—'}'),
            if (a.makerReason != null) Text('Maker reason: ${a.makerReason}'),
            const SizedBox(height: 12),
            TextField(
              controller: ctrl,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'Reason (audited)'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, ctrl.text.trim()), child: Text(verb)),
        ],
      ),
    );
  }

  /// Shared editor for return (checker suggestion) and resubmit (maker correction): a note, an
  /// optional target topic, and an editable payload. Returns null if cancelled. The payload is
  /// re-encoded to base64 only when it differs from the original (so reverting clears the override).
  Future<({String reason, String? topic, String? payloadB64})?> _editDialog(
      Approval a, String title, String submitLabel) async {
    final originalText = _decode(a.originalPayloadBase64);
    final prefill = a.payloadOverrideBase64 != null ? _decode(a.payloadOverrideBase64) : originalText;
    final reasonCtrl = TextEditingController();
    final topicCtrl = TextEditingController(text: a.targetTopic ?? '');
    final payloadCtrl = TextEditingController(text: prefill);

    final ok = await showDialog<bool>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text(title),
        content: SizedBox(
          width: 560,
          child: SingleChildScrollView(
            child: Column(
              mainAxisSize: MainAxisSize.min,
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                if (a.maker != null) Text('Maker: ${a.maker}'),
                if (a.checkerReason != null)
                  Padding(
                    padding: const EdgeInsets.only(top: 4),
                    child: Text('Last note (${a.checker ?? '—'}): ${a.checkerReason}',
                        style: const TextStyle(fontStyle: FontStyle.italic)),
                  ),
                const SizedBox(height: 10),
                TextField(
                  controller: reasonCtrl,
                  autofocus: true,
                  decoration: const InputDecoration(labelText: 'Note / reason (audited)'),
                ),
                const SizedBox(height: 8),
                TextField(
                  controller: topicCtrl,
                  decoration: InputDecoration(
                      labelText: 'Target topic (optional)',
                      hintText: a.originalTopic ?? 'original topic'),
                ),
                const SizedBox(height: 12),
                const Align(
                    alignment: Alignment.centerLeft,
                    child: Text('Payload (edit to correct):', style: TextStyle(color: Colors.black54))),
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
          FilledButton(onPressed: () => Navigator.pop(context, true), child: Text(submitLabel)),
        ],
      ),
    );
    if (ok != true) return null;
    final newPayload = payloadCtrl.text;
    final payloadB64 = newPayload == originalText ? null : base64.encode(utf8.encode(newPayload));
    final topic = topicCtrl.text.trim();
    return (reason: reasonCtrl.text.trim(), topic: topic.isEmpty ? null : topic, payloadB64: payloadB64);
  }

  void _showPayloadDiff(Approval a) {
    showDialog<void>(
      context: context,
      builder: (context) => AlertDialog(
        title: Text('Payload change — ${a.correlationId}'),
        content: SizedBox(
          width: 560,
          child: SingleChildScrollView(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              children: [
                const Text('Original', style: TextStyle(fontWeight: FontWeight.bold)),
                _mono(_decode(a.originalPayloadBase64)),
                const SizedBox(height: 12),
                const Text('Corrected', style: TextStyle(fontWeight: FontWeight.bold)),
                _mono(_decode(a.payloadOverrideBase64)),
              ],
            ),
          ),
        ),
        actions: [TextButton(onPressed: () => Navigator.pop(context), child: const Text('Close'))],
      ),
    );
  }

  Widget _mono(String text) => Container(
        width: double.infinity,
        margin: const EdgeInsets.symmetric(vertical: 6),
        padding: const EdgeInsets.all(8),
        color: Colors.black12,
        child: Text(text.isEmpty ? '—' : text,
            style: const TextStyle(fontFamily: 'monospace', fontSize: 12)),
      );

  String _decode(String? b64) {
    if (b64 == null || b64.isEmpty) return '';
    try {
      return utf8.decode(base64.decode(b64));
    } catch (_) {
      return '(binary payload, ${b64.length} base64 chars)';
    }
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>().state;
    final returnedView = _status == 'RETURNED';
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Text('Approvals', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(width: 16),
            ChoiceChip(
              label: const Text('Pending (checker)'),
              selected: !returnedView,
              onSelected: (_) => _setStatus('PENDING'),
            ),
            const SizedBox(width: 8),
            ChoiceChip(
              label: const Text('Returned (maker)'),
              selected: returnedView,
              onSelected: (_) => _setStatus('RETURNED'),
            ),
            const SizedBox(width: 8),
            if (_approvals != null) TagChip(label: '${_approvals!.length}', color: Colors.indigo),
            const Spacer(),
            IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
          ]),
          const SizedBox(height: 12),
          if (!returnedView && !auth.isApprover)
            const Padding(
              padding: EdgeInsets.only(bottom: 8),
              child: Text('You are not an approver — approve/reject/return is disabled.',
                  style: TextStyle(color: Colors.orange)),
            ),
          if (returnedView && !auth.isOperator)
            const Padding(
              padding: EdgeInsets.only(bottom: 8),
              child: Text('You are not an operator — resubmit is disabled.',
                  style: TextStyle(color: Colors.orange)),
            ),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
          Expanded(child: _list(auth, returnedView)),
        ],
      ),
    );
  }

  Widget _list(AuthState auth, bool returnedView) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final items = _approvals ?? const <Approval>[];
    if (items.isEmpty) {
      return Center(
          child: Text(returnedView ? 'No requests awaiting correction.' : 'No pending approvals.'));
    }
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, i) => _card(items[i], auth, returnedView),
    );
  }

  Widget _card(Approval a, AuthState auth, bool returnedView) {
    return Card(
      child: Padding(
        padding: const EdgeInsets.all(14),
        child: Column(
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            Row(children: [
              TagChip(label: a.type, color: Colors.indigo),
              const SizedBox(width: 8),
              Expanded(child: Text(a.target, style: const TextStyle(fontWeight: FontWeight.bold))),
              if (a.revision > 0)
                Padding(
                  padding: const EdgeInsets.only(left: 6),
                  child: TagChip(label: 'rev ${a.revision}', color: Colors.blueGrey),
                ),
              if (a.payloadEdited)
                const Padding(
                  padding: EdgeInsets.only(left: 6),
                  child: TagChip(label: 'PAYLOAD EDITED', color: Colors.deepOrange),
                ),
              if (a.targetTopic != null)
                Padding(
                  padding: const EdgeInsets.only(left: 6),
                  child: TagChip(label: '→ ${a.targetTopic}', color: Colors.teal),
                ),
            ]),
            const SizedBox(height: 6),
            Text('Maker: ${a.maker ?? '—'}'
                '${a.makerReason != null ? ' · ${a.makerReason}' : ''}'),
            if (a.exceptionClass != null)
              Text('Exception: ${a.exceptionClass}', style: const TextStyle(color: Colors.black54)),
            if (returnedView && a.checkerReason != null)
              Padding(
                padding: const EdgeInsets.only(top: 4),
                child: Text('Returned by ${a.checker ?? '—'}: ${a.checkerReason}',
                    style: const TextStyle(color: Colors.deepOrange, fontStyle: FontStyle.italic)),
              ),
            Text('Requested: ${formatTimestamp(a.createdAt)}',
                style: const TextStyle(color: Colors.black54)),
            const SizedBox(height: 10),
            Row(children: [
              if (a.correlationId != null)
                OutlinedButton.icon(
                  icon: const Icon(Icons.open_in_new, size: 16),
                  label: const Text('View failure'),
                  onPressed: () => Navigator.of(context).push(MaterialPageRoute(
                      builder: (_) => FailureDetailScreen(correlationId: a.correlationId!))),
                ),
              if (a.payloadEdited) ...[
                const SizedBox(width: 8),
                OutlinedButton.icon(
                  icon: const Icon(Icons.difference, size: 16),
                  label: const Text('Payload diff'),
                  onPressed: () => _showPayloadDiff(a),
                ),
              ],
              const Spacer(),
              if (returnedView) ...[
                if (auth.isOperator)
                  FilledButton.icon(
                    icon: const Icon(Icons.edit, size: 16),
                    label: const Text('Correct & resubmit'),
                    onPressed: () => _resubmit(a),
                  ),
              ] else if (auth.isApprover) ...[
                TextButton(onPressed: () => _decide(a, false), child: const Text('Reject')),
                const SizedBox(width: 4),
                OutlinedButton.icon(
                  icon: const Icon(Icons.undo, size: 16),
                  label: const Text('Return'),
                  onPressed: () => _returnToMaker(a),
                ),
                const SizedBox(width: 8),
                FilledButton.icon(
                  icon: const Icon(Icons.check, size: 16),
                  label: const Text('Approve'),
                  onPressed: () => _decide(a, true),
                ),
              ],
            ]),
          ],
        ),
      ),
    );
  }
}
