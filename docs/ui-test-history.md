# UI run history (FIN-475)

`scripts/e2e-history.py` reads compact UI evidence and independent GitHub run metadata. It prints JSON or
Markdown without dispatching, retrying, modifying or merging anything. Python standard library only;
`gh` authentication is needed only for online GET requests. The producer is tracked in
[FIN-474](https://finassist.youtrack.cloud/issue/FIN-474); this consumer does not import its implementation.

```sh
# Last 20 main/push runs, all attempts of each run (at most 100 attempts per run).
python3 scripts/e2e-history.py --repo fin-assist/android --branch main --event push --limit 20
# An explicit run; first and intermediate failures remain visible after a successful rerun.
python3 scripts/e2e-history.py --run 42506253547 --format json > /tmp/ui-history.json
```

Online selection is bounded to 100 runs. Artifact pagination stops with a visible error after four pages;
job metadata is bounded to 100 jobs per attempt. Each `gh api --method GET` call has a 20-second deadline,
with a 180-second total budget (`--api-budget`, maximum 300). A deadline terminates the entire owned process
group, including children surviving the parent. Failed jobs/API requests retain the known attempt and its
independent workflow snapshot; an unavailable latest attempt cannot disappear from the report. API errors contain fixed diagnostic codes, never stderr,
response bodies, credentials or temporary artifact URLs. Download endpoints are constructed from validated
repository and numeric artifact IDs; artifact-supplied download URLs are ignored. Exhausting a budget leaves
selected runs/attempts visible as unavailable, rather than silently dropping them.

## Saved input and reproducibility

```sh
python3 scripts/e2e-history.py --metadata /tmp/github-history.json \
  --bundle 42:1=/tmp/e2e-result-attempt-1.zip \
  --bundle 42:2=/tmp/e2e-result-attempt-2.zip --format json
```

The offline metadata envelope has this shape:

```json
{
  "repository": "fin-assist/android",
  "runs": [
    {
      "id": 42,
      "run_attempt": 1,
      "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
      "repository": {"full_name": "fin-assist/android"},
      "head_repository": {"full_name": "fin-assist/android"},
      "status": "completed",
      "conclusion": "failure",
      "event": "push",
      "path": ".github/workflows/e2e.yml",
      "head_branch": "main",
      "name": "UI tests",
      "jobs": [{"status": "completed", "conclusion": "failure"}]
    }
  ],
  "artifacts": [
    {
      "id": 901,
      "name": "e2e-result-attempt-1",
      "expired": false,
      "digest": "sha256:<64 lowercase hex characters from the artifact API>",
      "workflow_run": {
        "id": 42,
        "head_sha": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
      }
    }
  ]
}
```

Keep the independent raw GitHub API snapshots, not metadata copied out of `result.json`. Use the run or
attempt API response for each attempt; optionally add its jobs API `jobs` array. Online mode always fetches
jobs. If jobs were not saved offline, their conclusions are unavailable; the run conclusion and compact
`job_results` still veto a complete pass. Artifact records come from the run artifacts API. The whole ZIP SHA-256 must match the provider `digest`; without it the evidence stays unverified.
Directory input is accepted only with `--local-reconstruction`, because a directory cannot prove the
original provider ZIP digest. A ZIP alone
cannot establish which run produced it. Offline validation trusts the supplied API snapshots; hashes are
integrity checks, not signatures proving that a local snapshot is authentic.

`--run` restricts the denominator; otherwise every unique run in metadata is selected. Identical duplicate
run/attempt snapshots and identical bundle inputs do not double-count. Conflicting inputs are invalid;
a successful copy never wins over a conflicting failure. Every attempt from 1 to the latest selected run
attempt is displayed. Missing intermediate metadata gets an explicit error. Absent compact evidence is
`legacy_or_missing`: absence alone cannot distinguish an older workflow from failed packaging or expired
storage. Missing, cancelled and unavailable runs remain in the selected-run denominator.

For a historical run reconstructed locally from its original raw artifacts, use the explicit offline flag:

```sh
python3 scripts/e2e-history.py --metadata /tmp/github-history.json \
  --bundle 42:1=/tmp/reconstructed-result --local-reconstruction --format json
```

This still checks the independent run ID, source and attempt. It labels `artifact_provenance` as
`local_reconstruction` and `evidence_status` as `reconstructed`. Canonical observations stay visible,
but `full_pass` is false and these rows have a separate `reconstructed_application_pass` counter.
They never enter the provider-verified pass counter. This is historical observation replay, not publication
or acceptance of the new producer workflow. Reconstruction is permitted only when the attempt has no
matching provider record. If a record exists, its source and every ZIP digest are checked even with this
flag; failed provider validation cannot fall back to reconstruction. Ordinary mode requires provider metadata.

Output is deterministic for identical saved inputs: stable sorting, no collection timestamp or local path
in the report. JSON contains the full retained diagnostics and configuration fingerprints; Markdown gives
an operational overview followed by suite details. Links are built from validated GitHub identity and Qase
project/run IDs; artifact text is escaped. Untrusted stdout/stderr and raw test stack traces are not rendered.

## Compact schema 1

The artifact is named `e2e-result-attempt-N`. Its root `result.json` contains:

- `schema: 1`, `metadata` with repository/server/source/run/attempt/event/ref/workflow identity and
  `job_results: {shard, native, components}`;
- `files`, mapping each payload-relative path to its SHA-256 and byte count (the index does not hash itself);
- `suites` with `kind` (`maestro`, `native`, `jvm`), `index`, `budget_seconds` (or null),
  `maestro_archive_cache` (currently null), `evidence` paths, and raw artifact name/link;
- `delivery` with `path` (`qase-delivery.json` or null) and `reason` (`recorded`,
  `manual_not_published`, `not_recorded`); `raw_reports` names the separate full reports artifact.

The six mandatory payloads are `maestro-report.xml`, `native-report.xml`, `jvm-report.xml`,
`execution-status.json`, `expected-tests.json` and `qase-cases.json`. Optional originals are
`qase-delivery.json` and `evidence/e2e-shard-N|e2e-native|e2e-components/` with `execution.json`,
`preparation.json`, `sdk-cache.json`. Payloads are the producer's byte-for-byte originals.

The consumer checks schemas, hashes, complete file inventory, independent source/run/attempt identity,
artifact provenance, receipt identity and canonical test inventory. Earlier successful job receipts may
have attempt numbers lower than the collection attempt. It uses the existing Qase parser and mapping;
there is no second test registry. Unknown, duplicate or missing canonical component keys cannot become
passes. `execution-status.json` is the strict gather verdict; original receipt categories remain evidence,
while gather may classify integrity errors as `reporting`. The consumer checks consistency without
replacing that verdict. A valid failed execution can contain genuine passing/failing results from before
setup or deadline failure.

ZIPs are read without extraction: only the named payload paths, at most 256 entries, 8 MiB per file,
64 MiB total uncompressed and 32 MiB compressed. Traversal paths, duplicate names, symlinks, encrypted
entries, duplicate JSON members, nonfinite numbers and XML DTD/entities are rejected. Directory input is
also restricted to regular, bounded payload files and cannot follow symlinks. Malformed evidence produces
a visible invalid row, never a current pass. A missing provider digest is explicitly unverified.

## Reading the result

`application_passed` preserves the artifact-reported canonical gather result. It does not establish trusted
UI acceptance. Independent API `head_repository.full_name` must match the selected repository, `path` must equal the selected `.github/workflows/e2e.yml` (or explicit
`--workflow` basename), and API `event` must be `push` or `workflow_dispatch`. A PR event or another
workflow is `control_plane: ineligible`; missing routing metadata is `unknown`. Both keep observations
visible but cannot qualify for `full_pass` or a comparable cohort. Artifact metadata cannot choose this
policy, including when its internal hashes and provider ZIP digest are valid.

`full_pass` requires provider-verified evidence and additionally needs a completed successful
GitHub workflow, successful compact shard/native/components job results and no independently recorded
failing or unfinished jobs. A workflow failure with passing XML remains a workflow failure. Qase delivery
is separate: `published` needs the matching mapped case count, zero unmapped cases and a positive Qase
run ID; no token, a dry run, manual non-publication or publication failure is not delivery success.

The first-attempt operational summary divides provider-verified complete passes by **all selected runs** and lists
workflow/evidence states. Reconstructed observations have a separate counter. It does not estimate a flake rate. Reruns appear alongside their first and
intermediate attempts; a later pass does not erase an earlier assertion, setup/device, budget, reporting or
cancelled result. Per-suite canonical diagnostics, failure/missing/skipped counts, primary cause, phase and
an additional deadline marker remain visible. An assertion failure followed by deadline expiry keeps both.

Comparable cohorts require exactly matching source SHA, canonical inventory/Qase fingerprint and known
configuration: workflow SHA/ref/event, per-suite budget, runner OS/architecture/image version, Java/Gradle,
Maestro archive/version where applicable, boot mode and SDK package revisions/source-property hashes.
Different signed APK hashes are recorded but do not split a cohort: independent runners can sign the same
source differently. Unknown configuration makes a run incomparable, while its test result stays visible.
AVD arguments are not archived individually in schema 1; exact workflow identity and recorded boot mode
are the available configuration evidence. Do not interpret these groups as proof of unrecorded environment
identity.

Process duration is the original `tests-started` → `tests-finished` wall interval, never the sum of JUnit
durations. Remaining budget is recorded budget minus that duration and can be negative. Unknown/empty Java, missing receipt SDK revisions or missing AVD cache policy also make cohorts
incomparable. Missing marks,
JVM timing absent from older receipts, missing budgets and cache outcomes stay unknown. Cache classes come
only from the SDK sidecar (`hit-current`, `hit-updated`, `miss`, `preinstalled`); a fast run is not evidence
of a cache hit. Maestro archive caching is unknown in schema 1. There are no p95, SLO or statistical claims
from this collector, including for single-sample cohorts.

Local regression command (hard deadline, including child processes):

```sh
python3 scripts/e2e-cache.py run-command --timeout 90 -- \
  python3 -m unittest discover -s scripts -p test_e2e_history.py
```

The existing coordinator bounds the process group. These tests use synthetic compact evidence built
through the existing gatherer and local fake `gh` processes. They do not start an
emulator, Gradle or a UI workflow.
