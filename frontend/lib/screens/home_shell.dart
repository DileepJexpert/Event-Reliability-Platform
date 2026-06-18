import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../config/app_config.dart';
import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../services/event_stream.dart';
import '../state/dashboard_state.dart';
import 'approvals_screen.dart';
import 'assistant_screen.dart';
import 'audit_search_screen.dart';
import 'dashboard_screen.dart';
import 'failures_screen.dart';
import 'incidents_screen.dart';
import 'trends_screen.dart';

/// The authenticated shell: owns the request-scoped services ([ApiClient], [EventStream],
/// [DashboardState]) and a navigation rail across the console's screens (§16).
class HomeShell extends StatefulWidget {
  const HomeShell({super.key});

  @override
  State<HomeShell> createState() => _HomeShellState();
}

class _HomeShellState extends State<HomeShell> {
  late final ApiClient _api;
  late final EventStream _stream;
  late final DashboardState _dashboard;
  int _index = 0;

  static const _pages = [
    DashboardScreen(),
    FailuresScreen(),
    IncidentsScreen(),
    TrendsScreen(),
    AssistantScreen(),
    ApprovalsScreen(),
    AuditSearchScreen(),
  ];

  @override
  void initState() {
    super.initState();
    final auth = context.read<AuthService>();
    // ApiClient is provided above MaterialApp so pushed routes can read it too.
    _api = context.read<ApiClient>();
    _stream = EventStream(auth);
    _dashboard = DashboardState(_api, _stream)..start();
  }

  @override
  void dispose() {
    _dashboard.dispose();
    super.dispose();
  }

  Future<void> _editActor(AuthService auth) async {
    final ctrl = TextEditingController(text: auth.actingAs);
    final who = await showDialog<String>(
      context: context,
      builder: (context) => AlertDialog(
        title: const Text('Acting as'),
        content: Column(
          mainAxisSize: MainAxisSize.min,
          crossAxisAlignment: CrossAxisAlignment.start,
          children: [
            const Text('Used for audit + the X-Actor header. Set a different user to play the checker '
                'in the maker-checker flow.'),
            const SizedBox(height: 8),
            TextField(
              controller: ctrl,
              autofocus: true,
              decoration: const InputDecoration(labelText: 'User'),
            ),
          ],
        ),
        actions: [
          TextButton(onPressed: () => Navigator.pop(context), child: const Text('Cancel')),
          FilledButton(onPressed: () => Navigator.pop(context, ctrl.text.trim()), child: const Text('Set')),
        ],
      ),
    );
    if (who != null) auth.setActingAs(who);
  }

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();
    return ChangeNotifierProvider<DashboardState>.value(
      value: _dashboard,
      child: Scaffold(
        appBar: AppBar(
          titleSpacing: 16,
          title: const Row(
            mainAxisSize: MainAxisSize.min,
            children: [
              Icon(Icons.shield_moon, size: 20),
              SizedBox(width: 8),
              Text('Event Reliability Console',
                  style: TextStyle(fontSize: 16, fontWeight: FontWeight.w600)),
            ],
          ),
          actions: [
            if (!AppConfig.isOidc) _demoSwitcher(auth) else _userLabel(auth),
            const SizedBox(width: 8),
            IconButton(
              tooltip: 'Sign out',
              icon: const Icon(Icons.logout),
              onPressed: () => context.read<AuthService>().logout(),
            ),
            const SizedBox(width: 8),
          ],
        ),
        body: Row(
          children: [
            NavigationRail(
              selectedIndex: _index,
              onDestinationSelected: (i) => setState(() => _index = i),
              labelType: NavigationRailLabelType.all,
              leading: Column(
                children: [
                  const SizedBox(height: 8),
                  const Icon(Icons.shield_moon, color: Colors.indigo),
                  const SizedBox(height: 4),
                  Text(auth.state.username, style: const TextStyle(fontSize: 11)),
                  // Acting-as switcher: lets one browser play maker then checker (4-eyes).
                  TextButton.icon(
                    style: TextButton.styleFrom(padding: const EdgeInsets.symmetric(horizontal: 6)),
                    icon: const Icon(Icons.switch_account, size: 14),
                    label: Text('as ${auth.actingAs}', style: const TextStyle(fontSize: 10)),
                    onPressed: () => _editActor(context.read<AuthService>()),
                  ),
                  IconButton(
                    tooltip: 'Sign out',
                    icon: const Icon(Icons.logout, size: 18),
                    onPressed: () => context.read<AuthService>().logout(),
                  ),
                ],
              ),
              destinations: const [
                NavigationRailDestination(icon: Icon(Icons.dashboard), label: Text('Dashboard')),
                NavigationRailDestination(icon: Icon(Icons.list_alt), label: Text('Failures')),
                NavigationRailDestination(icon: Icon(Icons.warning_amber), label: Text('Incidents')),
                NavigationRailDestination(icon: Icon(Icons.insights), label: Text('Trends')),
                NavigationRailDestination(icon: Icon(Icons.auto_awesome), label: Text('Ask Brod')),
                NavigationRailDestination(icon: Icon(Icons.approval), label: Text('Approvals')),
                NavigationRailDestination(icon: Icon(Icons.history), label: Text('Audit')),
              ],
            ),
            const VerticalDivider(width: 1),
            Expanded(child: _pages[_index]),
          ],
        ),
      ),
    );
  }

  /// Dev-only quick identity switch in the top bar: hop between the maker (Dileep) and checker (Somokh)
  /// without signing out, so the 4-eyes flow can be shown back-to-back. Swaps roles + the X-Actor.
  Widget _demoSwitcher(AuthService auth) {
    final current = auth.state.username;
    final selected = kDemoUsers.any((u) => u.username == current) ? current : kDemoUsers.first.username;
    return Row(
      mainAxisSize: MainAxisSize.min,
      children: [
        const Text('Demo:', style: TextStyle(fontSize: 12)),
        const SizedBox(width: 8),
        SegmentedButton<String>(
          showSelectedIcon: false,
          style: const ButtonStyle(
            visualDensity: VisualDensity.compact,
            tapTargetSize: MaterialTapTargetSize.shrinkWrap,
          ),
          segments: [
            for (final u in kDemoUsers)
              ButtonSegment<String>(value: u.username, label: Text('${u.label} · ${u.role}')),
          ],
          selected: {selected},
          onSelectionChanged: (sel) {
            final u = kDemoUsers.firstWhere((x) => x.username == sel.first);
            auth.loginAsDev(u.username, u.roles);
          },
        ),
      ],
    );
  }

  Widget _userLabel(AuthService auth) {
    return Center(
      child: Padding(
        padding: const EdgeInsets.symmetric(horizontal: 8),
        child: Text(auth.state.username, style: const TextStyle(fontSize: 13)),
      ),
    );
  }
}
