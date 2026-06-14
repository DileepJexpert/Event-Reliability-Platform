import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import 'services/api_client.dart';
import 'services/auth_service.dart';
import 'screens/home_shell.dart';
import 'screens/login_screen.dart';

/// Root widget. Provides [AuthService] and a token-aware [ApiClient] *above* the [MaterialApp] so
/// that routes pushed onto the Navigator (e.g. the failure detail screen) can still read them. The
/// live [DashboardState]/SSE plumbing is created inside the authenticated shell.
class ConsoleApp extends StatelessWidget {
  const ConsoleApp({super.key});

  @override
  Widget build(BuildContext context) {
    return MultiProvider(
      providers: [
        ChangeNotifierProvider<AuthService>(create: (_) => AuthService()..bootstrap()),
        ProxyProvider<AuthService, ApiClient>(
          update: (_, auth, __) => ApiClient(auth),
        ),
      ],
      child: MaterialApp(
        title: 'Event Reliability Console',
        debugShowCheckedModeBanner: false,
        theme: _theme(),
        home: const _AuthGate(),
      ),
    );
  }

  /// A compact, professional, desktop/web-first theme: denser controls, a corporate blue palette,
  /// outlined dense inputs and a tabular data-table style — rather than the airy mobile defaults.
  ThemeData _theme() {
    final scheme = ColorScheme.fromSeed(
      seedColor: const Color(0xFF1F4E79),
      brightness: Brightness.light,
    );
    return ThemeData(
      useMaterial3: true,
      colorScheme: scheme,
      visualDensity: VisualDensity.compact,
      scaffoldBackgroundColor: const Color(0xFFF4F6F8),
      inputDecorationTheme: InputDecorationTheme(
        isDense: true,
        filled: true,
        fillColor: Colors.white,
        contentPadding: const EdgeInsets.symmetric(horizontal: 10, vertical: 10),
        border: OutlineInputBorder(borderRadius: BorderRadius.circular(6)),
      ),
      dataTableTheme: const DataTableThemeData(
        headingRowColor: WidgetStatePropertyAll(Color(0xFFEDF0F4)),
        headingTextStyle:
            TextStyle(fontWeight: FontWeight.w700, fontSize: 12.5, color: Color(0xFF1A2433)),
        dataTextStyle: TextStyle(fontSize: 12.5, color: Color(0xFF2A3445)),
        dataRowMinHeight: 38,
        dataRowMaxHeight: 48,
        columnSpacing: 26,
        horizontalMargin: 16,
        dividerThickness: 1,
      ),
      dividerTheme: const DividerThemeData(space: 1, thickness: 1),
      chipTheme: const ChipThemeData(
        padding: EdgeInsets.symmetric(horizontal: 6),
        labelStyle: TextStyle(fontSize: 11, fontWeight: FontWeight.w600),
      ),
    );
  }
}

class _AuthGate extends StatelessWidget {
  const _AuthGate();

  @override
  Widget build(BuildContext context) {
    final auth = context.watch<AuthService>();
    return auth.state.authenticated ? const HomeShell() : const LoginScreen();
  }
}
