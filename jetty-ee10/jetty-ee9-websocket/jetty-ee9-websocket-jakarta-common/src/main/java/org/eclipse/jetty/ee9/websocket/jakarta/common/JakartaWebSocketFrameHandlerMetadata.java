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

package org.eclipse.jetty.ee9.websocket.jakarta.common;

import java.lang.invoke.MethodHandle;

import jakarta.websocket.Encoder;
import jakarta.websocket.EndpointConfig;
import org.eclipse.jetty.ee9.websocket.jakarta.common.decoders.AvailableDecoders;
import org.eclipse.jetty.ee9.websocket.jakarta.common.encoders.AvailableEncoders;
import org.eclipse.jetty.http.pathmap.UriTemplatePathSpec;
import org.eclipse.jetty.websocket.core.WebSocketComponents;
import org.eclipse.jetty.websocket.core.exception.InvalidWebSocketException;

public class JakartaWebSocketFrameHandlerMetadata
{
    private static final String[] NO_VARIABLES = new String[0];

    // EndpointConfig entries
    private final AvailableDecoders availableDecoders;
    private final AvailableEncoders availableEncoders;

    private MethodHandle openHandle;
    private MethodHandle closeHandle;
    private MethodHandle errorHandle;
    private MethodHandle pongHandle;
    private JakartaWebSocketMessageMetadata textMetadata;
    private JakartaWebSocketMessageMetadata binaryMetadata;

    /**
     * For {@code @ServerEndpoint} or {@code ServerEndpointConfig} based endpoints, this
     * holds the {@link UriTemplatePathSpec} based off of the configured url-pattern.
     *
     * <p>
     * This is the source of the configured uri-template variables names, as well as the parser
     * for the incoming URI to obtain the values for those variables.
     * </p>
     * <p>
     * Used to bind uri-template variables, with their values from the upgrade, to the methods
     * that have declared their interest in these values via {@code @PathParam} annotations.
     * </p>
     * <p>
     * The parsed values from the {@link UriTemplatePathSpec} are returned as a Map
     * of String names to String values.
     * </p>
     * <p>
     * This Metadata Object does not keep track of the parsed values, as they are request
     * specific.
     * </p>
     * <p>
     * This can be null if Metadata is from a client endpoint, or the server endpoint
     * doesn't use uri-template variables.
     * </p>
     */
    private UriTemplatePathSpec uriTemplatePathSpec;

    public JakartaWebSocketFrameHandlerMetadata(EndpointConfig endpointConfig, WebSocketComponents components)
    {
        this.availableDecoders = new AvailableDecoders(endpointConfig, components);
        this.availableEncoders = new AvailableEncoders(endpointConfig, components);
    }

    public AvailableDecoders getAvailableDecoders()
    {
        return availableDecoders;
    }

    public AvailableEncoders getAvailableEncoders()
    {
        return availableEncoders;
    }

    public JakartaWebSocketMessageMetadata getBinaryMetadata()
    {
        return binaryMetadata;
    }

    public MethodHandle getCloseHandle()
    {
        return closeHandle;
    }

    public MethodHandle getErrorHandle()
    {
        return errorHandle;
    }

    public MethodHandle getOpenHandle()
    {
        return openHandle;
    }

    public void setUriTemplatePathSpec(UriTemplatePathSpec pathSpec)
    {
        this.uriTemplatePathSpec = pathSpec;
    }

    public UriTemplatePathSpec getUriTemplatePathSpec()
    {
        return uriTemplatePathSpec;
    }

    public String[] getNamedTemplateVariables()
    {
        if (uriTemplatePathSpec != null)
            return uriTemplatePathSpec.getVariables();
        return NO_VARIABLES;
    }

    public MethodHandle getPongHandle()
    {
        return pongHandle;
    }

    public JakartaWebSocketMessageMetadata getTextMetadata()
    {
        return textMetadata;
    }

    public boolean hasBinaryMetadata()
    {
        return (binaryMetadata != null);
    }

    public boolean hasTextMetdata()
    {
        return (textMetadata != null);
    }

    public void setBinaryMetadata(JakartaWebSocketMessageMetadata metadata, Object origin)
    {
        assertNotSet(this.binaryMetadata, "BINARY Message Metadata", origin);
        this.binaryMetadata = metadata;
    }

    public void setCloseHandler(MethodHandle close, Object origin)
    {
        assertNotSet(this.closeHandle, "CLOSE Handler", origin);
        this.closeHandle = close;
    }

    public void setEncoders(Class<? extends Encoder>[] encoders)
    {
        this.availableEncoders.registerAll(encoders);
    }

    public void setErrorHandler(MethodHandle error, Object origin)
    {
        assertNotSet(this.errorHandle, "ERROR Handler", origin);
        this.errorHandle = error;
    }

    public void setOpenHandler(MethodHandle open, Object origin)
    {
        assertNotSet(this.openHandle, "OPEN Handler", origin);
        this.openHandle = open;
    }

    public void setPongHandle(MethodHandle pong, Object origin)
    {
        assertNotSet(this.pongHandle, "PONG Handler", origin);
        this.pongHandle = pong;
    }

    public void setTextMetadata(JakartaWebSocketMessageMetadata metadata, Object origin)
    {
        assertNotSet(this.textMetadata, "TEXT Messsage Metadata", origin);
        this.textMetadata = metadata;
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
