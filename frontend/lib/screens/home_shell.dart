import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../services/api_client.dart';
import '../services/auth_service.dart';
import '../services/event_stream.dart';
import '../state/dashboard_state.dart';
import 'audit_search_screen.dart';
import 'dashboard_screen.dart';
import 'failures_screen.dart';
import 'incidents_screen.dart';

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

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();
    return ChangeNotifierProvider<DashboardState>.value(
      value: _dashboard,
      child: Scaffold(
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
}
