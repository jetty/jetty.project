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

package org.eclipse.jetty.start;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PathMatchersSearchRootTest
{
    public static Stream<Arguments> pathPatterns()
    {
        List<Arguments> arguments = new ArrayList<>();

        if (OS.LINUX.isCurrentOs() || OS.MAC.isCurrentOs())
        {
            // absolute first
            arguments.add(Arguments.of("/opt/app/*.jar", "/opt/app"));
            //@checkstyle-disable-check : LegacyMethodSeparators
            arguments.add(Arguments.of("/lib/jvm/**/jre/lib/*.jar", "/lib/jvm"));
            //@checkstyle-enable-check :  LegacyMethodSeparators
            arguments.add(Arguments.of("glob:/var/lib/*.xml", "/var/lib"));
            arguments.add(Arguments.of("glob:/var/lib/*.{xml,java}", "/var/lib"));
            arguments.add(Arguments.of("glob:/opt/corporate/lib-{dev,prod}/*.ini", "/opt/corporate"));
            arguments.add(Arguments.of("regex:/opt/jetty/.*/lib-(dev|prod)/*.ini", "/opt/jetty"));

            arguments.add(Arguments.of("/*.ini", "/"));
            arguments.add(Arguments.of("/etc/jetty.conf", "/etc"));
            arguments.add(Arguments.of("/common.conf", "/"));
        }

        if (OS.WINDOWS.isCurrentOs())
        {
            // absolute declaration
            arguments.add(Arguments.of("D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs\\example.jar",
                "D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs"));
            // escaped declaration
            // absolute patterns (complete with required windows slash escaping)
            arguments.add(Arguments.of("C:\\\\corp\\\\lib\\\\*.jar", "C:\\corp\\lib"));
            arguments.add(Arguments.of("D:\\\\lib\\\\**\\\\jre\\\\lib\\\\*.jar", "D:\\lib"));
        }

        // some relative paths
        arguments.add(Arguments.of("lib/*.jar", "lib"));
        arguments.add(Arguments.of("etc/jetty.xml", "etc"));
        arguments.add(Arguments.of("start.ini", "."));
        arguments.add(Arguments.of("start.d/", "start.d"));

        return Stream.of(
            arguments.toArray(new Arguments[0])
        );
    }

    @ParameterizedTest
    @MethodSource("pathPatterns")
    public void testSearchRoot(String pattern, String expectedSearchRoot)
    {
        Path actual = PathMatchers.getSearchRoot(pattern);
        String expectedNormal = FS.separators(expectedSearchRoot);
        assertThat(".getSearchRoot(\"" + pattern + "\")", actual.toString(), is(expectedNormal));
    }
}
