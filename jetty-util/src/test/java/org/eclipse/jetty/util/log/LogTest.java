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

package org.eclipse.jetty.util.log;

import java.util.HashMap;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.junit.jupiter.api.Assertions.assertEquals;

public class LogTest
{
    // @checkstyle-disable-check : AvoidEscapedUnicodeCharactersCheck
    private static Logger originalLogger;
    private static Map<String, Logger> originalLoggers;

    @BeforeAll
    public static void rememberOriginalLogger()
    {
        originalLogger = Log.getLog();
        originalLoggers = new HashMap<>(Log.getLoggers());
        Log.getMutableLoggers().clear();
    }

    @AfterAll
    public static void restoreOriginalLogger()
    {
        Log.setLog(originalLogger);
        Log.getMutableLoggers().clear();
        Log.getMutableLoggers().putAll(originalLoggers);
    }

    @Test
    public void testDefaultLogging()
    {
        Logger log = Log.getLogger(LogTest.class);
        log.info("Test default logging");
    }

    @Test
    public void testNamedLogNamedStdErrLog()
    {
        Log.setLog(new StdErrLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    @Test
    public void testNamedLogNamedJUL()
    {
        Log.setLog(new JavaUtilLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    @Test
    public void testNamedLogNamedSlf4J() throws Exception
    {
        Log.setLog(new Slf4jLog());

        assertNamedLogging(Red.class);
        assertNamedLogging(Blue.class);
        assertNamedLogging(Green.class);
    }

    private void assertNamedLogging(Class<?> clazz)
    {
        Logger lc = Log.getLogger(clazz);
        assertEquals(lc.getName(), clazz.getName(), "Named logging (impl=" + Log.getLog().getClass().getName() + ")");
    }

    public static Stream<Arguments> packageCases()
    {
        return Stream.of(
            // null entry
            Arguments.of(null, ""),
            // empty entry
            Arguments.of("", ""),
            // all whitespace entry
            Arguments.of("  \t  ", ""),
            // bad / invalid characters
            Arguments.of("org.eclipse.Foo.\u0000", "oe.Foo"),
            Arguments.of("org.eclipse.\u20ac.Euro", "oe\u20ac.Euro"),
            // bad package segments
            Arguments.of(".foo", "foo"),
            Arguments.of(".bar.Foo", "b.Foo"),
            Arguments.of("org...bar..Foo", "ob.Foo"),
            Arguments.of("org . . . bar . . Foo ", "ob.Foo"),
            Arguments.of("org . . . bar . . Foo ", "ob.Foo"),
            // long-ish classname
            Arguments.of("org.eclipse.jetty.websocket.common.extensions.compress.DeflateFrameExtension", "oejwcec.DeflateFrameExtension"),
            // internal class
            Arguments.of("org.eclipse.jetty.foo.Bar$Internal", "oejf.Bar$Internal")
        );
    }

    @ParameterizedTest
    @MethodSource("packageCases")
    public void testCondensePackageViaLogger(String input, String expected)
    {
        StdErrLog log = new StdErrLog();
        StdErrLog logger = (StdErrLog)log.newLogger(input);
        assertThat("log[" + input + "] condenses to name", logger._abbrevname, is(expected));
    }

    @ParameterizedTest
    @MethodSource("packageCases")
    public void testCondensePackageDirect(String input, String expected)
    {
        assertThat("log[" + input + "] condenses to name", AbstractLogger.condensePackageString(input), is(expected));
    }
}
