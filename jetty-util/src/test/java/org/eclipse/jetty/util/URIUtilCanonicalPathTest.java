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

package org.eclipse.jetty.util;

import java.util.ArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class URIUtilCanonicalPathTest
{
    public static Stream<Arguments> data()
    {
        String[][] canonical =
            {
                // Examples from RFC
                {"/a/b/c/./../../g", "/a/g"},
                {"mid/content=5/../6", "mid/6"},

                // Basic examples (no changes expected)
                {"/hello.html", "/hello.html"},
                {"/css/main.css", "/css/main.css"},
                {"/", "/"},
                {"", ""},
                {"/aaa/bbb/", "/aaa/bbb/"},
                {"/aaa/bbb", "/aaa/bbb"},
                {"aaa/bbb", "aaa/bbb"},
                {"aaa/", "aaa/"},
                {"aaa", "aaa"},
                {"a", "a"},
                {"a/", "a/"},

                // Extra slashes
                {"/aaa//bbb/", "/aaa//bbb/"},
                {"/aaa//bbb", "/aaa//bbb"},
                {"/aaa///bbb/", "/aaa///bbb/"},

                // Path traversal with current references "./"
                {"/aaa/./bbb/", "/aaa/bbb/"},
                {"/aaa/./bbb", "/aaa/bbb"},
                {"./bbb/", "bbb/"},
                {"./aaa", "aaa"},
                {"./aaa/", "aaa/"},
                {"/./aaa/", "/aaa/"},
                {"./aaa/../bbb/", "bbb/"},
                {"/foo/.", "/foo/"},
                {"/foo/./", "/foo/"},
                {"./", ""},
                {".", ""},
                {".//", "/"},
                {".///", "//"},
                {"/.", "/"},
                {"//.", "//"},
                {"///.", "///"},

                // Path traversal directory (but not past root)
                {"/aaa/../bbb/", "/bbb/"},
                {"/aaa/../bbb", "/bbb"},
                {"/aaa..bbb/", "/aaa..bbb/"},
                {"/aaa..bbb", "/aaa..bbb"},
                {"/aaa/..bbb/", "/aaa/..bbb/"},
                {"/aaa/..bbb", "/aaa/..bbb"},
                {"/aaa/./../bbb/", "/bbb/"},
                {"/aaa/./../bbb", "/bbb"},
                {"/aaa/bbb/ccc/../../ddd/", "/aaa/ddd/"},
                {"/aaa/bbb/ccc/../../ddd", "/aaa/ddd"},
                {"/foo/../bar//", "/bar//"},
                {"/ctx/../bar/../ctx/all/index.txt", "/ctx/all/index.txt"},
                {"/down/.././index.html", "/index.html"},

                // Path traversal up past root
                {"..", null},
                {"./..", null},
                {"aaa/../..", null},
                {"/foo/bar/../../..", null},
                {"/../foo", null},
                {"a/.", "a/"},
                {"a/..", ""},
                {"a/../..", null},
                {"/foo/../../bar", null},

                // Query parameter specifics
                {"/ctx/dir?/../index.html", "/ctx/index.html"},
                {"/get-files?file=/etc/passwd", "/get-files?file=/etc/passwd"},
                {"/get-files?file=../../../../../passwd", null},

                // Known windows shell quirks
                {"file.txt  ", "file.txt  "}, // with spaces
                {"file.txt...", "file.txt..."}, // extra dots ignored by windows
                // BREAKS Jenkins: {"file.txt\u0000", "file.txt\u0000"}, // null terminated is ignored by windows
                {"file.txt\r", "file.txt\r"}, // CR terminated is ignored by windows
                {"file.txt\n", "file.txt\n"}, // LF terminated is ignored by windows
                {"file.txt\"\"\"\"", "file.txt\"\"\"\""}, // extra quotes ignored by windows
                {"file.txt<<<>>><", "file.txt<<<>>><"}, // angle brackets at end of path ignored by windows
                {"././././././file.txt", "file.txt"},

                // Oddball requests that look like path traversal, but are not
                {"/....", "/...."},
                {"/..../ctx/..../blah/logo.jpg", "/..../ctx/..../blah/logo.jpg"},

                // paths with encoded segments should remain encoded
                // canonicalPath() is not responsible for decoding characters
                {"%2e%2e/", "%2e%2e/"},
                {"/%2e%2e/", "/%2e%2e/"},

                // paths with parameters are not elided
                // canonicalPath() is not responsible for decoding characters
                {"/foo/.;/bar", "/foo/.;/bar"},
                {"/foo/..;/bar", "/foo/..;/bar"},
                {"/foo/..;/..;/bar", "/foo/..;/..;/bar"},

                // Trailing / is preserved
                {"/foo/bar/..", "/foo/"},
                {"/foo/bar/../", "/foo/"},
            };

        ArrayList<Arguments> ret = new ArrayList<>();
        for (String[] args : canonical)
        {
            ret.add(Arguments.of((Object[])args));
        }
        return ret.stream();
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testCanonicalPath(String input, String expectedResult)
    {
        assertThat(URIUtil.canonicalPath(input), is(expectedResult));
    }
}
