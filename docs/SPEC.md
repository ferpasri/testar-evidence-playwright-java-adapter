# Playwright Java adapter specification

The adapter translates explicit script actions and test-oracle evaluations into records accepted by the Java state-model library. Each session creates one run and a portable evidence directory.

For a walkthrough of the classes and script integration, see [How it works](how-it-works.md).

## Components

| Component | Responsibility |
| --- | --- |
| `PlaywrightEvidence` | Own the evidence writer, action lifecycle, ordered observations, and run ending. |
| `PlaywrightChecks` | Run an assertion once through its existing retry mechanism and report its final outcome and reading. |
| `CaptureOptions` | Select capture bounds, computed styles, excluded attributes, and screenshots. |
| `DomCapture` / `BrowserSnapshot` | Read browser data and map the captured hierarchy to generic widgets. |

The caller owns the Playwright page and browser. Calls use the same thread as the page. The state-model library validates events, saves JSONL, and manages screenshot artifacts.

## Captured data

The adapter captures the document root, DOM elements, and elements in open shadow roots. Widget IDs derive from structural paths and are interpreted within their observation. The scope is filtered: frame documents are excluded and closed shadow roots are not observable.

State properties retain the full `playwright.document.url` and actual `playwright.document.title`, with equal-value aliases `ui.url` and `ui.title`.

Widgets retain DOM attributes under `playwright.attribute.<name>`, raw text content and selected live DOM values under `playwright.dom.*`, bounds, and selected computed styles under `playwright.style.<name>`. An HTML value attribute and the live input value retain separate keys. A widget title attribute, visible text, and accessible-label attribute retain their own meanings.

Common aliases include raw `ui.text`, explicit-attribute `ui.role` and `ui.alt`, live `ui.value`/`ui.checked`/`ui.selected`, and `ui.bounds`. `evidence.property_aliases` identifies the original property within the same record.

`evidence.capture` describes the area, widget/property selection, limits, and known truncation. `evidence.omitted_properties` records whole-property exclusions, limits, redactions, unsupported values, and unavailable readings when known. Omission metadata retains up to 32 entries per widget; counts describe additional omissions. A missing captured property alone does not establish application-level absence.

| Capture option | Default | Maximum |
| --- | ---: | ---: |
| Widgets, including document root | 2,000 | 5,000 |
| Characters per widget string value | 8,192 | 65,536 |
| Selected widget-property character budget | 4,194,304 | 8,388,608 |
| Attributes considered per widget | 256 | 1,024 |

The character budget counts selected property names and JSON-serialized values, including aliases. Structural paths, capture descriptions, and bounded omission metadata are stored separately. Oversized string values are omitted whole.

Default computed styles are display, visibility, font size, font family, color, and background color. Callers can select up to 64 styles and exclude up to 256 attribute names. Password input values and their value attributes are redacted explicitly. Screenshots are viewport PNGs and can be disabled through the capture options.

## Observations and execution links

A snapshot is captured before an action when needed, after an assertion has finished, and for an explicit `observe` call. Capture and screenshot requests are successive browser operations. The test controls application readiness; the capture describes the observed moment.

The recorder keeps the latest captured observation pending until the next action is known or the session finishes. It then records that observation with the next scripted action as its candidate, followed by any queued evaluation. This allows an action's result observation to become the next action's source without adding an artificial execution.

Candidate scope is unknown: the list describes the next scripted action and does not enumerate alternative interactions. An explicit observation or terminal visit can have an empty list.

Actions retain their kind, description, locator description, actual parameters, and scripted origin. A locator binds through its captured DOM path; a missing, ambiguous, excluded, or frame target keeps the locator description and an unspecified widget binding. Custom macros submitted through `perform` are one outer action.

Selection is recorded before the action callback. A normal return records accepted submission; an action exception records rejected submission with its error and is rethrown. Acceptance describes the execution wrapper's outcome. A business response such as Denied appears in the resulting observation and is evaluated separately.

The next captured observation completes an accepted execution. Consecutive assertions produce distinct observations without inventing action links. Two captures with the same content remain separate visits.

## Test-oracle evaluations

`hasText`, `hasValue`, and `hasAttribute` use Playwright's native retrying assertions. Rule parameters identify the comparison and timeout. For example, `hasText` keeps Playwright's whitespace handling while the reported text reading retains the raw final value.

Each helper records one final evaluation. Passed means the assertion returned normally; failed means it threw an assertion failure; error means its execution threw another runtime exception. The original failure is rethrown after reporting. Persistence failures are propagated or attached to an existing assertion failure.

The reading is obtained after the assertion, followed by the state capture. Readings and capture are separate observations of the browser in time and can differ if the application changes between calls. A failed reading becomes unknown with its error details. An absent attribute reading comes from the DOM attribute API returning no value.

`checks().evaluate` accepts an existing assertion, rule/version/parameters, expected-value presence, property name, locator, and reading supplier. Supplied JSON null expectations remain distinct from omitted expectations. Detailed targets identify the captured widget and literal property key; an unavailable widget target retains an observation-level result with the requested target and explanation.

Password redaction concerns capture and fill parameters. Custom oracle authors choose their supplied expected values and readings.

## Ending and persistence

`finish(reason)` flushes the final observation, outstanding result link, and evaluations, then saves the run ending. It is idempotent. Closing an unfinished session also flushes its pending records and records closed, action_failed, or check_failed according to the known session outcome. The caller can provide an explicit ending reason.

New model creation preserves existing evidence by refusing an existing manifest or log. Successfully appended events remain readable if a later operation fails. The caller keeps the session open until its scripted operations and assertions finish.

Abstraction and cross-source matching are performed by consumers of the concrete evidence.
