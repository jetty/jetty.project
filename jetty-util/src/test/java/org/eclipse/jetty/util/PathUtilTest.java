//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.util;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class PathUtilTest
{
    public static Stream<Arguments> encodePathSource()
    {
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        return Stream.of(
            Arguments.of("/foo%23+;,:=/b a r/?info ", "/foo%2523%2B%3B%2C%3A%3D/b%20a%20r/%3Finfo%20"),
            Arguments.of("/context/'list'/\"me\"/;<script>window.alert('xss');</script>",
                "/context/%27list%27/%22me%22/%3B%3Cscript%3Ewindow.alert%28%27xss%27%29%3B%3C/script%3E"),
            Arguments.of("test\u00f6?\u00f6:\u00df", "test%C3%B6%3F%C3%B6%3A%C3%9F"),
            Arguments.of("test?\u00f6?\u00f6:\u00df", "test%3F%C3%B6%3F%C3%B6%3A%C3%9F"),
            Arguments.of("/euro/symbol/€ and \u20AC", "/euro/symbol/%E2%82%AC%20and%20%E2%82%AC")
        );
        // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("encodePathSource")
    public void testPathUtilEncodePath(String rawPath, String expectedEncoded)
    {
        String actualEncoded = PathUtil.encodePath(rawPath);
        assertEquals(expectedEncoded, actualEncoded);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("encodePathSource")
    public void testPathUtilEncodePathDelay(String rawPath, String expectedEncoded)
    {
        String actualEncoded = PathUtil.encodePathDelayAlloc(rawPath);
        assertEquals(expectedEncoded, actualEncoded);
    }
}
