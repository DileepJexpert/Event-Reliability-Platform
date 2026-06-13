import 'dart:convert';

import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../models/models.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../widgets/common.dart';
import 'failure_detail_screen.dart';

/// Maker-checker approvals (§13, §17): the queue of replay / bulk-replay / quarantine requests
/// awaiting a second pair of eyes. A checker (APPROVER) — who must be a different user than the maker
/// — approves or rejects each, seeing the original vs corrected payload and the target topic first.
class ApprovalsScreen extends StatefulWidget {
  const ApprovalsScreen({super.key});

  @override
  State<ApprovalsScreen> createState() => _ApprovalsScreenState();
}

class _ApprovalsScreenState extends State<ApprovalsScreen> {
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
      final list = await context.read<ApiClient>().listApprovals();
      setState(() => _approvals = list);
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _loading = false);
    }
  }

  Future<void> _decide(Approval a, bool approve) async {
    final reason = await _askReason(approve ? 'Approve' : 'Reject', a);
    if (reason == null) return;
    final api = context.read<ApiClient>();
    try {
      if (approve) {
        await api.approveRequest(a.requestId, reason: reason.isEmpty ? null : reason);
      } else {
        await api.rejectRequest(a.requestId, reason: reason.isEmpty ? null : reason);
      }
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(
          SnackBar(content: Text('${approve ? 'Approved' : 'Rejected'} ${a.requestId}')));
      await _load();
    } catch (e) {
      if (!mounted) return;
      ScaffoldMessenger.of(context).showSnackBar(SnackBar(content: Text('Failed: $e')));
    }
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
    final isApprover = context.watch<AuthService>().state.isApprover;
    return Padding(
      padding: const EdgeInsets.all(20),
      child: Column(
        crossAxisAlignment: CrossAxisAlignment.start,
        children: [
          Row(children: [
            Text('Pending approvals', style: Theme.of(context).textTheme.headlineSmall),
            const SizedBox(width: 12),
            if (_approvals != null) TagChip(label: '${_approvals!.length}', color: Colors.indigo),
            const Spacer(),
            IconButton(onPressed: _load, icon: const Icon(Icons.refresh)),
          ]),
          const SizedBox(height: 12),
          if (!isApprover)
            const Padding(
              padding: EdgeInsets.only(bottom: 8),
              child: Text('You are not an approver — approve/reject is disabled.',
                  style: TextStyle(color: Colors.orange)),
            ),
          if (_error != null) Text(_error!, style: const TextStyle(color: Colors.red)),
          Expanded(child: _list(isApprover)),
        ],
      ),
    );
  }

  Widget _list(bool isApprover) {
    if (_loading) return const Center(child: CircularProgressIndicator());
    final items = _approvals ?? const <Approval>[];
    if (items.isEmpty) {
      return const Center(child: Text('No pending approvals.'));
    }
    return ListView.separated(
      itemCount: items.length,
      separatorBuilder: (_, __) => const SizedBox(height: 8),
      itemBuilder: (context, i) => _card(items[i], isApprover),
    );
  }

  Widget _card(Approval a, bool isApprover) {
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
              if (isApprover) ...[
                TextButton(onPressed: () => _decide(a, false), child: const Text('Reject')),
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
