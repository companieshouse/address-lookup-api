# Editor setup for Java formatting

The root `pom.xml` is the single source of truth for Spotless and Google Java
Format settings. The editor actions below call `scripts/spotless.sh tidy`,
the same helper used by `make tidy`. They do not duplicate formatting rules
in EditorConfig or editor-specific formatter configurations.

Open the repository root (the directory containing `pom.xml`) in your editor.
Bash, Maven and Java 21+ must be available in the editor's environment.
These instructions use the checked-in Bash helper and Unix `env` command.

These are explicit formatting actions, not automatic format-on-save or
replacements for an editor's built-in Format Document action. Editor trust
settings still apply; opening a checkout cannot silently enable every editor.
Only the selected Java file is formatted. Review the diff afterward.

## VS Code

Configuration: [`.vscode/tasks.json`](.vscode/tasks.json).

1. Open the repository root as the workspace and trust it if appropriate.
2. Open and save the Java file you want to format.
3. Choose **Terminal > Run Task > Spotless: format current Java file**.

The task passes the active file's absolute path as `FILE` and runs the helper
from the repository root. No VS Code formatting extension is required.
VS Code reloads the changed saved file. Formatter errors appear in the task
terminal; do not treat a failed task as successful formatting.

The task does not save unsaved buffers. Save first to avoid formatting an
older on-disk version.

## Vim

Configuration: [`.vim/spotless.vim`](.vim/spotless.vim).

From Vim with the repository root as its working directory, load the command
once per session:

```vim
:source .vim/spotless.vim
```

In a named Java buffer, run:

```vim
:Spotless
```

The command saves the buffer, formats that file through the helper, then
reloads it while preserving the view. Errors are displayed as Vim errors.
The helper path is resolved from the loaded script, so subsequent changes to
Vim's working directory do not change which project's formatter it uses.

If Vim started elsewhere, source the script using its full path. Local Vim
scripts are not automatically loaded; enabling global `exrc` is not required.

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

If a tracked editor file is missing, inspect `git status` before restoring it.
Global Git ignore rules do not delete tracked files or prevent their checkout.

See [COMMANDS.md](COMMANDS.md#java-formatting-tools) for formatter behavior,
standalone checks and optional pre-commit hook setup. The hook is separate
from these editor actions and formats all Java files when enabled.
