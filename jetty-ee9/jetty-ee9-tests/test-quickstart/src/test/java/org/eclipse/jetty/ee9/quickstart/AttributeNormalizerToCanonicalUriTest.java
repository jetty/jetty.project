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

package org.eclipse.jetty.ee9.quickstart;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class AttributeNormalizerToCanonicalUriTest
{
    public static Stream<String[]> sampleUris()
    {
        List<String[]> data = new ArrayList<>();

        // root without authority
        data.add(new String[]{"file:/", "file:/"});
        data.add(new String[]{"file:/F:/", "file:/F:"});

        // root with empty authority
        data.add(new String[]{"file:///", "file:///"});
        data.add(new String[]{"file:///F:/", "file:///F:"});

        // deep directory - no authority
        data.add(new String[]{"file:/home/user/code/", "file:/home/user/code"});
        data.add(new String[]{"file:/C:/code/", "file:/C:/code"});

        // deep directory - with authority
        data.add(new String[]{"file:///home/user/code/", "file:///home/user/code"});
        data.add(new String[]{"file:///C:/code/", "file:///C:/code"});

        // Some non-file tests
        data.add(new String[]{"http://webtide.com/", "http://webtide.com/"});
        data.add(new String[]{"http://webtide.com/cometd/", "http://webtide.com/cometd"});

        return data.stream();
    }

    @ParameterizedTest
    @MethodSource("sampleUris")
    public void testCanonicalURI(String input, String expected)
    {
        URI inputURI = URI.create(input);
        URI actual = AttributeNormalizer.toCanonicalURI(inputURI);
        assertThat(input, actual.toASCIIString(), is(expected));
    }
}
