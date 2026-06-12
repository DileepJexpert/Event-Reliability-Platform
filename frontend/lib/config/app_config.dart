/// Runtime configuration for the console. Values can be overridden at build/run time with
/// `--dart-define`, e.g. `flutter run --dart-define=API_BASE_URL=https://brod.bank.internal`.
class AppConfig {
  /// Base URL of the Event Reliability backend (REST + SSE).
  static const String apiBaseUrl =
      String.fromEnvironment('API_BASE_URL', defaultValue: 'http://localhost:8080');

  // ---- OIDC / bank SSO (used when authMode == oidc) ----
  static const String oidcIssuer =
      String.fromEnvironment('OIDC_ISSUER', defaultValue: '');
  static const String oidcClientId =
      String.fromEnvironment('OIDC_CLIENT_ID', defaultValue: 'event-reliability-console');
  static const String oidcRedirectUri = String.fromEnvironment('OIDC_REDIRECT_URI',
      defaultValue: 'com.eventreliability.console://oauthredirect');
  static const List<String> oidcScopes = ['openid', 'profile', 'roles'];

  /// `dev` (no auth — matches the backend's default profile) or `oidc` (bank SSO).
  static const String authMode =
      String.fromEnvironment('AUTH_MODE', defaultValue: 'dev');

  static bool get isOidc => authMode == 'oidc';
}
