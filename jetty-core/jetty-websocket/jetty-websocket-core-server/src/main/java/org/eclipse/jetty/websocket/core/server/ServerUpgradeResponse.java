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

package org.eclipse.jetty.websocket.core.server;

import java.util.List;

import org.eclipse.jetty.server.Response;
import org.eclipse.jetty.websocket.core.ExtensionConfig;

/**
 * Upgrade response used for websocket negotiation.
 * <p>
 * Allows setting of extensions and subprotocol without using headers directly.
 */
public interface ServerUpgradeResponse extends Response
{
    String getAcceptedSubProtocol();

    void setAcceptedSubProtocol(String protocol);

    List<ExtensionConfig> getExtensions();

    void addExtensions(List<ExtensionConfig> configs);

    void removeExtensions(List<ExtensionConfig> configs);

    void setExtensions(List<ExtensionConfig> configs);
}
