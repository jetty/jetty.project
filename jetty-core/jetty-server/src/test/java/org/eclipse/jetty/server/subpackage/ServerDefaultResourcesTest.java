//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.server.subpackage;

import java.util.stream.Stream;

import org.eclipse.jetty.server.Server;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.junit.jupiter.api.Assertions.assertNotNull;

public class ServerDefaultResourcesTest
{
    public static Stream<Arguments> arguments()
    {
        return Stream.of(
            new Server(),
            new Server(){}
        ).map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("arguments")
    public void testDefaultStyleSheet(Server server) throws Exception
    {
        try
        {
            server.start();
            assertNotNull(server.getDefaultStyleSheet());
        }
        finally
        {
            server.stop();
        }
    }

    @ParameterizedTest
    @MethodSource("arguments")
    public void testDefaultFavicon(Server server) throws Exception
    {
        try
        {
            server.start();
            assertNotNull(server.getDefaultFavicon());
        }
        finally
        {
            server.stop();
        }
    }
}
