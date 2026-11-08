# Compact UI result (FIN-474)

The `Reports` job publishes `e2e-result-attempt-<GITHUB_RUN_ATTEMPT>` alongside the existing `e2e-reports`
and seven suite artifacts. Download the compact artifact to inspect the complete test inventory, canonical
results, provenance and offline Qase mapping. It contains no screenshots, Maestro archives, JaCoCo data or HTML
coverage copies. All those remain in the existing raw artifacts with their existing 14-day retention. The compact
artifact also has 14-day retention; artifact links can expire and are not permanent storage.

`scripts/e2e-shards.py gather` still owns the canonical test verdict. `scripts/e2e-result.py` copies its files and
indexes their hashes; it does not reaggregate results or decide PASS. An absent/invalid compact artifact means
**unknown**, never PASS. A failed gather can produce a useful compact result with `execution-status.json.passed=false`.
Upstream job failures are independent evidence: a canonical PASS does not override a failed native/component/shard job.
The final Reports gate and Qase delivery policy remain unchanged. No UI runs are added on PRs.

## Schema 1

The upload root is `build/e2e-result/`. Paths inside the index are relative to that root, using `/` separators.
The required files are byte-for-byte copies from the same checkout/gather:

| File | Meaning |
| --- | --- |
| `result.json` | Schema/version, reporting context, file checksums, suite evidence paths and raw artifact links |
| `maestro-report.xml`, `native-report.xml`, `jvm-report.xml` | Three canonical JUnit files, including NOT RUN and integrity failures |
| `execution-status.json` | Existing schema1 gather verdict, suite counters, first failure, source/APK identity and deadline flags |
| `expected-tests.json` | Existing source-derived component inventory; no second test registry |
| `qase-cases.json` | Existing `scripts/qase-cases.json` from the same checkout; many components may map to one Qase case |

Optional byte-for-byte evidence files are indexed under
`evidence/<e2e-shard-N|e2e-native|e2e-components>/{execution,preparation,sdk-cache}.json`.
The index records `null` when a source file is absent. Absence does not create successful execution evidence.
Malformed or wrong-source original receipts are retained for diagnosis; trust the gather integrity errors rather
than the contents of an unverified receipt. Symlink inputs are rejected, not followed.

`result.json` has the following fields (the example omits most `files` entries and suites for space):

```json
{
  "schema": 1,
  "metadata": {
    "repository": "fin-assist/android",
    "server_url": "https://github.com",
    "source_sha": "25c878053e095bae53f7a5d921065a3c641827e0",
    "run_id": "42506253547",
    "run_attempt": "1",
    "event_name": "workflow_dispatch",
    "ref": null,
    "ref_name": "release/0.2.20",
    "workflow": "UI tests",
    "workflow_ref": null,
    "workflow_sha": null,
    "job_results": {"shard": "success", "native": "success", "components": "success"}
  },
  "files": {
    "expected-tests.json": {"sha256": "<64 lowercase hex digits>", "bytes": 1234}
  },
  "suites": [{
    "kind": "maestro",
    "index": 1,
    "budget_seconds": 2400,
    "maestro_archive_cache": null,
    "evidence": {
      "execution": "evidence/e2e-shard-1/execution.json",
      "preparation": "evidence/e2e-shard-1/preparation.json",
      "sdk_cache": "evidence/e2e-shard-1/sdk-cache.json"
    },
    "artifact": {
      "name": "e2e-shard-1",
      "url": "https://github.com/fin-assist/android/actions/runs/42506253547/attempts/1#artifacts"
    }
  }],
  "delivery": {"path": null, "reason": "manual_not_published"},
  "raw_reports": {
    "name": "e2e-reports",
    "url": "https://github.com/fin-assist/android/actions/runs/42506253547/attempts/1#artifacts"
  }
}
```

All metadata identity values are strings or `null`. Repository, HTTPS server origin, full source SHA, positive
decimal `run_id` and `run_attempt` are required; optional facts remain `null` when unavailable. `job_results`
contains `shard`, `native`, `components`, each `success|failure|cancelled|skipped|null`. It records the actual
`needs` results, without synthesizing a combined verdict. Never treat null job metadata as success.

`files` includes every payload file, including optional evidence and delivery; `result.json` does not hash itself.
Each entry has SHA256 hex and an integer byte length. Validate schema, relative paths, hashes and the independent
GitHub run identity before consuming a downloaded bundle. Hashes detect corruption and mixing; they do not
authenticate an artifact independently of its run. The index does not execute any file or fetch any URL.

Suite identity is `(kind,index)`: Maestro uses 1-based shard indices; `native` and `jvm` use index0. `evidence`
has exactly `execution`, `preparation`, `sdk_cache` paths or null. `budget_seconds` is a positive integer or null:
in CI it is passed from the same workflow variables used by the actual process coordinator (2400/600/900 today).
Offline reconstruction leaves it unknown unless explicitly supplied from independently verified configuration.
The budget is the test process limit, not the enclosing job or emulator/setup limit. Maestro archive cache hit
telemetry is not persisted by the existing installer, so `maestro_archive_cache` is explicitly null.

Original execution receipts carry event timestamps, runner/Maestro identity, APK SHA256, SDK package revisions,
Java launcher and Gradle daemon configuration hash when available. Original preparation telemetry carries boot
mode, cache classification and named phase marks; SDK telemetry carries package cache classification, revisions,
runner identity and restore/install/save timings. These are measured observations, not invented durations.
`execution-status.json.suites[].seconds` remains JUnit test time, distinct from event/mark process elapsed time.
Repeated phase events and first-failure retention must not be collapsed into an assumed total or hidden retry.
Unknown configuration stays unknown; `metadata.workflow_sha` identifies the workflow configuration revision.

The reporting attempt can include unchanged suites from an earlier failed-jobs attempt, as existing gather allows
receipt attempts `<=` the reporting attempt for the same source/run. Keep original receipt attempts. Raw artifact
URLs point to the reporting attempt's artifact listing with a separate exact artifact name; they are navigation
links, not signed download URLs or proof that an artifact still exists. Do not overwrite earlier compact attempts.

`delivery.path` refers to the original `qase-delivery.json` when present; `reason=recorded` does **not** mean
published. Read that file's existing `result` (`pending|empty|dry_run|not_configured|failed|published`). Without a
delivery file, `reason=manual_not_published` identifies a manual event, and `not_recorded` identifies other/unknown
events. Manual runs do not publish to Qase. A Qase outage does not change the canonical test verdict.

## Offline use and validation

The packager uses only the Python standard library. It does not contact GitHub/Qase, run Gradle, or start a device.
After running the existing gather against downloaded raw artifacts, package its canonical output:

```sh
python3 scripts/e2e-result.py --canonical build/e2e \
  --shards-dir build/e2e-shards --native-dir build/e2e-native --jvm-dir build/e2e-components \
  --metadata /path/to/verified-run-metadata.json --out build/e2e-result
```

The metadata file is the `metadata` object shown above, **not** an arbitrary GitHub API response. For historical
artifacts, verify it against saved/live API run metadata, not receipts alone. Do not claim a newly published CI
artifact when reconstructing one locally. Missing required canonical files or invalid metadata fail packaging.
The output is replaced only when empty or recognized as a previous complete compact bundle; unrelated directories
are refused. Invalid budget values, invalid metadata and failed reconstruction remove an owned prior bundle so
it cannot be uploaded as a stale PASS. `--help` and CLI syntax errors (unknown options or missing option values)
leave output untouched. The workflow uploads the compact artifact only after successful packaging, so a failed
invocation cannot publish any leftover output; full raw Reports still upload on failure.

For offline Qase mapping, read all three canonical XML files with the existing `qase-report.py.parse_report`
(prefix `jvm:` for JVM), then call its existing `map_results` with the bundled `qase-cases.json["cases"]`.
Use the reporter from the bundle's source SHA. This reproduces many-to-one worst-result/missing-component handling
without a Qase token or network request; it does not publish delivery. Packaging regressions compare these mapped
results before and after copying, including missing, skipped, duplicate, malformed, wrong-source and deadline cases.

Current-run size is an observation: aim below 1MiB for the current inventory, but never truncate canonical diagnostics
to fit it. The full raw evidence is the place for large traces and screenshots. Script tests and offline replay
validate packaging; they do not constitute a new UI acceptance run.
