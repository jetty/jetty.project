# Jetty 13 - Environment Downloadable Modules

## Overview

In Jetty 13, environment-specific jars (e.g., servlet API, JDBC drivers, etc.) are no longer shipped inside the jetty-home distribution. Instead, they are made available via the `--download` mechanism, which fetches them from Maven Central on demand when the corresponding module is enabled.

## Changes

- All `.mod` files for environment modules have been modified to replace `--lib` lines (which point to local shipped jars) with `--download` lines that specify Maven coordinates and download URLs.
- The shipped jar files themselves have been removed from the distribution (not included here, but should be deleted from the `lib` directory).
- The `--download` lines follow the format: `--download=<groupId>:<artifactId>:<version>|<url>` (or simplified to `--download=<version>|<url>` as shown).

## How to Use

1. Download the Jetty 13 distribution.
2. If enabling an environment module (e.g., `--module=servlet-6.0`), the first time you run `java -jar start.jar`, it will automatically download the required jars.
3. Alternatively, you can manually download using `--download=<module>` command.

## Script

A conversion script `bin/convert-environments.sh` is provided to automate the transformation of existing environment modules. It searches for `.mod` files containing "env" in their names and converts `--lib` entries to `--download` entries.

## Future

All environment modules should follow this pattern. Non-environment modules (e.g., core server) may still ship jars if necessary.
