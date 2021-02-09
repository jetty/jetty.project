//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package org.eclipse.jetty.websocket.jsr356.server;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;
import javax.websocket.server.ServerEndpoint;
import javax.websocket.server.ServerEndpointConfig;

import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.common.scopes.SimpleContainerScope;
import org.eclipse.jetty.websocket.common.scopes.WebSocketContainerScope;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorExceptionSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.server.samples.InvalidOpenSessionIntSocket;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Test {@link AnnotatedEndpointScanner} against various simple, 1 method {@link ServerEndpoint} annotated classes with invalid signatures.
 */
public class ServerAnnotatedEndpointScannerInvalidSignaturesTest
{
    public static Stream<Arguments> scenarios()
    {
        List<Class<?>[]> data = new ArrayList<>();

        data.add(new Class<?>[]{InvalidCloseIntSocket.class, OnClose.class});
        data.add(new Class<?>[]{InvalidErrorErrorSocket.class, OnError.class});
        data.add(new Class<?>[]{InvalidErrorExceptionSocket.class, OnError.class});
        data.add(new Class<?>[]{InvalidErrorIntSocket.class, OnError.class});
        data.add(new Class<?>[]{InvalidOpenCloseReasonSocket.class, OnOpen.class});
        data.add(new Class<?>[]{InvalidOpenIntSocket.class, OnOpen.class});
        data.add(new Class<?>[]{InvalidOpenSessionIntSocket.class, OnOpen.class});

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods

        return data.stream().map(Arguments::of);
    }

    @ParameterizedTest
    @MethodSource("scenarios")
    public void testScanInvalidSignature(Class<?> pojo, Class<? extends Annotation> expectedAnnoClass) throws DeploymentException
    {
        WebSocketContainerScope container = new SimpleContainerScope(WebSocketPolicy.newClientPolicy());
        AnnotatedServerEndpointMetadata metadata = new AnnotatedServerEndpointMetadata(container, pojo, null);
        AnnotatedEndpointScanner<ServerEndpoint, ServerEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);

        InvalidSignatureException e = assertThrows(InvalidSignatureException.class, () ->
        {
            scanner.scan();
            // Expected InvalidSignatureException with message that references annotation
        });
        assertThat("Message", e.getMessage(), containsString(expectedAnnoClass.getSimpleName()));
    }
}
