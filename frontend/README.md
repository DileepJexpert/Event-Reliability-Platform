# Event Reliability Console (`event-reliability-console`)

The single Flutter operations console for the Event Reliability Platform. It talks to the backend
over **REST + SSE only** and contains **no business logic** (spec §16) — every decision lives in the
backend.

## Screens (§16)

| Screen | What it does |
| --- | --- |
| **Login** | OIDC/SSO sign-in (Authorization Code + PKCE) or a local "dev" identity. |
| **Dashboard** | Live failure rate, counts by classification, active incident banner — updated in real time from the SSE feed. |
| **Failures** | Filter by status / topic / classification; jump to a correlation id. |
| **Failure detail** | Metadata, classification, **full audit timeline**, and operator actions (replay, quarantine). |
| **Incidents** | Active systemic patterns, drill-in to an example message, and **one-click bulk replay**. |
| **Audit search** | Look up a correlation id and review its immutable audit history. |

Operator actions (replay / quarantine / bulk-replay) are only shown to users with the `OPERATOR`
role, mirroring the backend's authorization (§17).

## Project layout

```
lib/
├── main.dart / app.dart        # entrypoint + auth gate
├── config/app_config.dart      # API base URL, OIDC settings, auth mode (--dart-define)
├── models/models.dart          # DTOs mirroring the backend API
├── services/
│   ├── api_client.dart         # REST client (§15 endpoints)
│   ├── event_stream.dart       # SSE client for GET /api/stream (§14)
│   └── auth_service.dart       # OIDC (flutter_appauth) + dev mode
├── state/dashboard_state.dart  # live dashboard view-state (subscribes to SSE)
├── widgets/common.dart         # chips, colours, timestamp formatting
└── screens/                    # the six screens above
```

## Running

> **Heads-up:** this app was authored without a Flutter SDK in the build environment, so it has not
> been compiled here. Generate the platform scaffolding and fetch packages before first run.

```bash
cd frontend
flutter create .            # generates android/ ios/ web/ etc. around the existing lib/ + pubspec
flutter pub get
flutter run -d chrome \
  --dart-define=API_BASE_URL=http://localhost:8080 \
  --dart-define=AUTH_MODE=dev
```

### Configuration (`--dart-define`)

| Key | Default | Meaning |
| --- | --- | --- |
| `API_BASE_URL` | `http://localhost:8080` | Backend base URL (REST + SSE). |
| `AUTH_MODE` | `dev` | `dev` (no auth, matches backend default profile) or `oidc`. |
| `OIDC_ISSUER` | – | OIDC issuer URL (bank SSO) when `AUTH_MODE=oidc`. |
| `OIDC_CLIENT_ID` | `event-reliability-console` | OIDC client id. |
| `OIDC_REDIRECT_URI` | `com.eventreliability.console://oauthredirect` | Native redirect URI. |

In `dev` mode the console signs in as a local operator and sends no bearer token — which works
against the backend running in its default (permissive) profile. In `oidc` mode it performs the SSO
flow, attaches the access token to every request, and reads roles from the token's `roles` claim.
