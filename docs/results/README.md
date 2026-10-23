# Local measurement artifacts

Generated measurements, transcripts, quota receipts and test XML remain on the
development machine and are excluded from the GitHub source export. They are
not deleted. Historical Markdown reports describe the measurements and their
limits; links to raw local result files are available only in the local
workspace/delivery folder.

Reproduce offline checks with `python -m pytest tests`; Android checks use
`python scripts/build_android.py testDebugUnitTest lintDebug`. Real Gemini
benchmarks require explicit approval and the owner's configured credentials;
they must not run automatically in CI or during code review.

Sanitize any result before deliberately adding it to the repository. Never
publish API keys, authentication headers, personal video content or local
credential/configuration files.
