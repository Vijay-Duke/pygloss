# PyGloss Privacy Policy

Effective date: 10 July 2026

PyGloss is a PyCharm plugin that adds plain-English explanations to Python source code. This policy describes the data handled by the plugin.

## Local-only features

PyGloss's deterministic Polyglot Lens runs locally. It does not send source code or usage data over the network.

PyGloss does not collect telemetry, analytics, or crash reports.

## Optional AI features

Intent Summary and AI line translation are optional features. They are used only after a user configures an AI provider and enables the relevant feature.

When an optional AI feature runs, PyGloss sends the minimum prompt needed to the provider configured by the user. This can include:

- a bounded Python source snippet or selected Python statements;
- structural facts about the snippet, such as its kind or range;
- the user's optional project domain description and explanation-style preference; and
- the configured model identifier.

PyGloss supports OpenAI-compatible endpoints and Anthropic endpoints. The configured provider receives this data under that provider's own terms and privacy policy. Users are responsible for choosing a provider suitable for their code and organization.

## Credentials and local storage

API keys are stored with the IntelliJ Platform PasswordSafe. They are not written to project files or PyGloss settings XML.

Generated AI summaries and line translations may be cached in the IDE system directory, outside the project and version-control tree, to avoid repeated provider requests. These cached responses can contain generated text derived from the submitted code. Removing the relevant PyGloss cache or uninstalling the plugin removes the local cache.

## Changes and contact

This policy may change when PyGloss changes. The current version is published with the source code.

For questions or privacy requests, please open an issue at <https://github.com/Vijay-Duke/pygloss/issues>.
