# Manual QA Checklist

Use this checklist in a real PyCharm Community 2025.3 IDE session. The plugin must keep Python files unchanged on disk; all English text is a disposable view layer.

## Fixture Setup

- Open the plugin sandbox project with `src/test/testData/qa/reader_fixture.py`.
- Set the py-to-english profile to Intent Summary and preset to Hints, Outline, then Reader.
- Configure OpenAI-compatible provider with `http://localhost:11434/v1` and model `gpt-oss:20b` for local Ollama validation.

## Rendering Polish

- Confirm outline rows, Code Vision, and block inlay fallback all read from the same summaries.
- Confirm pending summaries show the placeholder and later update without source edits.
- Confirm Polyglot Lens wording says "Closest analogy" and includes a caveat when callouts are shown.
- Confirm no text overlaps editor content in Hints, Outline, or Reader presets.
- Confirm the Code preset hides summary overlays.

## Intent Summary Pipeline

- Open `reader_fixture.py` and wait for function and block summaries to appear.
- Edit only whitespace or comments and confirm summaries are reused.
- Change the signature of one function and confirm only that block shows pending text before refreshing.
- Use Regenerate on the caret block and confirm a fresh provider request is made.
- Confirm stale badges appear in the outline while regeneration is pending.

## Provider And Secrets

- Save an API key in settings and restart the IDE; confirm the key is not written to project files.
- Clear the API key and test local Ollama; confirm the local OpenAI-compatible path still works.
- Use Test connection against local Ollama and confirm success and failure states are readable.
- Confirm PasswordSafe prompts do not appear on the EDT during editor scrolling or folding.

## Large File Feel

- Open `large_fixture.py`.
- Scroll quickly while summaries are pending; confirm typing and caret movement remain responsive.
- Make repeated edits during an in-flight request and confirm old summaries do not appear after the edit.
- Confirm summaries are batched enough to avoid visible flicker.

## Marketplace Verifier

- Run the IntelliJ Platform verifier for the built plugin.
- Confirm PyCharm Community 2025.3 compatibility.
- Confirm no dependency on PyCharm Professional-only APIs.
