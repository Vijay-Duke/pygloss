# Python PSI cheat-sheet (verified against the bundled PyCharm platform)

Ground-truth API for `com.jetbrains.python.psi.*` in this project's platform build.
Derived via `javap` on `intellij.python.psi.jar`. **Use these exact forms — do not
guess method names; several obvious-sounding ones do NOT exist here.**

## Things that DON'T exist (workers keep inventing these)
- ❌ `PyAwaitExpression` — no such class. Await is a `PyPrefixExpression`.
- ❌ `PyNamedAssignment` — walrus `:=` is `PyAssignmentExpression`.
- ❌ `PyNamedParameter.isPositionalContainer` / `.isKeywordContainer` — not accessible.
- ❌ `PyComprehensionElement.isSet` / `.isDict` / `.isGenerator` — use subtypes.
- ❌ `PyWithStatement.isAsync` / `.asynchronous` — not present (functions DO have `isAsync`).
- ❌ `PyImportElement.importedName` / `.asName()` — wrong names.
- ❌ `PyWhileStatement.condition` — condition lives on the `whilePart`.
- ❌ `org.jetbrains.intellij.testFramework.TestIndexManager` — does not exist.

## Correct forms
| Need | Correct API |
|---|---|
| async function | `PyFunction.isAsync` (exists) |
| async `with` | leaf check: `withStmt.firstChild?.text == "async"` |
| `await` expr | `PsiTreeUtil.findChildrenOfType(scope, PyPrefixExpression::class.java).any { it.firstChild?.text == "await" }` |
| walrus `:=` | `PyAssignmentExpression` |
| comprehension kind | `when (comp) { is PySetCompExpression -> ...; is PyDictCompExpression -> ...; is PyGeneratorExpression -> ...; else -> list }` |
| `*args` / `**kwargs` | leading-star on param text: `p.text.startsWith("**")` (kwargs) else `p.text.startsWith("*")` (args) |
| `self` param | `PyNamedParameter.isSelf` (exists) |
| if condition | `ifStmt.ifPart?.condition` |
| while condition | `whileStmt.whilePart?.condition` |
| for target/source | `forStmt.forPart?.target` / `.source` |
| import name | `importElement.importReferenceExpression?.text` |
| import alias | `importElement.asNameElement?.name` |
| superclass names | `cls.superClassExpressions?.mapNotNull { (it as? PyReferenceExpression)?.asQualifiedName()?.toString() }` (returns `QualifiedName`, call `.toString()`) |
| decorators present | `element.decoratorList != null` |
| statement body | `PyFunction.statementList`, `PyWithStatement.statementList`, `part.statementList` |

## Tests (IntelliJ platform)
- Extend **`com.intellij.testFramework.fixtures.BasePlatformTestCase`** — it provides
  `myFixture` (a `CodeInsightTestFixture`) and manages setUp/tearDown. Do NOT build fixtures manually.
- Test-data dir: `override fun getTestDataPath(): String = "src/test/testData"`.
- Do NOT redeclare `project` — `BasePlatformTestCase` already exposes it (redeclaring collides with `getProject()`).
- Load code: `myFixture.configureByFile("u2/foo.py")` or `myFixture.configureByText("foo.py", "...")`;
  then `myFixture.file as PyFile`, edits via `myFixture.editor.document` +
  `WriteCommandAction.runWriteCommandAction(project) { doc.setText(...); PsiDocumentManager.getInstance(project).commitDocument(doc) }`.
- Assertions are inherited — call `assertEquals(...)` bare, NOT `TestCase.assertEquals(...)`.

## Build / gate
- Gate with `./gradlew --console=plain test -x buildSearchableOptions` (fast; platform cached).
- NEVER run `verifyPlugin` in a headless/CI shell — it downloads verifier IDEs and times out.
  `verifyPluginProjectConfiguration` is cheap and fine.
- Platform dep is property-driven: `pycharm(platformVersion)` with optional `-PlocalPycharmPath` override.
