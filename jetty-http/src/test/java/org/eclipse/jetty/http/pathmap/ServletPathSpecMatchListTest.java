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

package org.eclipse.jetty.http.pathmap;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

/**
 * Tests of {@link PathMappings#getMatches(String)}
 */
@SuppressWarnings("Duplicates")
public class ServletPathSpecMatchListTest
{
    public static Stream<Arguments> data()
    {
        ArrayList<Arguments> data = new ArrayList<>();

        // From old PathMapTest
        data.add(Arguments.of("All matches", "/animal/bird/path.tar.gz", "[/animal/bird/*=birds, /animal/*=animals, *.tar.gz=tarball, *.gz=gzipped, /=default]"));
        data.add(Arguments.of("Dir matches", "/animal/fish/", "[/animal/fish/*=fishes, /animal/*=animals, /=default]"));
        data.add(Arguments.of("Dir matches", "/animal/fish", "[/animal/fish/*=fishes, /animal/*=animals, /=default]"));
        data.add(Arguments.of("Root matches", "/", "[=root, /=default]"));
        data.add(Arguments.of("Dir matches", "", "[/=default]"));

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
    public void testGetMatches(String message, String inputPath, String expectedListing)
    {
        List<MappedResource<String>> matches = mappings.getMatches(inputPath);

        StringBuilder actual = new StringBuilder();
        actual.append('[');
        boolean delim = false;
        for (MappedResource<String> res : matches)
        {
            if (delim)
                actual.append(", ");
            actual.append(res.getPathSpec().getDeclaration()).append('=').append(res.getResource());
            delim = true;
        }
        actual.append(']');

        assertThat(message + " on [" + inputPath + "]", actual.toString(), is(expectedListing));
    }
}
