# Attendance AI Assistance

The AI and notification service of the
[Attendance Platform](https://github.com/nikolaev13nik/attendance-project-common). It turns an employee's
monthly work-time history into a short trend analysis written by Claude, and emails it to the
administrator who asked for the report.

It has no REST API, no database and no security layer: its only input is one Kafka topic, and everything it
needs arrives inside the message.

## Flow

```
att.user-statistic-ai-analysis (Kafka)
  -> StatisticAnalysisConsumer     validates the event, puts tenant and user into the log context
     -> StatisticAnalysisService
          PromptComposer           newest month first, trimmed to the configured window (default 6 months)
          LlmAnalysisClient        stub by default, Claude when att.ai.llm.provider=anthropic
          EmailNotificationService HTML email to the requester, every model sentence HTML-escaped
```

The event schema is generated from
[`attendance-statistic-events.yaml`](src/main/resources/swagger-source/attendance-statistic-events.yaml), a
byte-identical copy of the spec in
[TimeTracking](https://github.com/nikolaev13nik/Attendance-TimeTracking), which publishes these events.

## The Claude client

[`AnthropicLlmAnalysisClient`](src/main/java/att/ai/llm/AnthropicLlmAnalysisClient.java) uses the Anthropic
Java SDK with Claude Sonnet 5.5, low effort and a 4,000-token ceiling: one stateless request per analysis,
with the formatting rules as the system prompt and the history table as the user message.

- **Free unless asked.** The provider defaults to a stub that returns a fixed text, so tests and local runs
  never call the paid API.
- **Failures split by whether a retry can help.** Timeouts, rate limits and 5xx responses are retried;
  a bad key, missing credit, an invalid request or a refusal are marked non-retryable and go straight to
  the dead-letter topic `att.user-statistic-ai-analysis.dlq`.
- **No double charge on retry.** A small in-memory LRU cache (32 entries) keyed by the prompt means that
  when the email fails after the model answered, Kafka's retry reuses the answer instead of paying again.
- **Truncation is visible.** An answer cut off at the token limit is sent with a note rather than retried,
  since a retry would be cut at the same place.
- **Token usage** of every call is logged.

## Run and test

```bash
./mvnw test                  # no broker, no SMTP, no API key needed
./mvnw clean verify -Pci     # what CI runs: tests + JaCoCo, at least 85% line coverage
```

The build needs a GitHub token with `read:packages` under server id `github` in `~/.m2/settings.xml` to
download [attendance-common-lib](https://github.com/nikolaev13nik/attendance-common-lib). A separate live
test against the real API runs only when `ANTHROPIC_API_KEY` is set.

| Variable | Purpose |
| --- | --- |
| `ATT_AI_LLM_PROVIDER` | `stub` (default) or `anthropic` |
| `ANTHROPIC_API_KEY` | Claude API key, needed with `anthropic` |
| `ATT_AI_LLM_MODEL` | model id (default `claude-sonnet-5-5`) |
| `KAFKA_BOOTSTRAP_SERVERS` | Kafka brokers |
| `MAIL_ENABLED`, `MAIL_HOST`, `MAIL_PORT`, `MAIL_USERNAME`, `MAIL_PASSWORD`, `MAIL_FROM` | SMTP |

## Deployment

CI (GitHub Actions) builds and tests, pushes the image to GitHub Container Registry, tags the version and
writes it into [`helm/aiassistance`](helm/aiassistance), which is deployed to Kubernetes.
