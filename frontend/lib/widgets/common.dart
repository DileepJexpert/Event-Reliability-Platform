import 'package:flutter/material.dart';
import 'package:intl/intl.dart';

/// Shared presentation helpers: colour coding for the taxonomy/lifecycle, chips and timestamp
/// formatting. Pure view code — no platform logic.

Color classificationColor(String? classification) {
  switch (classification) {
    case 'TRANSIENT':
      return Colors.blue;
    case 'INFRASTRUCTURE':
      return Colors.indigo;
    case 'BUSINESS':
      return Colors.orange;
    case 'POISON':
      return Colors.red;
    case 'UNKNOWN':
    default:
      return Colors.blueGrey;
  }
}

Color stateColor(String? state) {
  switch (state) {
    case 'RESOLVED':
      return Colors.green;
    case 'RETRYING':
    case 'RETRY_SCHEDULED':
      return Colors.teal;
    case 'ROUTED_BUSINESS':
      return Colors.orange;
    case 'QUARANTINED_POISON':
      return Colors.red;
    case 'EXHAUSTED_PARKED':
    case 'PARKED_UNKNOWN':
      return Colors.deepOrange;
    case 'REPLAYED':
    case 'REPLAY_REQUESTED':
      return Colors.purple;
    default:
      return Colors.blueGrey;
  }
}

class TagChip extends StatelessWidget {
  final String? label;
  final Color color;
  const TagChip({super.key, required this.label, required this.color});

  @override
  Widget build(BuildContext context) {
    return Container(
      padding: const EdgeInsets.symmetric(horizontal: 8, vertical: 3),
      decoration: BoxDecoration(
        color: color.withOpacity(0.12),
        borderRadius: BorderRadius.circular(6),
        border: Border.all(color: color.withOpacity(0.5)),
      ),
      child: Text(
        label ?? '—',
        style: TextStyle(color: color, fontWeight: FontWeight.w600, fontSize: 12),
      ),
    );
  }
}

String formatTimestamp(int? epochMillis) {
  if (epochMillis == null || epochMillis == 0) return '—';
  final dt = DateTime.fromMillisecondsSinceEpoch(epochMillis).toLocal();
  return DateFormat('yyyy-MM-dd HH:mm:ss').format(dt);
}

String formatRelative(int? epochMillis) {
  if (epochMillis == null || epochMillis == 0) return '—';
  final diff = DateTime.now().difference(DateTime.fromMillisecondsSinceEpoch(epochMillis));
  if (diff.inSeconds < 60) return '${diff.inSeconds}s ago';
  if (diff.inMinutes < 60) return '${diff.inMinutes}m ago';
  if (diff.inHours < 24) return '${diff.inHours}h ago';
  return '${diff.inDays}d ago';
}
