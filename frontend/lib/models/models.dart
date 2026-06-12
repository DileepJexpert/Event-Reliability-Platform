/// Data models mirroring the backend DTOs. The console holds no business logic (§16) — these are
/// plain transfer objects with JSON parsing only. Lifecycle states and classifications are kept as
/// strings (the backend's enum names) and formatted for display in the widgets.

class FailureSummary {
  final String correlationId;
  final String? state;
  final String? classification;
  final String? recommendedAction;
  final String? originalTopic;
  final String? sourceApp;
  final String? exceptionClass;
  final String? exceptionMessage;
  final int attemptCount;
  final String? currentTier;
  final String? rootCauseSignature;
  final String? reason;
  final int? firstFailedAt;
  final int? updatedAt;

  FailureSummary({
    required this.correlationId,
    this.state,
    this.classification,
    this.recommendedAction,
    this.originalTopic,
    this.sourceApp,
    this.exceptionClass,
    this.exceptionMessage,
    this.attemptCount = 0,
    this.currentTier,
    this.rootCauseSignature,
    this.reason,
    this.firstFailedAt,
    this.updatedAt,
  });

  factory FailureSummary.fromJson(Map<String, dynamic> j) => FailureSummary(
        correlationId: j['correlationId'] as String,
        state: j['state'] as String?,
        classification: j['classification'] as String?,
        recommendedAction: j['recommendedAction'] as String?,
        originalTopic: j['originalTopic'] as String?,
        sourceApp: j['sourceApp'] as String?,
        exceptionClass: j['exceptionClass'] as String?,
        exceptionMessage: j['exceptionMessage'] as String?,
        attemptCount: (j['attemptCount'] ?? 0) as int,
        currentTier: j['currentTier'] as String?,
        rootCauseSignature: j['rootCauseSignature'] as String?,
        reason: j['reason'] as String?,
        firstFailedAt: j['firstFailedAt'] as int?,
        updatedAt: j['updatedAt'] as int?,
      );
}

class AuditEvent {
  final String correlationId;
  final int at;
  final String? fromState;
  final String? toState;
  final String action;
  final String actor;
  final String? detail;

  AuditEvent({
    required this.correlationId,
    required this.at,
    this.fromState,
    this.toState,
    required this.action,
    required this.actor,
    this.detail,
  });

  factory AuditEvent.fromJson(Map<String, dynamic> j) => AuditEvent(
        correlationId: (j['correlationId'] ?? '') as String,
        at: (j['at'] ?? 0) as int,
        fromState: j['fromState'] as String?,
        toState: j['toState'] as String?,
        action: (j['action'] ?? '') as String,
        actor: (j['actor'] ?? 'system') as String,
        detail: j['detail'] as String?,
      );
}

class FailureDetail {
  final String correlationId;
  final String? state;
  final String? classification;
  final String? recommendedAction;
  final String? originalTopic;
  final int? originalPartition;
  final int? originalOffset;
  final String? sourceApp;
  final String? exceptionClass;
  final String? exceptionMessage;
  final String? stacktrace;
  final int attemptCount;
  final String? currentTier;
  final int? eligibleAt;
  final String? schemaVersion;
  final String? payloadHash;
  final String? rootCauseSignature;
  final String? reason;
  final String? lastError;
  final String? lastActor;
  final String? payloadBase64;
  final Map<String, dynamic> headers;
  final int? firstFailedAt;
  final int? createdAt;
  final int? updatedAt;
  final List<AuditEvent> auditTimeline;

  FailureDetail({
    required this.correlationId,
    this.state,
    this.classification,
    this.recommendedAction,
    this.originalTopic,
    this.originalPartition,
    this.originalOffset,
    this.sourceApp,
    this.exceptionClass,
    this.exceptionMessage,
    this.stacktrace,
    this.attemptCount = 0,
    this.currentTier,
    this.eligibleAt,
    this.schemaVersion,
    this.payloadHash,
    this.rootCauseSignature,
    this.reason,
    this.lastError,
    this.lastActor,
    this.payloadBase64,
    this.headers = const {},
    this.firstFailedAt,
    this.createdAt,
    this.updatedAt,
    this.auditTimeline = const [],
  });

  factory FailureDetail.fromJson(Map<String, dynamic> j) => FailureDetail(
        correlationId: j['correlationId'] as String,
        state: j['state'] as String?,
        classification: j['classification'] as String?,
        recommendedAction: j['recommendedAction'] as String?,
        originalTopic: j['originalTopic'] as String?,
        originalPartition: j['originalPartition'] as int?,
        originalOffset: j['originalOffset'] as int?,
        sourceApp: j['sourceApp'] as String?,
        exceptionClass: j['exceptionClass'] as String?,
        exceptionMessage: j['exceptionMessage'] as String?,
        stacktrace: j['stacktrace'] as String?,
        attemptCount: (j['attemptCount'] ?? 0) as int,
        currentTier: j['currentTier'] as String?,
        eligibleAt: j['eligibleAt'] as int?,
        schemaVersion: j['schemaVersion'] as String?,
        payloadHash: j['payloadHash'] as String?,
        rootCauseSignature: j['rootCauseSignature'] as String?,
        reason: j['reason'] as String?,
        lastError: j['lastError'] as String?,
        lastActor: j['lastActor'] as String?,
        payloadBase64: j['payloadBase64'] as String?,
        headers: (j['headers'] as Map<String, dynamic>?) ?? const {},
        firstFailedAt: j['firstFailedAt'] as int?,
        createdAt: j['createdAt'] as int?,
        updatedAt: j['updatedAt'] as int?,
        auditTimeline: ((j['auditTimeline'] as List<dynamic>?) ?? const [])
            .map((e) => AuditEvent.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

class Incident {
  final String id;
  final String rootCause;
  final String sourceTopic;
  final int count;
  final int threshold;
  final int windowStart;
  final int windowEnd;
  final int firstSeenAt;
  final String status;
  final String? exampleCorrelationId;

  Incident({
    required this.id,
    required this.rootCause,
    required this.sourceTopic,
    required this.count,
    required this.threshold,
    required this.windowStart,
    required this.windowEnd,
    required this.firstSeenAt,
    required this.status,
    this.exampleCorrelationId,
  });

  bool get isActive => status == 'ACTIVE';

  factory Incident.fromJson(Map<String, dynamic> j) => Incident(
        id: j['id'] as String,
        rootCause: (j['rootCause'] ?? '') as String,
        sourceTopic: (j['sourceTopic'] ?? '') as String,
        count: (j['count'] ?? 0) as int,
        threshold: (j['threshold'] ?? 0) as int,
        windowStart: (j['windowStart'] ?? 0) as int,
        windowEnd: (j['windowEnd'] ?? 0) as int,
        firstSeenAt: (j['firstSeenAt'] ?? 0) as int,
        status: (j['status'] ?? 'ACTIVE') as String,
        exampleCorrelationId: j['exampleCorrelationId'] as String?,
      );
}

class PageResult<T> {
  final List<T> content;
  final int page;
  final int size;
  final int totalElements;
  final int totalPages;

  PageResult({
    required this.content,
    required this.page,
    required this.size,
    required this.totalElements,
    required this.totalPages,
  });

  factory PageResult.fromJson(
          Map<String, dynamic> j, T Function(Map<String, dynamic>) item) =>
      PageResult<T>(
        content: ((j['content'] as List<dynamic>?) ?? const [])
            .map((e) => item(e as Map<String, dynamic>))
            .toList(),
        page: (j['page'] ?? 0) as int,
        size: (j['size'] ?? 0) as int,
        totalElements: (j['totalElements'] ?? 0) as int,
        totalPages: (j['totalPages'] ?? 0) as int,
      );
}
