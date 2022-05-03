//
// ========================================================================
// Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.logging;

import java.io.BufferedReader;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static java.nio.charset.StandardCharsets.UTF_8;

/**
 * Tool to help identify maven projects that need to be updated.
 */
public class Slf4jEffort
{
    public static void main(String[] args) throws Throwable
    {
        if (args.length < 1)
            throw new IllegalStateException("No path specified: (expected) Slf4jEffort <path>");

        Path path = Paths.get(args[0]);

        if (!Files.exists(path))
        {
            throw new FileNotFoundException(path.toString());
        }

        if (!Files.isDirectory(path))
        {
            throw new IllegalStateException("Not a directory: " + path.toString());
        }

        Slf4jEffort effort = new Slf4jEffort();
        effort.scan(path);
    }

    private Predicate<Path> notInTargetDirectory = (path) -> !path.toString().contains("/target/");

    private void scan(Path root) throws IOException
    {
        AtomicInteger countJpms = new AtomicInteger(0);
        AtomicInteger countPomSlf4jApis = new AtomicInteger(0);
        AtomicInteger countPomSlf4jImpls = new AtomicInteger(0);
        AtomicInteger countOldLogClassProps = new AtomicInteger(0);

        getProjectsStream(root)
            .filter(pom ->
            {
                String fullpath = pom.toString();
                return !((fullpath.contains("/jetty-osgi") ||
                    fullpath.contains("/jetty-slf4j-impl/")));
            })
            .forEach((pom) ->
            {
                Path project = pom.getParent();
                try
                {
                    Path testLoggingProps = project.resolve("src/test/resources/jetty-logging.properties");

                    boolean isMainSrcUsingLogging = getSources(project.resolve("src/main/java")).anyMatch(Slf4jEffort::isUsingLogging);
                    boolean isTestSrcUsingLogging = getSources(project.resolve("src/test/java")).anyMatch(Slf4jEffort::isUsingLogging);

                    if (isMainSrcUsingLogging || isTestSrcUsingLogging)
                    {
                        if (!isSlf4jImplDepPresent(pom))
                        {
                            System.err.printf("[Missing: Dep: jetty-slf4j-impl] %s%n", pom);
                            countPomSlf4jImpls.incrementAndGet();
                        }

                        // Must include slf4j in module-info and pom
                        Path moduleInfo = project.resolve("src/main/java/module-info.java");
                        if (Files.exists(moduleInfo) && isMainSrcUsingLogging && !isLoggingJpmsPresent(moduleInfo))
                        {
                            System.err.printf("[Missing: JPMS] %s%n", moduleInfo);
                            countJpms.incrementAndGet();
                        }

                        if (!isSlf4jDepPresent(pom))
                        {
                            System.err.printf("[Missing: Dep: slf4j-api] %s%n", pom);
                            countPomSlf4jApis.incrementAndGet();
                        }

                        if (Files.exists(testLoggingProps) && isOldLogClassPropPresent(testLoggingProps))
                        {
                            System.err.printf("[Deprecated: log.class=LogImpl] %s%n", testLoggingProps);
                            countOldLogClassProps.incrementAndGet();
                        }
                    }
                }
                catch (IOException e)
                {
                    e.printStackTrace();
                }
            });

        System.out.printf("JPMS (module-info.java) to fix: %d%n", countJpms.get());
        System.out.printf("POMS (pom.xml) - slf4j-api to fix: %d%n", countPomSlf4jApis.get());
        System.out.printf("POMS (pom.xml) - jetty-slf4j-impl to fix: %d%n", countPomSlf4jImpls.get());
        System.out.printf("PROPS (jetty-logging.properties) - to fix: %d%n", countOldLogClassProps.get());
    }

    private boolean isDepPresent(Path pom, String keyword)
    {
        try (BufferedReader reader = Files.newBufferedReader(pom))
        {
            String line;
            boolean inDependencies = false;
            while ((line = reader.readLine()) != null)
            {
                if (line.contains("<dependencies>"))
                {
                    inDependencies = true;
                    continue;
                }

                if (line.contains("</dependencies>"))
                {
                    inDependencies = false;
                    continue;
                }

                if (inDependencies && line.contains(keyword))
                {
                    return true;
                }
            }
        }
        catch (IOException e)
        {
            System.err.printf(" [WARN] (%s) %s in %s%n", e.getClass().getName(), e.getMessage(), pom);
        }
        return false;
    }

    private boolean isSlf4jDepPresent(Path pom)
    {
        return isDepPresent(pom, "<artifactId>slf4j-api</artifactId>");
    }

    private boolean isSlf4jImplDepPresent(Path pom)
    {
        return isDepPresent(pom, "<artifactId>jetty-slf4j-impl</artifactId>");
    }

    private boolean isOldLogClassPropPresent(Path propFile)
    {
        return getLineStream(propFile).anyMatch((line) -> line.startsWith("org.eclipse.jetty.util.log.class="));
    }

    private Stream<String> getLineStream(Path file)
    {
        try
        {
            return Files.readAllLines(file, UTF_8).stream();
        }
        catch (IOException e)
        {
            System.err.printf(" [WARN] (%s) %s in %s%n", e.getClass().getName(), e.getMessage(), file);
            return Stream.empty();
        }
    }

    private boolean isLoggingJpmsPresent(Path moduleInfo)
    {
        try (BufferedReader reader = Files.newBufferedReader(moduleInfo))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.contains("requires org.slf4j;") ||
                    line.contains("requires transitive org.slf4j;") ||
                    line.contains("requires static org.slf4j;"))
                {
                    return true;
                }
            }
        }
        catch (IOException e)
        {
            System.err.printf(" [WARN] (%s) %s in %s%n", e.getClass().getName(), e.getMessage(), moduleInfo);
        }
        return false;
    }

    private static boolean isUsingLogging(Path src)
    {
        try (BufferedReader reader = Files.newBufferedReader(src))
        {
            String line;
            while ((line = reader.readLine()) != null)
            {
                if (line.startsWith("import org.eclipse.jetty.util.log.") ||
                    line.startsWith("import org.slf4j.") ||
                    line.startsWith("import org.eclipse.jetty.logging."))
                {
                    return true;
                }
            }
        }
        catch (IOException e)
        {
            System.err.printf(" [WARN] (%s) %s in %s%n", e.getClass().getName(), e.getMessage(), src);
        }
        return false;
    }

    private Stream<Path> getSources(Path src) throws IOException
    {
        if (!Files.exists(src) || !Files.isDirectory(src))
        {
            return Stream.empty();
        }
        try (Stream<Path> s = Files.walk(src))
        {
            return s
                .filter(Files::isRegularFile)
                .filter((path) -> path.getFileName().toString().endsWith(".java"))
                .collect(Collectors.toList())
                .stream();
        }
    }

    private Stream<Path> getProjectsStream(Path root) throws IOException
    {
        try (Stream<Path> s = Files.walk(root))
        {
            return s
                .filter(notInTargetDirectory)
                .filter(Files::isRegularFile)
                .filter((path) -> path.getFileName().toString().equals("pom.xml"))
                .collect(Collectors.toList())
                .stream();
        }
    }
}
