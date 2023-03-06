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

package org.eclipse.jetty.websocket.server;

import java.util.List;

import org.eclipse.jetty.server.Request;
import org.eclipse.jetty.websocket.api.ExtensionConfig;

public interface ServerUpgradeRequest extends Request
{
    List<ExtensionConfig> getExtensions();

    List<String> getSubProtocols();

    boolean hasSubProtocol(String subProtocol);
}
