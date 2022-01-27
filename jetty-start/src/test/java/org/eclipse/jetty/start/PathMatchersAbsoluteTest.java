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

package org.eclipse.jetty.start;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;

public class PathMatchersAbsoluteTest
{
    public static Stream<Arguments> pathPatterns()
    {
        List<Arguments> arguments = new ArrayList<>();

        if (OS.LINUX.isCurrentOs() | OS.MAC.isCurrentOs())
        {
            arguments.add(Arguments.of("/opt/app", true));
            arguments.add(Arguments.of("/opt/app", true));
            arguments.add(Arguments.of("/opt/florb", true));
            arguments.add(Arguments.of("/home/user/benfranklin", true));
            arguments.add(Arguments.of("glob:/home/user/benfranklin/*.jar", true));
            //@checkstyle-disable-check : LegacyMethodSeparators
            arguments.add(Arguments.of("glob:/**/*.jar", true));
            //@checkstyle-enable-check : LegacyMethodSeparators
            arguments.add(Arguments.of("regex:/*-[^dev].ini", true));
        }

        if (OS.WINDOWS.isCurrentOs())
        {
            // normal declaration
            arguments.add(Arguments.of("D:\\code\\jetty\\jetty-start\\src\\test\\resources\\extra-libs\\example.jar", true));
            // escaped declaration
            arguments.add(Arguments.of("C:\\\\System32", true));
            arguments.add(Arguments.of("C:\\\\Program Files", true));
        }

        arguments.add(Arguments.of("etc", false));
        arguments.add(Arguments.of("lib", false));
        arguments.add(Arguments.of("${user.dir}", false));
        arguments.add(Arguments.of("**/*.jar", false));
        arguments.add(Arguments.of("glob:*.ini", false));
        arguments.add(Arguments.of("regex:*-[^dev].ini", false));

        return Stream.of(arguments.toArray(new Arguments[0]));
    }

    @ParameterizedTest
    @MethodSource("pathPatterns")
    public void testIsAbsolute(String pattern, boolean expected)
    {
        assertThat("isAbsolute(\"" + pattern + "\")", PathMatchers.isAbsolute(pattern), is(expected));
    }
}
