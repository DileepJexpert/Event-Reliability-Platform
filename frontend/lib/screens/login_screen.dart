import 'package:flutter/material.dart';
import 'package:provider/provider.dart';

import '../config/app_config.dart';
import '../services/auth_service.dart';

/// Login screen (§16). In OIDC mode it launches the bank SSO flow. In dev mode it offers two fixed
/// demo identities — a maker (Operator) and a checker (Approver) — so the maker-checker (4-eyes) flow
/// can be demoed as two separate logins without an IdP.
class LoginScreen extends StatefulWidget {
  const LoginScreen({super.key});

  @override
  State<LoginScreen> createState() => _LoginScreenState();
}

class _LoginScreenState extends State<LoginScreen> {
  bool _busy = false;
  String? _error;

  Future<void> _signInOidc() async {
    setState(() {
      _busy = true;
      _error = null;
    });
    try {
      await context.read<AuthService>().login();
    } catch (e) {
      setState(() => _error = '$e');
    } finally {
      if (mounted) setState(() => _busy = false);
    }
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ConstrainedBox(
          constraints: const BoxConstraints(maxWidth: 460),
          child: Card(
            margin: const EdgeInsets.all(24),
            child: Padding(
              padding: const EdgeInsets.all(32),
              child: Column(
                mainAxisSize: MainAxisSize.min,
                crossAxisAlignment: CrossAxisAlignment.stretch,
                children: [
                  const Icon(Icons.shield_moon, size: 52, color: Color(0xFF1F4E79)),
                  const SizedBox(height: 12),
                  Text('Event Reliability Console',
                      style: Theme.of(context).textTheme.titleLarge, textAlign: TextAlign.center),
                  const SizedBox(height: 4),
                  Text('Brod — DLQ triage & recovery',
                      style: Theme.of(context).textTheme.bodySmall, textAlign: TextAlign.center),
                  const SizedBox(height: 26),
                  if (_error != null) ...[
                    Text(_error!,
                        style: const TextStyle(color: Colors.red), textAlign: TextAlign.center),
                    const SizedBox(height: 12),
                  ],
                  if (AppConfig.isOidc)
                    FilledButton.icon(
                      onPressed: _busy ? null : _signInOidc,
                      icon: _busy
                          ? const SizedBox(
                              width: 16, height: 16, child: CircularProgressIndicator(strokeWidth: 2))
                          : const Icon(Icons.login),
                      label: const Text('Sign in with SSO'),
                    )
                  else ...[
                    Align(
                      alignment: Alignment.centerLeft,
                      child: Text('Demo sign-in — choose a role',
                          style: Theme.of(context).textTheme.labelLarge),
                    ),
                    const SizedBox(height: 12),
                    _devUser(
                      username: 'alice',
                      title: 'Alice · Maker',
                      subtitle: 'Operator — raises replay / quarantine requests',
                      roles: const ['VIEWER', 'OPERATOR'],
                      icon: Icons.engineering,
                      color: const Color(0xFF1F4E79),
                    ),
                    const SizedBox(height: 10),
                    _devUser(
                      username: 'bob',
                      title: 'Bob · Checker',
                      subtitle: 'Approver — approves / rejects requests (4-eyes)',
                      roles: const ['VIEWER', 'APPROVER'],
                      icon: Icons.verified_user,
                      color: const Color(0xFF0B6E66),
                    ),
                  ],
                  const SizedBox(height: 18),
                  Text('Mode: ${AppConfig.authMode} · ${AppConfig.apiBaseUrl}',
                      style: Theme.of(context).textTheme.bodySmall, textAlign: TextAlign.center),
                ],
              ),
            ),
          ),
        ),
      ),
    );
  }

  Widget _devUser({
    required String username,
    required String title,
    required String subtitle,
    required List<String> roles,
    required IconData icon,
    required Color color,
  }) {
    return OutlinedButton(
      onPressed: () => context.read<AuthService>().loginAsDev(username, roles),
      style: OutlinedButton.styleFrom(
        padding: const EdgeInsets.all(14),
        alignment: Alignment.centerLeft,
      ),
      child: Row(
        children: [
          CircleAvatar(
            radius: 18,
            backgroundColor: color.withOpacity(0.14),
            child: Icon(icon, color: color, size: 20),
          ),
          const SizedBox(width: 12),
          Expanded(
            child: Column(
              crossAxisAlignment: CrossAxisAlignment.start,
              mainAxisSize: MainAxisSize.min,
              children: [
                Text(title, style: const TextStyle(fontWeight: FontWeight.w700, fontSize: 14)),
                const SizedBox(height: 2),
                Text(subtitle, style: const TextStyle(fontSize: 11.5, color: Colors.black54)),
              ],
            ),
          ),
          const Icon(Icons.arrow_forward, size: 18),
        ],
      ),
    );
  }
}
