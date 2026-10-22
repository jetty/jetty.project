# Servlet TCK

Runs the Jakarta Servlet TCK against Jetty, using Arquillian to deploy the TCK's web
applications into an embedded Jetty server. Ported from
[jetty-project/servlet-tck-run](https://github.com/jetty-project/servlet-tck-run).

Only EE11 is covered today (`servlet-tck-ee11`). The directory is laid out so another
environment can be added as a sibling module.

The tests themselves are not in this repository — they live in the
`jakarta.servlet.tck:servlet-tck-runtime` jar on Maven Central, and Surefire is pointed at it
with `dependenciesToScan`. There is nothing to install by hand.

## Running it

The module is behind the `tck` profile, so a normal build ignores it:

```shell
# everything
mvn -Ptck test -pl tests/servlet-tck/servlet-tck-ee11

# one test class
mvn -Ptck test -pl tests/servlet-tck/servlet-tck-ee11 \
    -Dtest=servlet.tck.api.jakarta_servlet.servletcontext.ServletContextTests

# serially, which is easier to read when something fails
mvn -Ptck test -pl tests/servlet-tck/servlet-tck-ee11 \
    -Djunit.jupiter.execution.parallel.enabled=false
```

The Jetty artifacts under test come from the reactor, so build them first
(`mvn -Pfast install`) or add `-am`.

Test classes run two at a time by default. Each gets its own Arquillian `Manager` and its own
Jetty `Server` through `arquillian.junit.jupiter.manager.perClass`, so they do not share state.

## In CI

The Jenkins build passes `-Ptck` on every JDK stage, and a TCK failure fails the build. That
last part needs saying out loud in the pom: `mavenBuild` adds `-Dmaven.test.failure.ignore=true`
on the main branches, so the module sets `<testFailureIgnore>false</testFailureIgnore>` to opt
back out of it.

## Things that will bite you

**The Maven stack is pinned down, on purpose.** The TCK builds its web applications through
ShrinkWrap, which embeds Maven 3.9 and resolver 1.9. The rest of this build is on Maven 3.10 and
resolver 2.0. Mix the two and every deployment fails with
`NoClassDefFoundError: org/eclipse/aether/util/PathUtils`. That is what the `maven.deps.version`
and `maven.resolver.version` overrides in the module pom are for — don't "tidy" them away.

**`requireUpperBoundDeps` is off for this module.** Nearly the whole classpath comes from the TCK
jar at versions nothing else here uses. Every other enforcer rule still applies, including the
ban on `javax.*` APIs.

**The certificates are generated, not checked in.** `maven-dependency-plugin` unpacks them out of
the TCK jar and `keytool-maven-plugin` builds `target/cacerts.jks` from them, so a `clean` means
the secure tests need `generate-test-resources` to run again before they will pass.
