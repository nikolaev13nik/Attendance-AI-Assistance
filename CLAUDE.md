# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## System context

This repo is the **AI Assistance & Notification** microservice (`att.ai`) in a system of ~4 services
(see `../attendance-project-common/docs/TimeTracker_docs.md` for the overall design). It is the
downstream end of the monthly-statistics flow: **Attendance-TimeTracking** (`../Attendance-TimeTracking`)
computes a user's monthly statistics, enriches them with recent history, and publishes a
`UserStatisticAnalysisRequestEvent` to Kafka topic `att.user-statistic-ai-analysis`. This service
consumes it, composes the history into an LLM prompt, and emails the resulting analysis to the person who
requested the report.

What this service deliberately does **not** have, and must not grow without a deliberate decision:

- **No REST API.** No controller, no `@RestController`, no inbound HTTP except `/actuator/health`.
  Its only input is one Kafka topic.
- **No database.** No JPA, no Flyway, no datasource. All the data it needs arrives inside the message
  (`entries` carries up to 7 months of statistics).
- **No Spring Security.** With no API to protect there is nothing to authenticate. Do not add a resource
  server here; JWT validation belongs to the services that serve requests.
- **No outbound HTTP client.** It does not call Accounting or TimeTracking. If you find yourself needing
  a user's name or email, note that TimeTracking already resolves the requester and puts it on the
  message — extend the event contract rather than adding a client.

## Commands

```bash
./mvnw clean install              # build (also regenerates the message DTOs, see below)
./mvnw test                       # run all tests
./mvnw test -Dtest=PromptComposerTest                      # one test class
./mvnw test -Dtest=PromptComposerTest#compose_emptyHistoryStatesThereIsNoDataTest   # one method
./mvnw clean verify -Pci          # what CI runs: build + tests + JaCoCo (min 85% line coverage)
./mvnw spring-boot:run            # run locally; needs KAFKA_BOOTSTRAP_SERVERS to reach a broker
```

No env var is strictly required to start. `MAIL_ENABLED=false` runs the whole pipeline and logs the
analysis instead of sending it, which is how the tests work and the right setting before SMTP exists.

The JaCoCo threshold is a real gate: `-Pci` fails under 85% line coverage bundle-wide (generated sources
and the `*Application` class excluded — see the `ci` profile). New production code needs tests in the
same change. `.github/workflows/ci.yml` then builds and pushes a Docker image to GHCR and tags the
version on `main`; it is a copy of TimeTracking's workflow and should be kept in sync with it.

There is no linter/formatter configured (the shared IntelliJ Google-style config lives in
`../attendance-project-common/sharedFiles/`).

## Architecture

### Contract-first events: one spec → generated DTOs

`src/main/resources/swagger-source/attendance-statistic-events.yaml` generates `att.ai.messaging.dto` via
`openapi-generator-maven-plugin` bound to `generate-sources`. **Never hand-write a class into
`att.ai.messaging.dto`** — it is overwritten on every build and it breaks the JaCoCo exclusions.

That yaml is a **byte-identical copy** of TimeTracking's own
`src/main/resources/swagger-source/attendance-statistic-events.yaml`. There is no shared Java artifact to
depend on (`attendance-project-common` contains no Java at all), so copying the spec is the established
pattern in this system. Keep it byte-identical so `diff` is the sync check:

```bash
diff src/main/resources/swagger-source/attendance-statistic-events.yaml \
     ../Attendance-TimeTracking/src/main/resources/swagger-source/attendance-statistic-events.yaml
```

Do not add a "this is a copy" header — it would make that diff permanently non-empty and useless.

The spec generates six classes; this service only consumes `UserStatisticAnalysisRequestEvent` (and its
nested `UserStatisticHistoryEntryDto`). `MonthStatisticEvent` is generated but unused here — that is the
price of copying the whole spec instead of trimming it, and it is deliberate: trimming would turn every
future re-sync into a manual merge.

### The pipeline

```
att.user-statistic-ai-analysis
  -> StatisticAnalysisConsumer        validate (jakarta Validator), build the context, trigger
     -> StatisticAnalysisService      the pipeline: every main step is called from here, in order
        1. PromptComposer.applyHistoryWindow   sorts newest-first, trims to att.ai.analysis.history-months
        2. PromptComposer.composePrompt        renders the window placed by step 1
        3. LlmAnalysisClient.analyse           StubLlmAnalysisClient returns a fixed string for now
        4. EmailNotificationService.notifyRequester   MimeMessage to event.reportEmail
             -> EmailContentBuilder            subject + HTML body
```

### `AnalysisContext`: one mutable object, `void` steps

`AnalysisContext` (`att.ai.context`) is the single input/output object threaded through a run. The
**consumer builds it** from the Kafka message (`AnalysisContext.builder().event(event).build()`) and hands
it to `StatisticAnalysisService.analyse(context)`, which is `void`. Each step takes the context and
nothing else, and writes its result onto it:

| Field      | Written by                                   | Read by                                |
|------------|----------------------------------------------|----------------------------------------|
| `event`    | the consumer, once                           | every step                             |
| `history`  | `PromptComposer.applyHistoryWindow` **only** | `composePrompt`, `EmailContentBuilder` |
| `prompt`   | `PromptComposer.composePrompt`               | the LLM step                           |
| `analysis` | `StatisticAnalysisService.requestAnalysis`   | `EmailContentBuilder`                  |

Same idiom as `DataTimeContext` in `../Attendance-TimeTracking` — built by the caller at the edge of the
flow, carrying input and accumulating output. Follow it for any new step: a `void` method taking the
context, not a new return type. Two deliberate exceptions, both about keeping a boundary clean:

- **`LlmAnalysisClient` stays `String analyse(String prompt)`.** A model-provider change must not reach
  outside `att.ai.llm`, so the provider does not get to know about the context. The service wraps the call
  in its own `void` step.
- **`EmailContentBuilder.subject/htmlBody` return their text.** They are sub-steps of the notification
  step, and a rendered subject and body are email presentation, not analysis state.

`history` defaults to an empty list (`@Builder.Default`), which is what replaced an older hazard: the
prompt used to be built from whatever list you passed, so unsorted entries produced silently wrong output.
`composePrompt` can no longer be handed a list at all, and running it out of order degrades to the "no
statistics" prompt instead of throwing. The newest-first order that `EmailContentBuilder.subject` depends
on (it reads `get(0)` and `get(size-1)`) is guaranteed by `applyHistoryWindow` being the only writer.

### Messaging (Spring Cloud Stream + Kafka)

Bindings live in `src/main/resources/async-messages.yml`, imported via `spring.config.import`:

- `userStatisticAnalysis-in-0` ← topic `att.user-statistic-ai-analysis`, group `att-ai-assistance`, DLQ
  `att.user-statistic-ai-analysis.dlq`.

**The binding name is structural, not a label:** `<function bean name>-in-<index>`. The `@Bean` method
`userStatisticAnalysis()` in `StatisticAnalysisConsumer` is what `userStatisticAnalysis-in-0` binds to, and
it is also named in `spring.cloud.function.definition`. Rename the method and the subscriber silently
detaches with no error — rename all three together.

There is **no producer and no `MessagingConstants` class**. A consumer's binding name only ever appears in
YAML; a Java constant for it would be dead code. Add one only if this service starts publishing.

The consumer is **not** gated by any property. It used to be (`att.ai.messaging.analysis.enabled`), but
that flag was never set in `helm/`, so it only ever served the test context — see "Testing conventions"
for what keeps tests broker-free now. Consuming is this service's whole purpose; whether an analysis
happens at all is controlled upstream, by the `report` flag on TimeTracking's statistics request.

### Failure handling, which differs by cause on purpose

| Cause                                      | Behaviour                                                                    | Reasoning                                                                                               |
|--------------------------------------------|------------------------------------------------------------------------------|---------------------------------------------------------------------------------------------------------|
| Malformed payload (no `userId`/`tenantId`) | `ConstraintViolationException` → straight to DLQ                             | Marked non-retryable in `async-messages.yml`: retrying a broken message cannot help                     |
| Blank or missing `reportEmail`             | warn, skip, acknowledge                                                      | Incomplete *data*, not a malformed message. Throwing would DLQ it and alert on something no retry fixes |
| SMTP down, auth failure, build failure     | `EmailDeliveryException` / `MailAuthenticationException` → retried, then DLQ | Transient. Deliberately **not** in the non-retryable list                                               |

That last row has an operational consequence worth knowing: a revoked or rotated Gmail app password
produces **no crash and no obvious error** — messages simply accumulate in
`att.user-statistic-ai-analysis.dlq`. If analyses stop arriving, check the DLQ first.

### Configuration and where secrets live

Config is split by **sensitivity**, not by whether a value is technically a password:

- **Protocol knobs** — `mail.port`, `smtpAuth`, `starttls`, `enabled`, `analysis.historyMonths` — live in
  `helm/aiassistance/values.yaml`, therefore in git, so ArgoCD renders them faithfully.
- **The mail account** — `MAIL_HOST`, `MAIL_FROM`, `MAIL_USERNAME`, `MAIL_PASSWORD` — comes from the
  `attendance-secrets` Secret, which is created by hand from `attendance-project-common/.env` and never
  enters git. **This repo is public**: an email address committed here is permanently in the history and
  harvestable, which is why even the non-secret host and sender are sourced from the Secret.
- `KAFKA_BOOTSTRAP_SERVERS` is the only value taken from the shared `attendance-config` ConfigMap.

All four Secret keys use `optional: true`, so the pod starts before an SMTP account exists.

**Every tunable this service owns is read through one class**, `AssistanceConfiguration`
(`att.ai.config`), injected where it is needed. It holds the `@Value` placeholders; no other class in
`src/main` has one. Add a new setting as a field there, not as a `@Value` on the class that needs it:

| Property                         | Field                                | Read by                    |
|----------------------------------|--------------------------------------|----------------------------|
| `att.ai.analysis.history-months` | `historyMonths` (default 6)          | `PromptComposer`           |
| `att.ai.notification.from`       | `notificationFrom` (no default)      | `EmailNotificationService` |
| `att.ai.notification.enabled`    | `notificationEnabled` (default true) | `EmailNotificationService` |

`att.ai.notification.from` deliberately has **no default**, so an unresolvable placeholder fails the
context at startup rather than mailing from null. Tests that need a different setting autowire the bean,
call the Lombok setter and put it back in a `finally` (see `EmailNotificationServiceTest`).

**On env var names, which is easy to get wrong:** two independent mechanisms resolve an env var for a
`@Value` placeholder, and `ATT_AI_ANALYSIS_HISTORYMONTHS` — what the chart sets — needs the second:

- `SystemEnvironmentPropertySource` maps separators only, so it matches `ATT_AI_ANALYSIS_HISTORY_MONTHS`.
- Boot's `ConfigurationPropertySourcesPropertySource`, attached by `SpringApplication.prepareEnvironment`,
  additionally applies **relaxed binding**, which drops the dash and so matches `ATT_AI_ANALYSIS_HISTORYMONTHS`.

Both spellings therefore work in the running app, and `AssistanceConfigurationTest` pins both. Note the
consequence for tests: a bare `new StandardEnvironment()` has no attached source, so the chart's spelling
resolves to `null` there — that is an artefact of the bare environment, **not** evidence that the chart is
misconfigured. Attach the source (or use `ApplicationContextRunner`, which does) before concluding
anything about production from such a test.

`management.health.mail.enabled=false` is set in `application.properties` **for a production reason**, not
just for tests: Boot's mail health contributor opens an SMTP connection on every `/actuator/health` call,
so an unreachable mail server would turn the readiness probe red and get the pod restart-looped — even
though the service is fine and undelivered mail is already handled by retry and the DLQ. Mail reachability
belongs in alerting on the DLQ, not in this pod's liveness.

Only `health` is exposed (`management.endpoints.web.exposure.include=health`). Adding `env` or
`configprops` would make SMTP configuration readable over HTTP from inside the cluster.

### Deployment

`helm/aiassistance/` mirrors `../Attendance-TimeTracking/helm/timetracking/` with four deliberate
differences: **no `service.yaml`** (nothing calls this service, and the probes reach the pod directly), no
database or JWT env, smaller resource requests, and `optional: true` on every Secret key.

ArgoCD manages it via `../attendance-project-common/argocd/aiassistance-application.yaml`. **ArgoCD does
not use Helm as a release manager** — it runs `helm template` and applies the result, so this service does
not appear in `helm list`, and `helm upgrade` / `--set` on it will be reverted by `selfHeal` within 120s.
A deploy is therefore a **git commit**:

```
edit src/ → push → CI builds, tests, pushes image 1.0.N, tags git 1.0.N   (ArgoCD does nothing)
bump appVersion in Chart.yaml → push → ArgoCD re-renders within 120s → rollout
```

`paths-ignore: helm/**` in the workflow is what makes that safe: a `Chart.yaml` bump does not trigger a
build, so bumping to N cannot mint N+1 and leave the manifest permanently behind.

Bump `version` in `Chart.yaml` when templates or values change; bump `appVersion` to deploy a new image.

**`.editorconfig` disables the IntelliJ formatter for `helm/**/templates/*.yaml`.** Do not remove it: a
YAML formatter rewrites `{{ .Values.x }}` as `{ { .Values.x } }`, which stops Helm recognising the
delimiter and leaves the chart unrenderable. This has already happened once and was committed.

## Testing conventions

Tests extend `BaseAiAssistanceTest` (`src/test/java/att/ai`), which boots the real context with
`@SpringBootTest(webEnvironment = NONE)` and mocks the one external dependency, `JavaMailSender`, with
`@MockitoBean`.

**Tests run with no broker and no SMTP.** Two things make that true, and both are needed:

1. `src/test/resources/application.properties` deliberately omits `spring.config.import`, so no stream
   bindings are declared and nothing names a destination.
2. The same file excludes Spring Cloud Stream's two core autoconfigurations:

   ```properties
   spring.autoconfigure.exclude=\
     org.springframework.cloud.stream.config.BindingServiceConfiguration,\
     org.springframework.cloud.stream.function.FunctionConfiguration
   ```

   Without these, `FunctionConfiguration` auto-detects the single `Consumer` bean as the function
   definition even with no `spring.cloud.function.definition` set, binds `userStatisticAnalysis-in-0` and
   lets the Kafka provisioner dial `localhost:9092` during context refresh. The other three stream
   autoconfigurations (`BindersHealthIndicator`, `BindingsEndpoint`, `ChannelsEndpoint`) are
   `@ConditionalOnBean` on beans these two declare, so they back off by themselves — no need to list them.
   Check with `./mvnw test 2>&1 | grep -iE "9092|AdminClient"`, which must print nothing.

The consumer is exercised by constructing `StatisticAnalysisConsumer` directly with the real beans and
handing it a `Message<>` — the same approach TimeTracking uses for its own consumer. **Do not add an
embedded Kafka, a test binder, or GreenMail**; extend this setup.

Two mechanics that cost time if unknown:

- A mocked `JavaMailSender` returns `null` from `createMimeMessage()`, which breaks `MimeMessageHelper`.
  The base class stubs it to hand out a fresh real `MimeMessage` per call.
- `MimeMessage` only writes its MIME headers during `saveChanges()`, which the real `Transport.send`
  performs and a mock does not. Call `saveChanges()` before asserting on `getContentType()`.

Reuse the base class fixtures — `entry(...)`, `sevenMonthHistory()`, `sixMonthWindow()`, `eventBuilder()`,
`fullEvent()`, `bodyOf(...)` and the two context ones — rather than re-deriving them. Assertions carry a
`"Reason: ..."` message explaining the expectation and each test has a `@DisplayName`; keep both.

The two context fixtures mirror the two states a context is ever in, so pick by what the test exercises:

- `contextOf(event)` — the message and nothing else, as the consumer builds it. For testing a step.
- `analysedContext()` — already carrying `sixMonthWindow()` and `ANALYSIS_TEXT`, as the notification step
  receives it. There is a three-argument overload for a different event, an empty history or other prose.

Anything reading `history` expects it newest-first, so do not pass `sevenMonthHistory()` where a window
belongs.

## Known gaps — don't assume these exist

- **No real LLM.** `StubLlmAnalysisClient` returns a hard-coded string. When adding a real client, gate the
  two on a property rather than deleting the stub: it is what keeps the pipeline testable without calling
  a paid API.
- **The analysed employee's name is not available.** The event carries the *requester's* name and email
  (`reportUserName`, `reportUserLastName`, `reportEmail`) but only the analysed user's `userId`, so the
  email subject reads "user 7". Putting a name there needs a new field on the event contract, set by
  TimeTracking.
- **`history-months` defaults to 6 while the producer sends 7** (target month + 6 before it), so the
  oldest entry is dropped. Set `analysis.historyMonths: "7"` in `helm/aiassistance/values.yaml` (or
  `ATT_AI_ANALYSIS_HISTORYMONTHS=7` locally) to use everything sent.
- **No scheduler and no second topic.** This service reacts only to messages; it never polls.
- **No DLQ consumer or alerting.** Nothing watches `att.user-statistic-ai-analysis.dlq`.

## General coding principles

- Avoid duplicating logic — take the window and the analysis text off `AnalysisContext`, and reuse the
  `BaseAiAssistanceTest` fixtures, instead of re-deriving the same window or the same event twice.
- Keep the layering: the consumer is a thin transport adapter, `att.ai.service` holds the analysis logic
  and the step sequence, `att.ai.notification` holds everything that knows about email, and `att.ai.llm`
  is the provider boundary. A change to the model provider must not reach outside `att.ai.llm`.
  `StatisticAnalysisService` depends on `att.ai.notification` because the pipeline ends in an email; that
  is the one deliberate edge from the analysis layer outward.
- Apply SOLID and the idioms already present rather than introducing new ones without reason.
