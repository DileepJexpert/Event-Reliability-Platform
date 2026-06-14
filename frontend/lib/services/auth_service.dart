import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../config/app_config.dart';

/// Fixed demo identities for the dev showcase (no IdP): a maker and a checker, so the maker-checker
/// (4-eyes) flow can be shown as two separate logins and switched from the top bar. Dev-only — real
/// deployments use OIDC and the token's `roles` claim.
class DemoUser {
  final String username;
  final String label;
  final String role;
  final String description;
  final List<String> roles;
  const DemoUser(this.username, this.label, this.role, this.description, this.roles);
}

const List<DemoUser> kDemoUsers = [
  DemoUser('alice', 'Alice', 'Maker', 'Operator — raises replay / quarantine requests',
      ['VIEWER', 'OPERATOR']),
  DemoUser('bob', 'Bob', 'Checker', 'Approver — approves / rejects requests (4-eyes)',
      ['VIEWER', 'APPROVER']),
];

/// Authentication state exposed to the UI. Roles come from the OIDC token's `roles` claim and gate
/// the operator actions (replay / quarantine / bulk-replay), mirroring the backend (§17).
class AuthState {
  final bool authenticated;
  final String? accessToken;
  final String username;
  final List<String> roles;

  const AuthState({
    required this.authenticated,
    this.accessToken,
    this.username = 'anonymous',
    this.roles = const [],
  });

  bool get isOperator => roles.contains('OPERATOR');
  bool get isApprover => roles.contains('APPROVER');
  bool get isViewer => roles.contains('VIEWER') || roles.contains('OPERATOR');
}

/// Handles login against the bank's OIDC provider (Authorization Code + PKCE via AppAuth). In `dev`
/// mode it short-circuits to a local operator identity so the console works against the backend's
/// permissive default profile without an IdP.
class AuthService extends ChangeNotifier {
  final FlutterAppAuth _appAuth = const FlutterAppAuth();
  final FlutterSecureStorage _storage = const FlutterSecureStorage();

  AuthState _state = const AuthState(authenticated: false);
  AuthState get state => _state;
  String? get token => _state.accessToken;

  /// The user the console acts as for audited actions, sent as the {@code X-Actor} header. In dev it
  /// is editable so a single browser can play both maker and checker (the backend honours the header
  /// only when there is no real identity); under OIDC it is the authenticated identity.
  String _actingAs = 'dev-operator';
  String get actingAs => _actingAs;
  void setActingAs(String who) {
    final value = who.trim();
    _actingAs = value.isEmpty ? _state.username : value;
    notifyListeners();
  }

  Future<void> bootstrap() async {
    if (!AppConfig.isOidc) {
      // Dev: don't auto-login — show the login screen so a demo maker/checker can be picked.
      return;
    }
    final stored = await _storage.read(key: 'access_token');
    if (stored != null) {
      _applyToken(stored);
    }
  }

  Future<void> login() async {
    if (!AppConfig.isOidc) {
      _state = const AuthState(
          authenticated: true, username: 'dev-operator', roles: ['VIEWER', 'OPERATOR', 'APPROVER']);
      notifyListeners();
      return;
    }
    final result = await _appAuth.authorizeAndExchangeCode(
      AuthorizationTokenRequest(
        AppConfig.oidcClientId,
        AppConfig.oidcRedirectUri,
        issuer: AppConfig.oidcIssuer,
        scopes: AppConfig.oidcScopes,
        promptValues: const ['login'],
      ),
    );
    final accessToken = result.accessToken;
    if (accessToken != null) {
      await _storage.write(key: 'access_token', value: accessToken);
      _applyToken(accessToken);
    }
  }

  /// Dev-only: sign in as a fixed demo identity (no IdP), so a maker and a checker appear as two
  /// separate logins for a showcase. The roles gate the UI exactly as the OIDC `roles` claim would,
  /// and the username is sent as the X-Actor header for audit + the distinct-checker (4-eyes) rule.
  void loginAsDev(String username, List<String> roles) {
    _state = AuthState(authenticated: true, username: username, roles: List.unmodifiable(roles));
    _actingAs = username;
    notifyListeners();
  }

  Future<void> logout() async {
    await _storage.delete(key: 'access_token');
    _state = const AuthState(authenticated: false);
    notifyListeners();
  }

  void _applyToken(String token) {
    final claims = _decodeJwt(token);
    final roles = (claims['roles'] as List<dynamic>?)?.map((e) => e.toString()).toList() ?? const [];
    final username = (claims['preferred_username'] ?? claims['sub'] ?? 'user').toString();
    _state = AuthState(authenticated: true, accessToken: token, username: username, roles: roles);
    _actingAs = username;
    notifyListeners();
  }

  Map<String, dynamic> _decodeJwt(String token) {
    try {
      final parts = token.split('.');
      if (parts.length != 3) return {};
      final payload = utf8.decode(base64Url.decode(base64Url.normalize(parts[1])));
      final decoded = jsonDecode(payload);
      return decoded is Map<String, dynamic> ? decoded : {};
    } catch (_) {
      return {};
    }
  }
}
