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

package org.eclipse.jetty.ee10.websocket.common;

import java.lang.invoke.MethodHandle;

import org.eclipse.jetty.ee10.websocket.api.BatchMode;
import org.eclipse.jetty.ee10.websocket.api.exceptions.InvalidWebSocketException;
import org.eclipse.jetty.websocket.core.Configuration;
import org.eclipse.jetty.websocket.core.internal.messages.MessageSink;

public class JettyWebSocketFrameHandlerMetadata extends Configuration.ConfigurationCustomizer
{
    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;

    private MethodHandle frameHandle;

    private MethodHandle textHandle;
    private Class<? extends MessageSink> textSink;
    private MethodHandle binaryHandle;
    private Class<? extends MessageSink> binarySink;

    private MethodHandle pingHandle;
    private MethodHandle pongHandle;

    // Batch Configuration
    private BatchMode batchMode = BatchMode.OFF;

    public void setBatchMode(BatchMode batchMode)
    {
        this.batchMode = batchMode;
    }

    public BatchMode getBatchMode()
    {
        return batchMode;
    }

    public void setBinaryHandle(Class<? extends MessageSink> sinkClass, MethodHandle binary, Object origin)
    {
        assertNotSet(this.binaryHandle, "BINARY Handler", origin);
        this.binaryHandle = binary;
        this.binarySink = sinkClass;
    }

    public MethodHandle getBinaryHandle()
    {
        return binaryHandle;
    }

    public Class<? extends MessageSink> getBinarySink()
    {
        return binarySink;
    }

    public void setCloseHandler(MethodHandle close, Object origin)
    {
        assertNotSet(this.closeHandle, "CLOSE Handler", origin);
        this.closeHandle = close;
    }

    public MethodHandle getCloseHandle()
    {
        return closeHandle;
    }

    public void setErrorHandler(MethodHandle error, Object origin)
    {
        assertNotSet(this.errorHandle, "ERROR Handler", origin);
        this.errorHandle = error;
    }

    public MethodHandle getErrorHandle()
    {
        return errorHandle;
    }

    public void setFrameHandler(MethodHandle frame, Object origin)
    {
        assertNotSet(this.frameHandle, "FRAME Handler", origin);
        this.frameHandle = frame;
    }

    public MethodHandle getFrameHandle()
    {
        return frameHandle;
    }

    public void setOpenHandler(MethodHandle open, Object origin)
    {
        assertNotSet(this.openHandle, "OPEN Handler", origin);
        this.openHandle = open;
    }

    public MethodHandle getOpenHandle()
    {
        return openHandle;
    }

    public void setPingHandle(MethodHandle ping, Object origin)
    {
        assertNotSet(this.pingHandle, "PING Handler", origin);
        this.pingHandle = ping;
    }

    public MethodHandle getPingHandle()
    {
        return pingHandle;
    }

    public void setPongHandle(MethodHandle pong, Object origin)
    {
        assertNotSet(this.pongHandle, "PONG Handler", origin);
        this.pongHandle = pong;
    }

    public MethodHandle getPongHandle()
    {
        return pongHandle;
    }

    public void setTextHandler(Class<? extends MessageSink> sinkClass, MethodHandle text, Object origin)
    {
        assertNotSet(this.textHandle, "TEXT Handler", origin);
        this.textHandle = text;
        this.textSink = sinkClass;
    }

    public MethodHandle getTextHandle()
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
