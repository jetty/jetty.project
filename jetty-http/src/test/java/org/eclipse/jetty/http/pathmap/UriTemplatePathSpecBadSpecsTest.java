//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//  ------------------------------------------------------------------------
//  All rights reserved. This program and the accompanying materials
//  are made available under the terms of the Eclipse Public License v1.0
//  and Apache License v2.0 which accompanies this distribution.
//
//      The Eclipse Public License is available at
//      http://www.eclipse.org/legal/epl-v10.html
//
//      The Apache License v2.0 is available at
//      http://www.opensource.org/licenses/apache2.0.php
//
//  You may elect to redistribute this code under either of these licenses.
//  ========================================================================
//

package org.eclipse.jetty.http.pathmap;

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for bad path specs on ServerEndpoint Path Param / URI Template
 */
public class UriTemplatePathSpecBadSpecsTest
{
    public static Stream<Arguments> data()
    {
        String[] badSpecs = new String[]{
            "/a/b{var}", // bad syntax - variable does not encompass whole path segment
            "a/{var}", // bad syntax - no start slash
            "/a/{var/b}", // path segment separator in variable name
            "/{var}/*", // bad syntax - no globs allowed
            "/{var}.do", // bad syntax - variable does not encompass whole path segment
            "/a/{var*}", // use of glob character not allowed in variable name
            "/a/{}", // bad syntax - no variable name
            // MIGHT BE ALLOWED "/a/{---}", // no alpha in variable name
            "{var}", // bad syntax - no start slash
            "/a/{my special variable}", // bad syntax - space in variable name
            "/a/{var}/{var}", // variable name duplicate
            // MIGHT BE ALLOWED "/a/{var}/{Var}/{vAR}", // variable name duplicated (diff case)
            "/a/../../../{var}", // path navigation not allowed
            "/a/./{var}", // path navigation not allowed
            "/a//{var}" // bad syntax - double path slash (no path segment)
        };

        return Stream.of(badSpecs).map(Arguments::of);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @MethodSource("data")
    public void testBadPathSpec(String pathSpec)
    {
        assertThrows(IllegalArgumentException.class, () -> new UriTemplatePathSpec(pathSpec));
    }
}
