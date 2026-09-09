//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.start.usecases;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.file.Files;
import java.util.List;

import org.eclipse.jetty.toolchain.test.FS;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.allOf;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

public class ModuleValidationTest extends AbstractUseCase
{
    /**
     * Test of validation, where the referenced lib jar does not exist,
     * but potentially comes from the [files] section.
     */
    @Test
    public void testNonExistentXmlWithMavenFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        FS.ensureDirExists(baseDir.resolve("modules/demo.d"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [files]
            basehome:modules/demo.d/foo.xml|etc/foo-1.1.xml
            [xml]
            etc/foo-1.1.xml
            """, UTF_8);
        Files.writeString(baseDir.resolve("modules/demo.d/foo.xml"),
            """
            <example/>
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            results.main.validateModules(capture, results.startArgs);
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("No failure detected")));
        }
    }

    /**
     * Test of validation, where the referenced lib jar does not exist, and has no associated [files] section
     * that could potentially create it.
     */
    @Test
    public void testNonExistentXmlWithOutFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [xml]
            lib/foo.xml
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [xml] Does not exist 'lib/foo.xml'")));
        }
    }

    /**
     * Test of validation, where the referenced lib jar does not exist,
     * but potentially comes from the [files] section.
     */
    @Test
    public void testNonExistentLibJarWithMavenFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [files]
            maven://org.example/foo/1.1/jar|lib/foo-1.1.jar
            [lib]
            lib/foo-1.1.jar
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            results.main.validateModules(capture, results.startArgs);
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("No failure detected")));
        }
    }

    /**
     * Test of validation, where the referenced lib jar does not exist, and has no associated [files] section
     * that could potentially create it.
     */
    @Test
    public void testNonExistentLibJarWithOutFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [lib]
            lib/foo-1.1.jar
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [lib] Does not exist 'lib/foo-1.1.jar'")));
        }
    }

    /**
     * Test of validation, where the referenced lib dir does not exist,
     * but potentially comes from the [files] section.
     */
    @Test
    public void testNonExistentLibDirWithMavenFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [files]
            resources/
            [lib]
            resources/
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            results.main.validateModules(capture, results.startArgs);
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("No failure detected")));
        }
    }

    /**
     * Test of validation, where the referenced lib dir does not exist, and has no associated [files] section
     * that could potentially create it.
     */
    @Test
    public void testNonExistentLibDirWithOutFileReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [lib]
            resources/
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [lib] Does not exist 'resources/'")));
        }
    }

    /**
     * Test of validation, where a [lib] section has a reference using a glob of jars
     */
    @Test
    public void testLibGlobReference() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [lib]
            # The proper syntax is to use double `**.jar`
            lib/feature/*.jar
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [lib] Bad glob definition (should end in `/**.jar`) lib/feature/*.jar")));
        }
    }

    /**
     * Test of validation, where a [files] section has a property expansion that
     * doesn't exist.
     */
    @Test
    public void testFileWithInvalidProperty() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [files]
            # The `foo.version` property is undefined
            maven://org.example/foo/${foo.version}/jar|lib/foo-${foo.version}.jar
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [files] Unable to expand property 'maven://org.example/foo/${foo.version}/jar|lib/foo-${foo.version}.jar'")));
        }
    }

    /**
     * Test of validation, where the property expression in the [ini] section wasn't
     * expanded properly (these unexpanded property expressions indicate a mistake
     * that occurred during the build, where the resulting *-config.jar didn't
     * properly perform its maven-resources-plugin behaviors)
     */
    @ParameterizedTest(name = "{index}")
    @ValueSource(strings = {
        "foo.version=@foo.version@",
        "foo.version=${ee.foo.version}"
    })
    public void testUnexpandedIniProperty(String property) throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [ini]
            %s
            """.formatted(property), UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [ini] Bad/Unexpandable property [%s]".formatted(property))));
        }
    }

    @Test
    public void testDependencyDoesNotExist() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [depend]
            # Reference to module that doesn't exist
            bogus-foo
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [depends] Does not exist 'bogus-foo'")));
        }
    }

    @Test
    public void testBeforeDoesNotExist() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [before]
            # Reference to module that doesn't exist
            bogus-foo
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [before] Does not exist 'bogus-foo'")));
        }
    }

    @Test
    public void testAfterDoesNotExist() throws IOException
    {
        setupStandardHomeDir();

        FS.ensureDirExists(baseDir.resolve("modules"));
        Files.writeString(baseDir.resolve("modules/feature.mod"),
            """
            [after]
            # Reference to module that doesn't exist
            bogus-foo
            """, UTF_8);

        List<String> runArgs = List.of(
            "--validate-modules=feature"
        );
        ExecResults results = exec(runArgs, false);
        try (PrintCapture capture = new PrintCapture())
        {
            assertThrows(IllegalStateException.class, () -> results.main.validateModules(capture, results.startArgs));
            String validationOutput = capture.asString();
            assertThat(validationOutput, allOf(
                containsString("Validated 1 module(s)"),
                containsString("There are 1 failed module validations"),
                containsString("feature - [after] Does not exist 'bogus-foo'")));
        }
    }

    public static class PrintCapture extends PrintStream
    {
        private final ByteArrayOutputStream byteStream;

        public PrintCapture()
        {
            super(new ByteArrayOutputStream());
            this.byteStream = (ByteArrayOutputStream)out;
        }

        public String asString()
        {
            return this.byteStream.toString(UTF_8);
        }
    }
}
