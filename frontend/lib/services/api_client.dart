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

/// Thin REST client over the backend API (§15). Attaches the OIDC bearer token when present, and an
/// {@code X-Actor} header carrying the current acting user (honoured by the backend's local profile so
/// the maker-checker flow can be exercised without an IdP). Performs no business logic of its own.
class ApiClient {
  final AuthService auth;
  final http.Client _http = http.Client();

  ApiClient(this.auth);

  String get _base => AppConfig.apiBaseUrl;

  Map<String, String> get _headers => {
        'Accept': 'application/json',
        if (auth.token != null) 'Authorization': 'Bearer ${auth.token}',
        'X-Actor': auth.actingAs,
      };

  Future<PageResult<FailureSummary>> listFailures({
    String? status,
    String? topic,
    String? dlqTopic,
    String? sourceApp,
    String? classification,
    int page = 0,
    int size = 50,
  }) async {
    final query = <String, String>{
      'page': '$page',
      'size': '$size',
      if (status != null && status.isNotEmpty) 'status': status,
      if (topic != null && topic.isNotEmpty) 'topic': topic,
      if (dlqTopic != null && dlqTopic.isNotEmpty) 'dlqTopic': dlqTopic,
      if (sourceApp != null && sourceApp.isNotEmpty) 'sourceApp': sourceApp,
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

  /// Distinct topics / DLQ topics / source apps across all failures, to power the filter autocomplete.
  Future<Facets> getFacets() async {
    final res = await _http.get(Uri.parse('$_base/api/failures/facets'), headers: _headers);
    _check(res);
    return Facets.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<List<Incident>> listIncidents() async {
    final res = await _http.get(Uri.parse('$_base/api/incidents'), headers: _headers);
    _check(res);
    return (jsonDecode(res.body) as List<dynamic>)
        .map((e) => Incident.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  /// Aggregated analytics for the Trends tab (§16).
  Future<Trends> getTrends() async {
    final res = await _http.get(Uri.parse('$_base/api/trends'), headers: _headers);
    _check(res);
    return Trends.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  // ----- maker actions (raise requests; 4-eyes) -----

  Future<void> replay(String correlationId, {String? reason, String? targetTopic, String? payloadBase64}) =>
      _postJson('/api/failures/$correlationId/replay',
          {'reason': reason, 'targetTopic': targetTopic, 'payloadBase64': payloadBase64});

  Future<void> quarantine(String correlationId, {String? reason}) =>
      _postJson('/api/failures/$correlationId/quarantine', {'reason': reason});

  Future<void> bulkReplay(String incidentId, {String? reason, String? targetTopic}) =>
      _postJson('/api/incidents/$incidentId/bulk-replay', {'reason': reason, 'targetTopic': targetTopic});

  // ----- checker actions (maker-checker approvals) -----

  Future<List<Approval>> listApprovals({String status = 'PENDING'}) async {
    final uri = Uri.parse('$_base/api/approvals').replace(queryParameters: {'status': status});
    final res = await _http.get(uri, headers: _headers);
    _check(res);
    return (jsonDecode(res.body) as List<dynamic>)
        .map((e) => Approval.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<Approval> getApproval(String requestId) async {
    final res = await _http.get(Uri.parse('$_base/api/approvals/$requestId'), headers: _headers);
    _check(res);
    return Approval.fromJson(jsonDecode(res.body) as Map<String, dynamic>);
  }

  Future<void> approveRequest(String requestId, {String? reason}) =>
      _postJson('/api/approvals/$requestId/approve', {'reason': reason});

  Future<void> rejectRequest(String requestId, {String? reason}) =>
      _postJson('/api/approvals/$requestId/reject', {'reason': reason});

  /// Checker sends a request back to the maker for correction (optionally suggesting a fix).
  Future<void> returnToMaker(String requestId,
          {String? reason, String? targetTopic, String? payloadBase64}) =>
      _postJson('/api/approvals/$requestId/return',
          {'reason': reason, 'targetTopic': targetTopic, 'payloadBase64': payloadBase64});

  /// Maker corrects a returned request and resubmits it for approval.
  Future<void> resubmit(String requestId,
          {String? reason, String? targetTopic, String? payloadBase64}) =>
      _postJson('/api/approvals/$requestId/resubmit',
          {'reason': reason, 'targetTopic': targetTopic, 'payloadBase64': payloadBase64});

  /// The maker-checker round-trip for one request (request → return → resubmit → approve/reject).
  Future<List<AuditEvent>> requestHistory(String requestId) async {
    final res =
        await _http.get(Uri.parse('$_base/api/approvals/$requestId/history'), headers: _headers);
    _check(res);
    return (jsonDecode(res.body) as List<dynamic>)
        .map((e) => AuditEvent.fromJson(e as Map<String, dynamic>))
        .toList();
  }

  Future<void> _postJson(String path, Map<String, dynamic> body) async {
    final res = await _http.post(
      Uri.parse('$_base$path'),
      headers: {..._headers, 'Content-Type': 'application/json'},
      body: jsonEncode(body),
    );
    _check(res);
  }

  void _check(http.Response res) {
    if (res.statusCode >= 400) {
      throw ApiException(res.statusCode, res.body);
    }
  }
}
