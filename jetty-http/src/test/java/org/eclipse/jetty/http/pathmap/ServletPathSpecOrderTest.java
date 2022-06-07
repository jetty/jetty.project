//
//  ========================================================================
//  Copyright (c) 1995-2022 Mort Bay Consulting Pty Ltd and others.
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

import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests of {@link PathMappings#getMatched(String)}, with a focus on correct mapping selection order
 */
@SuppressWarnings("Duplicates")
public class ServletPathSpecOrderTest
{
    public static Stream<Arguments> data()
    {
        ArrayList<Arguments> data = new ArrayList<>();

        // From old PathMapTest
        data.add(Arguments.of("/abs/path", "abspath"));
        data.add(Arguments.of("/abs/path/xxx", "default"));
        data.add(Arguments.of("/abs/pith", "default"));
        data.add(Arguments.of("/abs/path/longer", "longpath"));
        data.add(Arguments.of("/abs/path/", "default"));
        data.add(Arguments.of("/abs/path/foo", "default"));
        data.add(Arguments.of("/animal/bird/eagle/bald", "birds"));
        data.add(Arguments.of("/animal/fish/shark/hammerhead", "fishes"));
        data.add(Arguments.of("/animal/insect/ladybug", "animals"));
        data.add(Arguments.of("/animal", "animals"));
        data.add(Arguments.of("/animal/", "animals"));
        data.add(Arguments.of("/animal/other", "animals"));
        data.add(Arguments.of("/animal/*", "animals"));
        data.add(Arguments.of("/downloads/distribution.tar.gz", "tarball"));
        data.add(Arguments.of("/downloads/script.gz", "gzipped"));
        data.add(Arguments.of("/animal/arhive.gz", "animals"));
        data.add(Arguments.of("/Other/path", "default"));
        // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
        data.add(Arguments.of("/\u20ACuro/path", "money"));
        // @checkstyle-enable-check : AvoidEscapedUnicodeCharactersCheck
        data.add(Arguments.of("/", "root"));

        // Extra tests
        data.add(Arguments.of("/downloads/readme.txt", "default"));
        data.add(Arguments.of("/downloads/logs.tgz", "default"));
        data.add(Arguments.of("/main.css", "default"));

        return data.stream();
    }

    private static PathMappings<String> mappings;

    static
    {
        mappings = new PathMappings<>();

        // From old PathMapTest
        mappings.put(new ServletPathSpec("/abs/path"), "abspath"); // 1
        mappings.put(new ServletPathSpec("/abs/path/longer"), "longpath"); // 2
        mappings.put(new ServletPathSpec("/animal/bird/*"), "birds"); // 3
        mappings.put(new ServletPathSpec("/animal/fish/*"), "fishes"); // 4
        mappings.put(new ServletPathSpec("/animal/*"), "animals"); // 5
        mappings.put(new ServletPathSpec("*.tar.gz"), "tarball"); // 6
        mappings.put(new ServletPathSpec("*.gz"), "gzipped"); // 7
        mappings.put(new ServletPathSpec("/"), "default"); // 8
        // 9 was the old Jetty ":" spec delimited case (no longer valid)
        mappings.put(new ServletPathSpec(""), "root"); // 10
        mappings.put(new ServletPathSpec("/\u20ACuro/*"), "money"); // 11
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testMatch(String inputPath, String expectedResource)
    {
        assertThat("Match on [" + inputPath + "]", mappings.getMatched(inputPath).getResource(), is(expectedResource));
    }
}
