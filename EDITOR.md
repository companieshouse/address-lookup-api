# Editor setup for Java formatting

The root `pom.xml` is the single source of truth for Spotless and Google Java
Format settings. The editor action below calls `scripts/spotless.sh tidy`,
the same helper used by `make tidy`. It does not duplicate formatting rules
in EditorConfig or editor-specific formatter configurations.

Open the repository root (the directory containing `pom.xml`) in your editor.
Bash, Maven and Java 21+ must be available in the editor's environment.
These instructions use the checked-in Bash helper and Unix `env` command.

This is an explicit formatting action, not automatic format-on-save or a
replacement for an editor's built-in Format Document action. Editor trust
settings still apply; opening a checkout cannot silently enable every editor.
Only the selected Java file is formatted. Review the diff afterward.

## IntelliJ IDEA

External Tools are IDE settings, not shared project settings. Configure this
once per installation under **Settings > Tools > External Tools**:

| Setting | Value |
| --- | --- |
| Name | `Spotless` |
| Program | `env` |
| Arguments | `"FILE=$FilePath$" bash "$ProjectFileDir$/scripts/spotless.sh" tidy` |
| Working directory | `$ProjectFileDir$` |

Enable **Synchronize files after execution** and the console for failures.
The quotes in the arguments preserve paths containing spaces.

Save the active Java file, then choose **Tools > External Tools > Spotless**.
Formatter errors appear in the External Tools console. You can assign a
shortcut under **Settings > Keymap > External Tools**.

Do not configure a separate Google Java Format version or indentation scheme
as a substitute for this action; the helper uses the project's pinned Maven
configuration.

## Command-line alternative and troubleshooting

From the repository root:

```bash
make tidy FILE=path/to/YourFile.java
make spotless-check
```

If an editor cannot find Maven or Java, check its environment, which may
differ from your terminal's environment. Restart the editor after updating
its environment. Formatting may need network access for Maven dependencies.
Do not edit a file while formatting is running.

See [COMMANDS.md](COMMANDS.md#java-formatting-tools) for formatter behavior,
standalone checks and optional pre-commit hook setup. The hook is separate
from these editor actions and formats all Java files when enabled.
