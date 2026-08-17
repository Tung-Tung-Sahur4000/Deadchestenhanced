# Contributing to DeadChest

Contributions are welcome. This document covers the local development workflow for building, testing, and iterating on
the plugin.

## Understanding the codebase

Read [docs/architecture.md](docs/architecture.md) first. It covers the module split, how a death is processed in both
modes, the crash duplication protection, the scheduler abstraction required for Folia, and the three places a new
configuration key must be declared.

## Requirements

- Java 17 for building and running tests
- A local server plugin directory only if you want to use the `copyJar` development task

## Build and Test

Run all checks from the repository root.

On Linux or macOS:

```bash
./gradlew build
```

On Windows:

```powershell
gradlew.bat build
```

This command compiles the project and runs the full test suite.

## Run Tests Only

On Linux or macOS:

```bash
./gradlew test
```

On Windows:

```powershell
gradlew.bat test
```

## Generate the Plugin JAR

To build the shaded plugin JAR explicitly:

On Linux or macOS:

```bash
./gradlew :deadchest-plugin:shadowJar
```

On Windows:

```powershell
gradlew.bat :deadchest-plugin:shadowJar
```

The generated file is written to `deadchest-plugin/build/libs`.

## Local Development Workflow

If you want Gradle to copy the built plugin directly into a local test server, add the following property to your
`gradle.properties`:

```properties
pluginDir=<path_to_your_plugin_folder_of_your_server>
```

Then run:

```bash
./gradlew :deadchest-plugin:copyJar --continuous
```

This rebuilds the plugin on each change and copies the JAR to your server's plugin directory.

## Best Practices

Please keep contributions consistent with the existing codebase and aim for maintainable, readable changes.

- Add or update tests when you change behavior, fix a bug, or introduce new logic.
- Prefer clear and descriptive names for variables, methods, and classes.
- Keep methods focused and avoid mixing unrelated responsibilities in the same block of code.
- Favor simple, explicit code over clever or overly compact implementations.
- Reuse existing patterns in the project when they already solve the problem well.
- Update documentation when a change affects setup, behavior, or user-facing features.
- Try to keep pull requests scoped to a single concern whenever possible.

## Traps worth knowing

- A new config key must be declared in `ConfigKey`, registered in `DeadChestLoader.registerConfig()` and present in
  `deadchest-plugin/src/main/resources/config.yml`. Miss the last one and the plugin rewrites `config.yml` on every
  startup.
- Never reference a `Material`, `Particle`, `Sound` or `Enchantment` constant that does not exist on every supported
  version. Resolve it by name, and skip the feature when the lookup fails.
- Anything touching a block, an entity or a chunk goes through `SchedulerAdapter`, never through
  `Bukkit.getScheduler()` directly, otherwise it breaks on Folia.
- `/dc reload` re-reads the configuration but does not migrate it. New keys only appear after a restart.
