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
    exports org.eclipse.jetty.websocket.core.messages;
    exports org.eclipse.jetty.websocket.core.util;
    exports org.eclipse.jetty.websocket.core.internal to
        org.eclipse.jetty.util; // Export to DecoratedObjectFactory.

    uses org.eclipse.jetty.websocket.core.Extension;
    
    provides org.eclipse.jetty.websocket.core.Extension with
        org.eclipse.jetty.websocket.core.internal.FragmentExtension,
        org.eclipse.jetty.websocket.core.internal.IdentityExtension,
        org.eclipse.jetty.websocket.core.internal.PerMessageDeflateExtension,
        org.eclipse.jetty.websocket.core.internal.ValidationExtension;
}
