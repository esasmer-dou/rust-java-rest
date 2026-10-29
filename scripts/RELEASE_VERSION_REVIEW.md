# Release Version Review

Dot-source `guard-release-version-rewrites.ps1` in a future documentation version
rewriter. `Get-GuardedReleaseVersionUpdate -Path <file> -Text <text>
-Replacements <hashtable>` returns `Text` and `ManualReview`. Apply only `Text`;
collect `ManualReview` and pass it to `Write-ReleaseVersionReviewReport -Review
<items> -ReportPath <json>`. Copy the helper, scanner and selfcheck when running in
an isolated checkout.

The helper preserves publication and source-checkout paragraphs, including wrapped
English/Turkish claims, and reports their original text and proposed replacement.
Review findings are nonblocking. Only a human with artifact/workflow evidence may
change those claims. Literal version replacements elsewhere run once, without
cascading replacements; POM-only version alignment remains supported.

`review-release-version-claims.ps1` scans tracked Markdown without modifying it;
release, platform-release and publish-verified workflows upload its nonblocking
JSON report. It makes no automatic publication decision or cross-library version
proposal. Existing package/provenance gates retain their fail-closed behavior.

No checked-in REST release-version rewriter currently calls this helper. Historical
workspace `.tmp/release-20261029/fix-docs.ps1` is not a supported rewriter and must
not be rerun for a new release. It remains unchanged as historical evidence.

The synthetic, dependency-free selfcheck is `pwsh -NoProfile -File
scripts/test-guard-release-version-rewrites.ps1`. For this change it must run only
on EmaTech; this is a project testing restriction, not a product usage restriction.
