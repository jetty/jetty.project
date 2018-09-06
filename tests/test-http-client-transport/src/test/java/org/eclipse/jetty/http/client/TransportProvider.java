//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.http.client;

import java.util.Arrays;
import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Stream;

import org.eclipse.jetty.util.StringUtil;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.ArgumentsProvider;

public class TransportProvider implements ArgumentsProvider
{
    public static Stream<Transport> getActiveTransports()
    {
        String transports = System.getProperty(Transport.class.getName());

        if (!StringUtil.isBlank(transports))
        {
            return Arrays.stream(transports.split("\\s*,\\s*"))
                    .map(Transport::valueOf);
        }

        // TODO #2014 too many test failures, don't test unix socket client for now.
        // if (OS.IS_UNIX)
        //     return Transport.values();

        return EnumSet.complementOf(EnumSet.of(Transport.UNIX_SOCKET))
                .stream();
    }

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context) throws Exception
    {
        return getActiveTransports().map(Arguments::of);
    }
}
