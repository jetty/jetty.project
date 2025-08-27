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

package org.eclipse.jetty.websocket.common;

import org.eclipse.jetty.websocket.api.exceptions.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.messages.MessageSink;
import org.eclipse.jetty.websocket.core.util.MethodHolder;

public class JettyWebSocketFrameHandlerMetadata extends Configuration.ConfigurationCustomizer
{
    private boolean autoDemand;
    private MethodHolder openHandle;
    private MethodHolder closeHandle;
    private MethodHolder errorHandle;
    private MethodHolder frameHandle;
    private MethodHolder textHandle;
    private Class<? extends MessageSink> textSink;
    private MethodHolder binaryHandle;
    private Class<? extends MessageSink> binarySink;
    private MethodHolder pingHandle;
    private MethodHolder pongHandle;

    public boolean isAutoDemand()
    {
        return autoDemand;
    }

    public void setAutoDemand(boolean autoDemand)
    {
        this.autoDemand = autoDemand;
    }

    public void setBinaryHandle(Class<? extends MessageSink> sinkClass, MethodHolder binary, Object origin)
    {
        assertNotSet(this.binaryHandle, "BINARY Handler", origin);
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        this.binaryHandle = binary;
        this.binarySink = sinkClass;
    }

    public MethodHolder getBinaryHandle()
    {
        return binaryHandle;
    }

    public Class<? extends MessageSink> getBinarySink()
    {
        return binarySink;
    }

    public void setCloseHandle(MethodHolder close, Object origin)
    {
        assertNotSet(this.closeHandle, "CLOSE Handler", origin);
        this.closeHandle = close;
    }

    public MethodHolder getCloseHandle()
    {
        return closeHandle;
    }

    public void setErrorHandle(MethodHolder error, Object origin)
    {
        assertNotSet(this.errorHandle, "ERROR Handler", origin);
        this.errorHandle = error;
    }

    public MethodHolder getErrorHandle()
    {
        return errorHandle;
    }

    public void setFrameHandle(MethodHolder frame, Object origin)
    {
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        assertNotSet(this.textHandle, "TEXT Handler", origin);
        assertNotSet(this.binaryHandle, "BINARY Handler", origin);
        assertNotSet(this.pingHandle, "PING Handler", origin);
        assertNotSet(this.pongHandle, "PONG Handler", origin);
        this.frameHandle = frame;
    }

    public MethodHolder getFrameHandle()
    {
        return frameHandle;
    }

    public void setOpenHandle(MethodHolder openHandle, Object origin)
    {
        assertNotSet(this.openHandle, "OPEN Handler", origin);
        this.openHandle = openHandle;
    }

    public MethodHolder getOpenHandle()
    {
        return openHandle;
    }

    public void setPingHandle(MethodHolder ping, Object origin)
    {
        assertNotSet(this.pingHandle, "PING Handler", origin);
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        this.pingHandle = ping;
    }

    public MethodHolder getPingHandle()
    {
        return pingHandle;
    }

    public void setPongHandle(MethodHolder pong, Object origin)
    {
        assertNotSet(this.pongHandle, "PONG Handler", origin);
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        this.pongHandle = pong;
    }

    public MethodHolder getPongHandle()
    {
        return pongHandle;
    }

    public void setTextHandle(Class<? extends MessageSink> sinkClass, MethodHolder text, Object origin)
    {
        assertNotSet(this.textHandle, "TEXT Handler", origin);
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        this.textHandle = text;
        this.textSink = sinkClass;
    }

    public MethodHolder getTextHandle()
    {
        return textHandle;
    }

    public Class<? extends MessageSink> getTextSink()
    {
        return textSink;
    }

    @SuppressWarnings("Duplicates")
    private void assertNotSet(Object val, String role, Object origin)
    {
        if (val == null)
            return;

        StringBuilder err = new StringBuilder();
        err.append("Cannot replace previously assigned [");
        err.append(role);
        err.append("] at ").append(describeOrigin(val));
        err.append(" with ");
        err.append(describeOrigin(origin));

        throw new InvalidWebSocketException(err.toString());
    }

    private String describeOrigin(Object obj)
    {
        if (obj == null)
        {
            return "<undefined>";
        }

        return obj.toString();
    }
}
