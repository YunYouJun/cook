# Dependency patches

## deepmerge 4.3.1

[GHSA-gcqx-w48m-w2h4](https://github.com/advisories/GHSA-gcqx-w48m-w2h4) affects the merge of a `__proto__` key, including a source merged into a null-prototype target and keys copied from the target. The patch rejects that key in both loops in the source and published CommonJS/UMD bundles. Other merge behavior is preserved and covered by `test/deepmergeSecurity.test.ts`, which loads the dependency through `unbuild` → `@rollup/plugin-node-resolve`.

As of 2026-11-11, the official registry still publishes 4.3.1 as latest. Although npm audit suggests 4.3.2, that version is unavailable and the [GitHub advisory](https://github.com/advisories/GHSA-gcqx-w48m-w2h4) lists no patched version. Keep the alert visible; a local patch does not change the upstream version. Replace this patch with a verified upstream fix when one is published, then rerun the regression tests and production builds. Track [upstream issue #273](https://github.com/TehShrike/deepmerge/issues/273).
