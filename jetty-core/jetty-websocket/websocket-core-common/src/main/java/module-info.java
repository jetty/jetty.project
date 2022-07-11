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

module org.eclipse.jetty.websocket.core.common
{
    requires org.eclipse.jetty.http;
    requires org.slf4j;

    requires transitive org.eclipse.jetty.io;
    requires transitive org.eclipse.jetty.util;

    exports org.eclipse.jetty.websocket.core;
    exports org.eclipse.jetty.websocket.core.exception;

    exports org.eclipse.jetty.websocket.core.internal to
        org.eclipse.jetty.websocket.core.client,
        org.eclipse.jetty.websocket.core.server,
        org.eclipse.jetty.util; // Export to DecoratedObjectFactory.

    // The Jetty & Jakarta API Layers need to access both access some internal utilities which we don't want to expose.
    exports org.eclipse.jetty.websocket.core.internal.util to
        org.eclipse.jetty.ee8.websocket.jetty.common,
        org.eclipse.jetty.ee8.websocket.jetty.client,
        org.eclipse.jetty.ee8.websocket.jetty.server,
        org.eclipse.jetty.ee8.websocket.jakarta.common,
        org.eclipse.jetty.ee8.websocket.jakarta.client,
        org.eclipse.jetty.ee8.websocket.jakarta.server,
        org.eclipse.jetty.ee9.websocket.jetty.common,
        org.eclipse.jetty.ee9.websocket.jetty.client,
        org.eclipse.jetty.ee9.websocket.jetty.server,
        org.eclipse.jetty.ee9.websocket.jakarta.common,
        org.eclipse.jetty.ee9.websocket.jakarta.client,
        org.eclipse.jetty.ee9.websocket.jakarta.server,
        org.eclipse.jetty.ee10.websocket.jetty.common,
        org.eclipse.jetty.ee10.websocket.jetty.client,
        org.eclipse.jetty.ee10.websocket.jetty.server,
        org.eclipse.jetty.ee10.websocket.jakarta.common,
        org.eclipse.jetty.ee10.websocket.jakarta.client,
        org.eclipse.jetty.ee10.websocket.jakarta.server;

    exports org.eclipse.jetty.websocket.core.internal.messages to
        org.eclipse.jetty.ee8.websocket.jetty.common,
        org.eclipse.jetty.ee8.websocket.jetty.client,
        org.eclipse.jetty.ee8.websocket.jetty.server,
        org.eclipse.jetty.ee8.websocket.jakarta.common,
        org.eclipse.jetty.ee8.websocket.jakarta.client,
        org.eclipse.jetty.ee8.websocket.jakarta.server,
        org.eclipse.jetty.ee9.websocket.jetty.common,
        org.eclipse.jetty.ee9.websocket.jetty.client,
        org.eclipse.jetty.ee9.websocket.jetty.server,
        org.eclipse.jetty.ee9.websocket.jakarta.common,
        org.eclipse.jetty.ee9.websocket.jakarta.client,
        org.eclipse.jetty.ee9.websocket.jakarta.server,
        org.eclipse.jetty.ee10.websocket.jetty.common,
        org.eclipse.jetty.ee10.websocket.jetty.client,
        org.eclipse.jetty.ee10.websocket.jetty.server,
        org.eclipse.jetty.ee10.websocket.jakarta.common,
        org.eclipse.jetty.ee10.websocket.jakarta.client,
        org.eclipse.jetty.ee10.websocket.jakarta.server;

    uses org.eclipse.jetty.websocket.core.Extension;
    
    provides org.eclipse.jetty.websocket.core.Extension with
        org.eclipse.jetty.websocket.core.internal.FragmentExtension,
        org.eclipse.jetty.websocket.core.internal.IdentityExtension,
        org.eclipse.jetty.websocket.core.internal.PerMessageDeflateExtension,
        org.eclipse.jetty.websocket.core.internal.ValidationExtension;
}
