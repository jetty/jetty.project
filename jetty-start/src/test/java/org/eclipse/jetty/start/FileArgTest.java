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

import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.nullValue;

public class FileArgTest
{
    public static Stream<Arguments> data()
    {
        return Stream.of(
            Arguments.of("resource", null, "resource"),
            Arguments.of("lib/logging", null, "lib/logging"),

            // -- URI with relative location --
            Arguments.of("http://machine.com/my.conf|resources/my.conf", "http://machine.com/my.conf", "resources/my.conf"),
            Arguments.of("http://machine.com:8080/my.conf|resources/my.conf", "http://machine.com:8080/my.conf", "resources/my.conf"),
            Arguments.of("https://machine.com:8080/my.conf|resources/my.conf", "https://machine.com:8080/my.conf", "resources/my.conf"),
            // Windows URI (drive mapped)
            Arguments.of("file:///Z:/share/my.conf|resources/my.conf", "file:///Z:/share/my.conf", "resources/my.conf"),
            // Windows URI (network share)
            Arguments.of("file:////nas/share/my.conf|resources/my.conf", "file:////nas/share/my.conf", "resources/my.conf"),

            // -- URI with absolute location --
            Arguments.of("http://machine.com/db.dat|/var/run/db.dat", "http://machine.com/db.dat", "/var/run/db.dat"),
            Arguments.of("http://machine.com:8080/b/db.dat|/var/run/db.dat", "http://machine.com:8080/b/db.dat", "/var/run/db.dat"),
            Arguments.of("https://machine.com:8080/c/db.dat|/var/run/db.dat", "https://machine.com:8080/c/db.dat", "/var/run/db.dat"),
            // Windows URI (drive mapped) to drive mapped output
            Arguments.of("file:///Z:/share/my.conf|C:/db/db.dat", "file:///Z:/share/my.conf", "C:/db/db.dat"),
            Arguments.of("file:///Z:/share/my.conf|C:\\db\\db.dat", "file:///Z:/share/my.conf", "C:\\db\\db.dat"),
            // Windows URI (drive mapped) to network share output
            Arguments.of("file:///Z:/share/my.conf|\\\\nas\\apps\\db\\db.dat", "file:///Z:/share/my.conf", "\\\\nas\\apps\\db\\db.dat"),
            // Windows URI (network share) to drive mapped output
            Arguments.of("file:////nas/share/my.conf|C:/db/db.dat", "file:////nas/share/my.conf", "C:/db/db.dat"),
            Arguments.of("file:////nas/share/my.conf|C:\\db\\db.dat", "file:////nas/share/my.conf", "C:\\db\\db.dat"),
            // Windows URI (network share) to network share output
            Arguments.of("file:////nas/share/my.conf|\\\\nas\\apps\\db\\db.dat", "file:////nas/share/my.conf", "\\\\nas\\apps\\db\\db.dat")
        );
    }

    @ParameterizedTest
    @MethodSource("data")
    public void testFileArg(String rawFileRef, String expectedUri, String expectedLocation)
    {
        FileArg arg = new FileArg(null, rawFileRef);
        if (expectedUri == null)
        {
            assertThat("URI", arg.uri, nullValue());
        }
        else
        {
            assertThat("URI", arg.uri, is(expectedUri));
        }
        assertThat("Location", arg.location, is(expectedLocation));
    }
}
