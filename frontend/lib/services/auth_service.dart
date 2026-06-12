import 'dart:convert';

import 'package:flutter/foundation.dart';
import 'package:flutter_appauth/flutter_appauth.dart';
import 'package:flutter_secure_storage/flutter_secure_storage.dart';

import '../config/app_config.dart';

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

  Future<void> bootstrap() async {
    if (!AppConfig.isOidc) {
      _state = const AuthState(
          authenticated: true, username: 'dev-operator', roles: ['VIEWER', 'OPERATOR']);
      notifyListeners();
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
          authenticated: true, username: 'dev-operator', roles: ['VIEWER', 'OPERATOR']);
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
