# PyGloss

PyGloss is a PyCharm plugin that adds a read-only explanation layer over Python source. It does not rewrite files. All explanations appear directly inside the editor as folds and inlays, so the source and its descriptive view stay together.

## What it does

- **Polyglot Lens** works offline and explains Python concepts using a selected language such as JavaScript, Java, Go, or C#.
- **Intent Summary** can ask an OpenAI-compatible or Anthropic endpoint for short block summaries.
- **Code, Hints, Outline, and Reader presets** control how much English is shown. `Code` is closest to ordinary source; `Reader` replaces supported statements with English fold labels.
- **Project summary settings** add domain context, choose plain/analogy/technical wording, and optionally enable AI line translation.
- Generated summaries and line translations are cached outside the project source tree.

The deterministic Reader translation is intentionally conservative. If a Python construct is not understood safely, the original source stays visible.

## Run it in a PyCharm sandbox

Requirements: JDK 21. The Gradle wrapper downloads the configured PyCharm platform on the first run, which can take several minutes.

```bash
./gradlew test
./gradlew runIde
```

In the sandbox:

1. Open [`examples/reader_demo.py`](examples/reader_demo.py).
2. Choose **Tools → PyGloss View → Reader** to test the fully offline path.
3. Click a folded line to reveal its original Python.

The first launch may reopen the last project used by the sandbox. Use **File → Open** to open this repository or the demo file.

## Settings

Open **Settings → Tools → PyGloss**.

- **Provider:** OpenAI-compatible (Ollama, OpenAI, Groq, Together, LM Studio, vLLM, and similar endpoints) or Anthropic.
- **Base URL:** a complete `http://` or `https://` endpoint.
- **Model:** the provider's model identifier.
- **API key:** stored through the IDE password store, not in the project or plugin state XML. It may be blank for a local endpoint such as Ollama.
- **Reader profile, target language, and preset:** defaults for the in-editor explanation layer.

The **Summaries** sub-page is project-specific:

- **What is this project about?** adds domain context to AI prompts.
- **Explanation style** chooses plain language, plain language with analogies, or technical wording.
- **Translate code lines with AI** is experimental, disabled by default, uses more tokens, and only affects Reader view.

Use **Test connection** before enabling Intent Summary. Polyglot mode never needs an API key or network request.

## Useful commands

```bash
# Fresh test execution
./gradlew test --rerun-tasks

# Build the installable plugin ZIP
./gradlew buildPlugin

# Run JetBrains compatibility verification
./gradlew verifyPlugin
```

Build output is written under `build/distributions/`.

## Installation

Until the first Marketplace release is approved, build the plugin with `./gradlew buildPlugin`, then install the ZIP from **Settings → Plugins → ⚙ → Install Plugin from Disk**.

After Marketplace approval, search for **PyGloss** directly in PyCharm's Plugins settings.

## Project structure

- `application/` coordinates background model, provider, status, and refresh workflows.
- `engine/` detects Python PSI blocks and builds the cached English model.
- `render/` provides editor folds, inlays, refresh behavior, and status.
- `llm/` contains provider adapters, prompts, and the provider-independent summary pipeline.
- `cache/` owns in-memory and disk cache behavior.
- `settings/` stores non-secret application and project preferences.
- `src/test/` contains deterministic, adapter, cache, rendering, and plugin-load tests.

## Privacy and cost

Polyglot mode is local and deterministic. Intent Summary and AI line translation send bounded source context or selected statements to the configured provider. AI line translation is opt-in because it can make many more provider calls than block summaries.

PyGloss does not collect telemetry. Provider API keys are stored through the IntelliJ Platform password store and are never written to the project or plugin settings XML.
