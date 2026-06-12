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
        theme: ThemeData(
          colorScheme: ColorScheme.fromSeed(seedColor: Colors.indigo),
          useMaterial3: true,
        ),
        home: const _AuthGate(),
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
