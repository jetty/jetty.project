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

package org.eclipse.jetty.http;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDir;
import org.eclipse.jetty.toolchain.test.jupiter.WorkDirExtension;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.endsWith;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.startsWith;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@ExtendWith(WorkDirExtension.class)
public class EtagUtilTest
{
    public WorkDir workDir;

    @Test
    public void testCalcWeakETag() throws IOException
    {
        Path root = workDir.getEmptyPathDir();
        Path testFile = root.resolve("test.dat");
        Files.writeString(testFile, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        String weakEtag = EtagUtil.calcWeakEtag(testFile);
        assertThat(weakEtag, startsWith("W/\""));
        assertThat(weakEtag, endsWith("\""));
    }

    @Test
    public void testCalcWeakETagSameFileDifferentLocations() throws IOException
    {
        Path root = workDir.getEmptyPathDir();
        Path testFile = root.resolve("test.dat");
        Files.writeString(testFile, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");

        Path altDir = root.resolve("alt");
        FS.ensureDirExists(altDir);
        Path altFile = altDir.resolve("test.dat");
        Files.copy(testFile, altFile);

        String weakEtagOriginal = EtagUtil.calcWeakEtag(testFile);
        assertThat(weakEtagOriginal, startsWith("W/\""));
        assertThat(weakEtagOriginal, endsWith("\""));

        String weakEtagAlt = EtagUtil.calcWeakEtag(altFile);
        assertThat(weakEtagAlt, startsWith("W/\""));
        assertThat(weakEtagAlt, endsWith("\""));

        // When referenced locations are different, the etag should be different as well
        assertThat(weakEtagAlt, not(is(weakEtagOriginal)));
    }

    public static Stream<Arguments> rewriteWithSuffixCases()
    {
        return Stream.of(
            // Simple, not quoted, no suffix in original
            Arguments.of("ABCDEF", "-br", "\"ABCDEF-br\""),
            // Weak, not quoted, no suffix in original
            Arguments.of("W/ABCDEF", "-br", "W/\"ABCDEF-br\""),
            // Simple, quoted, no suffix in original
            Arguments.of("\"ABCDEF\"", "-br", "\"ABCDEF-br\""),
            // Weak, quoted, no suffix in original
            Arguments.of("W/\"ABCDEF\"", "--gzip", "W/\"ABCDEF--gzip\""),
            // Simple, quoted, gzip suffix in original
            Arguments.of("\"ABCDEF-gzip\"", "-br", "\"ABCDEF-br\""),
            // Simple, not quoted, gzip suffix in original
            Arguments.of("ABCDEF-gzip", "-br", "\"ABCDEF-br\""),
            // Weak, quoted, gzip suffix in original, different ETAG_SEPARATOR size
            Arguments.of("W/\"ABCDEF-gzip\"", "--br", "W/\"ABCDEF--br\"")
        );
    }

    @ParameterizedTest
    @MethodSource("rewriteWithSuffixCases")
    public void testRewriteWithSuffix(String input, String newSuffix, String expected)
    {
        String actual = EtagUtil.rewriteWithSuffix(input, newSuffix);
        assertThat(actual, is(expected));
    }

    public static Stream<Arguments> matchTrueCases()
    {
        return Stream.of(
            Arguments.of("tag", "tag"),
            Arguments.of("\"tag\"", "\"tag\""),
            Arguments.of("\"tag\"", "\"tag--gz\""),
            Arguments.of("\"tag\"", "\"tag--br\""),
            Arguments.of("W/\"1234567\"", "W/\"1234567\""),
            Arguments.of("W/\"1234567\"", "W/\"1234567--br\""),
            Arguments.of("12345", "\"12345\""),
            Arguments.of("\"12345\"", "12345"),
            Arguments.of("12345", "\"12345--gzip\""),
            Arguments.of("\"12345\"", "12345--gzip")
        );
    }

    @ParameterizedTest
    @MethodSource("matchTrueCases")
    public void testMatchTrue(String etag, String etagWithOptionalSuffix)
    {
        assertTrue(EtagUtil.match(etag, etagWithOptionalSuffix));
    }

    public static Stream<Arguments> matchFalseCases()
    {
        return Stream.of(
            Arguments.of("Zag", "Xag--gzip"),
            Arguments.of("xtag", "tag"),
            Arguments.of("W/\"1234567\"", "W/\"1234111\""),
            Arguments.of("W/\"1234567\"", "W/\"1234111--gzip\"")
        );
    }

    @ParameterizedTest
    @MethodSource("matchFalseCases")
    public void testMatchFalse(String etag, String etagWithOptionalSuffix)
    {
        assertFalse(EtagUtil.match(etag, etagWithOptionalSuffix));
    }
}
