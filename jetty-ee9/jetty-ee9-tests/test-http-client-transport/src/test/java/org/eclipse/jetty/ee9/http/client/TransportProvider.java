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

package org.eclipse.jetty.ee9.http.client;

import java.util.Arrays;
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
            return Arrays.stream(transports.split("\\s*,\\s*")).map(Transport::valueOf);

        return Arrays.stream(Transport.values());
    }

    @Override
    public Stream<? extends Arguments> provideArguments(ExtensionContext context)
    {
        return getActiveTransports().map(Arguments::of);
    }
}
