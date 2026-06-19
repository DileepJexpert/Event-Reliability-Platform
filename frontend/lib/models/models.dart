/// Data models mirroring the backend DTOs. The console holds no business logic (§16) — these are
/// plain transfer objects with JSON parsing only. Lifecycle states and classifications are kept as
/// strings (the backend's enum names) and formatted for display in the widgets.

class FailureSummary {
  final String correlationId;
  final String? state;
  final String? classification;
  final String? recommendedAction;
  final String? originalTopic;
  final String? dlqTopic;
  final String? sourceApp;
  final String? exceptionClass;
  final String? exceptionMessage;
  final int attemptCount;
  final String? currentTier;
  final String? rootCauseSignature;
  final String? reason;
  final int? firstFailedAt;
  final int? updatedAt;
  final String? owningTeam;

  FailureSummary({
    required this.correlationId,
    this.state,
    this.classification,
    this.recommendedAction,
    this.originalTopic,
    this.dlqTopic,
    this.sourceApp,
    this.exceptionClass,
    this.exceptionMessage,
    this.attemptCount = 0,
    this.currentTier,
    this.rootCauseSignature,
    this.reason,
    this.firstFailedAt,
    this.updatedAt,
    this.owningTeam,
  });

  factory FailureSummary.fromJson(Map<String, dynamic> j) => FailureSummary(
        correlationId: j['correlationId'] as String,
        state: j['state'] as String?,
        classification: j['classification'] as String?,
        recommendedAction: j['recommendedAction'] as String?,
        originalTopic: j['originalTopic'] as String?,
        dlqTopic: j['dlqTopic'] as String?,
        sourceApp: j['sourceApp'] as String?,
        exceptionClass: j['exceptionClass'] as String?,
        exceptionMessage: j['exceptionMessage'] as String?,
        attemptCount: (j['attemptCount'] ?? 0) as int,
        currentTier: j['currentTier'] as String?,
        rootCauseSignature: j['rootCauseSignature'] as String?,
        reason: j['reason'] as String?,
        firstFailedAt: j['firstFailedAt'] as int?,
        updatedAt: j['updatedAt'] as int?,
        owningTeam: j['owningTeam'] as String?,
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
  final String? dlqTopic;
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
  final String? owningTeam;
  final List<AuditEvent> auditTimeline;

  FailureDetail({
    required this.correlationId,
    this.state,
    this.classification,
    this.recommendedAction,
    this.originalTopic,
    this.dlqTopic,
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
    this.owningTeam,
    this.auditTimeline = const [],
  });

  factory FailureDetail.fromJson(Map<String, dynamic> j) => FailureDetail(
        correlationId: j['correlationId'] as String,
        state: j['state'] as String?,
        classification: j['classification'] as String?,
        recommendedAction: j['recommendedAction'] as String?,
        originalTopic: j['originalTopic'] as String?,
        dlqTopic: j['dlqTopic'] as String?,
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
        owningTeam: j['owningTeam'] as String?,
        auditTimeline: ((j['auditTimeline'] as List<dynamic>?) ?? const [])
            .map((e) => AuditEvent.fromJson(e as Map<String, dynamic>))
            .toList(),
      );
}

/// Distinct filter values for the failures-screen autocomplete (§16).
class Facets {
  final List<String> topics;
  final List<String> dlqTopics;
  final List<String> sourceApps;

  const Facets({this.topics = const [], this.dlqTopics = const [], this.sourceApps = const []});

  factory Facets.fromJson(Map<String, dynamic> j) => Facets(
        topics: ((j['topics'] as List<dynamic>?) ?? const []).map((e) => e.toString()).toList(),
        dlqTopics: ((j['dlqTopics'] as List<dynamic>?) ?? const []).map((e) => e.toString()).toList(),
        sourceApps:
            ((j['sourceApps'] as List<dynamic>?) ?? const []).map((e) => e.toString()).toList(),
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

/// A maker-checker approval request (§13) as shown to the checker — includes the original and the
/// maker's corrected payload so the console can show a diff.
class Approval {
  final String requestId;
  final String type;
  final String? correlationId;
  final String? incidentId;
  final String? maker;
  final String? makerReason;
  final String? targetTopic;
  final bool payloadEdited;
  final String? payloadOverrideBase64;
  final String? originalPayloadBase64;
  final String? exceptionClass;
  final String? originalTopic;
  final String status;
  final int createdAt;
  final String? checker;
  final String? checkerReason;
  final int? decidedAt;
  final int revision;

  Approval({
    required this.requestId,
    required this.type,
    this.correlationId,
    this.incidentId,
    this.maker,
    this.makerReason,
    this.targetTopic,
    this.payloadEdited = false,
    this.payloadOverrideBase64,
    this.originalPayloadBase64,
    this.exceptionClass,
    this.originalTopic,
    this.status = 'PENDING',
    this.createdAt = 0,
    this.checker,
    this.checkerReason,
    this.decidedAt,
    this.revision = 0,
  });

  String get target => correlationId ?? incidentId ?? requestId;

  factory Approval.fromJson(Map<String, dynamic> j) => Approval(
        requestId: j['requestId'] as String,
        type: (j['type'] ?? '') as String,
        correlationId: j['correlationId'] as String?,
        incidentId: j['incidentId'] as String?,
        maker: j['maker'] as String?,
        makerReason: j['makerReason'] as String?,
        targetTopic: j['targetTopic'] as String?,
        payloadEdited: (j['payloadEdited'] ?? false) as bool,
        payloadOverrideBase64: j['payloadOverrideBase64'] as String?,
        originalPayloadBase64: j['originalPayloadBase64'] as String?,
        exceptionClass: j['exceptionClass'] as String?,
        originalTopic: j['originalTopic'] as String?,
        status: (j['status'] ?? 'PENDING') as String,
        createdAt: (j['createdAt'] ?? 0) as int,
        checker: j['checker'] as String?,
        checkerReason: j['checkerReason'] as String?,
        decidedAt: j['decidedAt'] as int?,
        revision: (j['revision'] ?? 0) as int,
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

/// Aggregated analytics for the Trends tab (§16), mirroring the backend {@code TrendsDto}.
class Trends {
  final int total;
  final int open;
  final int resolved;
  final int parked;
  final double resolutionRate;
  final int? mttrMillis;
  final Map<String, int> byClassification;
  final Map<String, int> byState;
  final List<NameCount> topTopics;
  final List<NameCount> topSourceApps;
  final List<DailyCount> daily;

  const Trends({
    this.total = 0,
    this.open = 0,
    this.resolved = 0,
    this.parked = 0,
    this.resolutionRate = 0,
    this.mttrMillis,
    this.byClassification = const {},
    this.byState = const {},
    this.topTopics = const [],
    this.topSourceApps = const [],
    this.daily = const [],
  });

  factory Trends.fromJson(Map<String, dynamic> j) => Trends(
        total: (j['total'] ?? 0) as int,
        open: (j['open'] ?? 0) as int,
        resolved: (j['resolved'] ?? 0) as int,
        parked: (j['parked'] ?? 0) as int,
        resolutionRate: ((j['resolutionRate'] ?? 0) as num).toDouble(),
        mttrMillis: (j['mttrMillis'] as num?)?.toInt(),
        byClassification: _intMap(j['byClassification']),
        byState: _intMap(j['byState']),
        topTopics: _nameCounts(j['topTopics']),
        topSourceApps: _nameCounts(j['topSourceApps']),
        daily: ((j['daily'] as List<dynamic>?) ?? const [])
            .map((e) => DailyCount.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  static Map<String, int> _intMap(dynamic v) {
    final m = <String, int>{};
    if (v is Map) {
      v.forEach((k, val) => m[k.toString()] = (val as num).toInt());
    }
    return m;
  }

  static List<NameCount> _nameCounts(dynamic v) => ((v as List<dynamic>?) ?? const [])
      .map((e) => NameCount.fromJson(e as Map<String, dynamic>))
      .toList();
}

/// A name (source topic / app) with its failure count.
class NameCount {
  final String name;
  final int count;
  const NameCount(this.name, this.count);
  factory NameCount.fromJson(Map<String, dynamic> j) =>
      NameCount((j['name'] ?? '') as String, (j['count'] ?? 0) as int);
}

/// Failures first-seen on a given UTC date ({@code yyyy-MM-dd}).
class DailyCount {
  final String date;
  final int count;
  const DailyCount(this.date, this.count);
  factory DailyCount.fromJson(Map<String, dynamic> j) =>
      DailyCount((j['date'] ?? '') as String, (j['count'] ?? 0) as int);
}

/// The operations assistant's reply ({@code POST /api/assistant/ask}). [citations] are the
/// incident/failure ids the model was grounded in; [grounded] is false when the assistant is not
/// configured or had no context.
class AssistantAnswer {
  final String answer;
  final List<String> citations;
  final int contextSize;
  final bool grounded;

  const AssistantAnswer({
    this.answer = '',
    this.citations = const [],
    this.contextSize = 0,
    this.grounded = false,
  });

  factory AssistantAnswer.fromJson(Map<String, dynamic> j) => AssistantAnswer(
        answer: (j['answer'] ?? '') as String,
        citations: ((j['citations'] as List<dynamic>?) ?? const [])
            .map((e) => e.toString())
            .toList(),
        contextSize: (j['contextSize'] ?? 0) as int,
        grounded: (j['grounded'] ?? false) as bool,
      );
}

/// Financial exposure / "value at risk" ({@code GET /api/exposure}) — money tied up in stuck failures.
class Exposure {
  final Map<String, double> atRiskByCurrency;
  final int atRiskCount;
  final int withoutAmount;
  final int totalConsidered;
  final List<ExposureGroup> byTeam;
  final List<ExposureGroup> byTopic;
  final List<ExposureItem> topExposures;
  final int? oldestAtRiskAt;
  final int generatedAt;

  const Exposure({
    this.atRiskByCurrency = const {},
    this.atRiskCount = 0,
    this.withoutAmount = 0,
    this.totalConsidered = 0,
    this.byTeam = const [],
    this.byTopic = const [],
    this.topExposures = const [],
    this.oldestAtRiskAt,
    this.generatedAt = 0,
  });

  factory Exposure.fromJson(Map<String, dynamic> j) => Exposure(
        atRiskByCurrency: doubleMap(j['atRiskByCurrency']),
        atRiskCount: (j['atRiskCount'] ?? 0) as int,
        withoutAmount: (j['withoutAmount'] ?? 0) as int,
        totalConsidered: (j['totalConsidered'] ?? 0) as int,
        byTeam: _groups(j['byTeam']),
        byTopic: _groups(j['byTopic']),
        topExposures: ((j['topExposures'] as List<dynamic>?) ?? const [])
            .map((e) => ExposureItem.fromJson(e as Map<String, dynamic>))
            .toList(),
        oldestAtRiskAt: (j['oldestAtRiskAt'] as num?)?.toInt(),
        generatedAt: (j['generatedAt'] ?? 0) as int,
      );

  static Map<String, double> doubleMap(dynamic v) {
    final m = <String, double>{};
    if (v is Map) {
      v.forEach((k, val) => m[k.toString()] = (val as num).toDouble());
    }
    return m;
  }

  static List<ExposureGroup> _groups(dynamic v) => ((v as List<dynamic>?) ?? const [])
      .map((e) => ExposureGroup.fromJson(e as Map<String, dynamic>))
      .toList();
}

/// Exposure for one grouping (a team or topic): count of stuck failures + amount per currency.
class ExposureGroup {
  final String name;
  final int count;
  final Map<String, double> amountByCurrency;
  const ExposureGroup(this.name, this.count, this.amountByCurrency);
  factory ExposureGroup.fromJson(Map<String, dynamic> j) => ExposureGroup(
        (j['name'] ?? '') as String,
        (j['count'] ?? 0) as int,
        Exposure.doubleMap(j['amountByCurrency']),
      );
}

/// A single high-value stuck failure, for the "biggest exposures" table.
class ExposureItem {
  final String correlationId;
  final double amount;
  final String currency;
  final String team;
  final String topic;
  final String? state;
  final int? firstFailedAt;

  const ExposureItem({
    required this.correlationId,
    required this.amount,
    required this.currency,
    required this.team,
    required this.topic,
    this.state,
    this.firstFailedAt,
  });

  factory ExposureItem.fromJson(Map<String, dynamic> j) => ExposureItem(
        correlationId: (j['correlationId'] ?? '') as String,
        amount: ((j['amount'] ?? 0) as num).toDouble(),
        currency: (j['currency'] ?? '') as String,
        team: (j['team'] ?? '') as String,
        topic: (j['topic'] ?? '') as String,
        state: j['state'] as String?,
        firstFailedAt: (j['firstFailedAt'] as num?)?.toInt(),
      );
}

/// Adaptive anomaly detection report ({@code GET /api/anomalies}).
class AnomalyReport {
  final List<Anomaly> anomalies;
  final int bucketMillis;
  final int lookbackMillis;
  final double sensitivity;
  final int generatedAt;

  const AnomalyReport({
    this.anomalies = const [],
    this.bucketMillis = 0,
    this.lookbackMillis = 0,
    this.sensitivity = 0,
    this.generatedAt = 0,
  });

  factory AnomalyReport.fromJson(Map<String, dynamic> j) => AnomalyReport(
        anomalies: ((j['anomalies'] as List<dynamic>?) ?? const [])
            .map((e) => Anomaly.fromJson(e as Map<String, dynamic>))
            .toList(),
        bucketMillis: (j['bucketMillis'] ?? 0) as int,
        lookbackMillis: (j['lookbackMillis'] ?? 0) as int,
        sensitivity: ((j['sensitivity'] ?? 0) as num).toDouble(),
        generatedAt: (j['generatedAt'] ?? 0) as int,
      );
}

/// One anomalous series: the latest-bucket count against its own baseline.
class Anomaly {
  final String dimension;
  final String key;
  final int recentCount;
  final double baselineMean;
  final double expected;
  final double score;
  final String? sampleCorrelationId;

  const Anomaly({
    required this.dimension,
    required this.key,
    required this.recentCount,
    required this.baselineMean,
    required this.expected,
    required this.score,
    this.sampleCorrelationId,
  });

  factory Anomaly.fromJson(Map<String, dynamic> j) => Anomaly(
        dimension: (j['dimension'] ?? '') as String,
        key: (j['key'] ?? '') as String,
        recentCount: (j['recentCount'] ?? 0) as int,
        baselineMean: ((j['baselineMean'] ?? 0) as num).toDouble(),
        expected: ((j['expected'] ?? 0) as num).toDouble(),
        score: ((j['score'] ?? 0) as num).toDouble(),
        sampleCorrelationId: j['sampleCorrelationId'] as String?,
      );
}

/// Reconciliation / completeness report ({@code GET /api/reconciliation}).
class ReconciliationReport {
  final int totalCaptured;
  final int completed;
  final int open;
  final double completionRate;
  final int? oldestOpenAt;
  final int generatedAt;
  final List<ReconciliationGroup> bySource;
  final List<ReconciliationGroup> byTopic;
  final List<ReconciliationItem> oldestOpen;

  const ReconciliationReport({
    this.totalCaptured = 0,
    this.completed = 0,
    this.open = 0,
    this.completionRate = 0,
    this.oldestOpenAt,
    this.generatedAt = 0,
    this.bySource = const [],
    this.byTopic = const [],
    this.oldestOpen = const [],
  });

  factory ReconciliationReport.fromJson(Map<String, dynamic> j) => ReconciliationReport(
        totalCaptured: (j['totalCaptured'] ?? 0) as int,
        completed: (j['completed'] ?? 0) as int,
        open: (j['open'] ?? 0) as int,
        completionRate: ((j['completionRate'] ?? 0) as num).toDouble(),
        oldestOpenAt: (j['oldestOpenAt'] as num?)?.toInt(),
        generatedAt: (j['generatedAt'] ?? 0) as int,
        bySource: _groups(j['bySource']),
        byTopic: _groups(j['byTopic']),
        oldestOpen: ((j['oldestOpen'] as List<dynamic>?) ?? const [])
            .map((e) => ReconciliationItem.fromJson(e as Map<String, dynamic>))
            .toList(),
      );

  static List<ReconciliationGroup> _groups(dynamic v) => ((v as List<dynamic>?) ?? const [])
      .map((e) => ReconciliationGroup.fromJson(e as Map<String, dynamic>))
      .toList();
}

/// Completeness for one grouping (source app / topic): completed vs open.
class ReconciliationGroup {
  final String name;
  final int total;
  final int completed;
  final int open;
  final double completionRate;
  final int? oldestOpenAt;

  const ReconciliationGroup({
    required this.name,
    required this.total,
    required this.completed,
    required this.open,
    required this.completionRate,
    this.oldestOpenAt,
  });

  factory ReconciliationGroup.fromJson(Map<String, dynamic> j) => ReconciliationGroup(
        name: (j['name'] ?? '') as String,
        total: (j['total'] ?? 0) as int,
        completed: (j['completed'] ?? 0) as int,
        open: (j['open'] ?? 0) as int,
        completionRate: ((j['completionRate'] ?? 0) as num).toDouble(),
        oldestOpenAt: (j['oldestOpenAt'] as num?)?.toInt(),
      );
}

/// A single open (un-reconciled) failure, for the worklist.
class ReconciliationItem {
  final String correlationId;
  final String topic;
  final String team;
  final String? state;
  final int? firstFailedAt;

  const ReconciliationItem({
    required this.correlationId,
    required this.topic,
    required this.team,
    this.state,
    this.firstFailedAt,
  });

  factory ReconciliationItem.fromJson(Map<String, dynamic> j) => ReconciliationItem(
        correlationId: (j['correlationId'] ?? '') as String,
        topic: (j['topic'] ?? '') as String,
        team: (j['team'] ?? '') as String,
        state: j['state'] as String?,
        firstFailedAt: (j['firstFailedAt'] as num?)?.toInt(),
      );
}
