import 'dart:convert';

import 'package:http/http.dart' as http;

import '../config/app_config.dart';
import '../models/models.dart';
import 'auth_service.dart';

/// Thrown for non-2xx responses from the backend.
class ApiException implements Exception {
  final int status;
  final String body;
  ApiException(this.status, this.body);
  @override
  String toString() => 'HTTP $status: $body';
}

/// Thin REST client over the backend API (§15). Attaches the OIDC bearer token when present;
/// performs no business logic of its own.
class ApiClient {
  final AuthService auth;
  final http.Client _http = http.Client();

  ApiClient(this.auth);

  String get _base => AppConfig.apiBaseUrl;

  Map<String, String> get _headers => {
        'Accept': 'application/json',
        if (auth.token != null) 'Authorization': 'Bearer ${auth.token}',
      };

  Future<PageResult<FailureSummary>> listFailures({
    String? status,
    String? topic,
    String? classification,
    int page = 0,
    int size = 50,
  }) async {
    final query = <String, String>{
      'page': '$page',
      'size': '$size',
      if (status != null && status.isNotEmpty) 'status': status,
      if (topic != null && topic.isNotEmpty) 'topic': topic,
      if (classification != null && classification.isNotEmpty) 'classification': classification,
    };
    final uri = Uri.parse('$_base/api/failures').replace(queryParameters: query);
    final res = await _http.get(uri, headers: _headers);
    _check(res);
    return PageResult.fromJson(
        jsonDecode(res.body) as Map<String, dynamic>, FailureSummary.fromJson);
  }

  Future<FailureDetail> getFailure(String correlationId) async {
    final res = await _http.get(Uri.parse('$_base/api/failures/$correlationId'), headers: _headers);
    _check(res);
    return FailureDetail.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<List<Incident>> listIncidents() async {
    final res = await _http.get(Uri.parse('$_base/api/incidents'), headers: _headers);
    _check(res);
    return (jsonDecode(res.body) as List<dynamic>)
        .map((e) => Incident.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<void> replay(String correlationId, {String? reason}) =>
      _action('/api/failures/$correlationId/replay', reason);

  Future<void> quarantine(String correlationId, {String? reason}) =>
      _action('/api/failures/$correlationId/quarantine', reason);

  Future<void> bulkReplay(String incidentId, {String? reason}) =>
      _action('/api/incidents/$incidentId/bulk-replay', reason);

  Future<void> _action(String path, String? reason) async {
    final res = await _http.post(
      Uri.parse('$_base$path'),
      headers: {..._headers, 'Content-Type': 'application/json'},
      body: jsonEncode({'reason': reason}),
    );
    _check(res);
  }

  void _check(http.Response res) {
    if (res.statusCode >= 400) {
      throw ApiException(res.statusCode, res.body);
    }
  }
}
