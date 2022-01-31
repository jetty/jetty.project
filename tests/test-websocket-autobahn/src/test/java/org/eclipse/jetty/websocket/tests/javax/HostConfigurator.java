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

package org.eclipse.jetty.websocket.tests.javax;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.http.HttpHeader;

public class HostConfigurator extends ClientEndpointConfig.Configurator
{
    @Override
    public void beforeRequest(Map<String, List<String>> headers)
    {
        headers.put(HttpHeader.HOST.asString(), Collections.singletonList("localhost:9001"));
    }
}
