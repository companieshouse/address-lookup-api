# Database

Flyway SQL lives in `migrations/`. Local-only seed data lives in `local/` and is never packaged for
deployed environments. See the root `README.md` for how to build, run and test.

## Bootstrap rule

A release is either `FULL` (a complete copy of the dataset) or `COU` (a change-only update that
must be applied on top of the release named in its `previous` field).

`os_data.release_history` records every release applied to the active tables. When it has **no
rows**, the database holds no baseline, so a `COU` has nothing to build on. In that state only a
`FULL` release is accepted. Once a `FULL` has been applied, later `COU` releases are accepted in
order.

The rule is documented here and in `V2026.007`; it is not enforced by a database constraint. The
release workflows must check it using `os_data.release_history`.
