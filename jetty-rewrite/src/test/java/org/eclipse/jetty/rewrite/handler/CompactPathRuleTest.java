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

package org.eclipse.jetty.rewrite.handler;

import java.util.stream.Stream;

import org.eclipse.jetty.http.HttpURI;
import org.eclipse.jetty.util.URIUtil;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertEquals;

public class CompactPathRuleTest extends AbstractRuleTestCase
{
    public static Stream<Arguments> scenarios()
    {
        return Stream.of(
            // shouldn't change anything
            Arguments.of("/foo", null, "/foo", null, "/foo"),
            Arguments.of("/", null, "/", null, "/"),
            // simple compact path
            Arguments.of("////foo", null, "/foo", null, "/foo"),
            // with simple query
            Arguments.of("//foo//bar", "a=b", "/foo/bar", "a=b", "/foo/bar?a=b"),
            // with query that has double slashes (should preserve slashes in query)
            Arguments.of("//foo//bar", "a=b//c", "/foo/bar", "a=b//c", "/foo/bar?a=b//c")
        );
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testCompactPathRule(String inputPath, String inputQuery, String expectedPath, String expectedQuery, String expectedPathQuery) throws Exception
    {
        start(false);

        CompactPathRule rule = new CompactPathRule();

        reset();
        _request.setHttpURI(HttpURI.build(_request.getHttpURI(), inputPath, null, inputQuery).asImmutable());

        String target = _request.getHttpURI().getDecodedPath();

        String applied = rule.matchAndApply(target, _request, _response);
        assertEquals(expectedPath, applied);

        String encoded = URIUtil.encodePath(applied);
        rule.applyURI(_request, _request.getRequestURI(), encoded);

        assertEquals(expectedPath, _request.getRequestURI());
        assertEquals(expectedQuery, _request.getQueryString());
        assertEquals(expectedPathQuery, _request.getHttpURI().getPathQuery());
    }
}
