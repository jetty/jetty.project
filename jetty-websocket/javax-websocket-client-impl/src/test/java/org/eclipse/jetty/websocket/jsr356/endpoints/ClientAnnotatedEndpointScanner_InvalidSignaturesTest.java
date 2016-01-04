//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.jsr356.endpoints;

import static org.hamcrest.Matchers.containsString;

import java.lang.annotation.Annotation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;
import javax.websocket.DeploymentException;
import javax.websocket.OnClose;
import javax.websocket.OnError;
import javax.websocket.OnOpen;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidCloseIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorErrorSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorExceptionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidErrorIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenSessionIntSocket;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

/**
 * Test {@link AnnotatedEndpointScanner} against various simple, 1 method, {@link ClientEndpoint} annotated classes with invalid signatures.
 */
@RunWith(Parameterized.class)
public class ClientAnnotatedEndpointScanner_InvalidSignaturesTest
{
    private static final Logger LOG = Log.getLogger(ClientAnnotatedEndpointScanner_InvalidSignaturesTest.class);
    private static ClientContainer container = new ClientContainer();

    @Parameters
    public static Collection<Class<?>[]> data()
    {
        List<Class<?>[]> data = new ArrayList<>();

        // @formatter:off
        data.add(new Class<?>[]{ InvalidCloseIntSocket.class, OnClose.class });
        data.add(new Class<?>[]{ InvalidErrorErrorSocket.class, OnError.class });
        data.add(new Class<?>[]{ InvalidErrorExceptionSocket.class, OnError.class });
        data.add(new Class<?>[]{ InvalidErrorIntSocket.class, OnError.class });
        data.add(new Class<?>[]{ InvalidOpenCloseReasonSocket.class, OnOpen.class });
        data.add(new Class<?>[]{ InvalidOpenIntSocket.class, OnOpen.class });
        data.add(new Class<?>[]{ InvalidOpenSessionIntSocket.class, OnOpen.class });
        // @formatter:on

        // TODO: invalid return types
        // TODO: static methods
        // TODO: private or protected methods
        // TODO: abstract methods

        return data;
    }

    // The pojo to test
    private Class<?> pojo;
    // The annotation class expected to be mentioned in the error message
    private Class<? extends Annotation> expectedAnnoClass;

    public ClientAnnotatedEndpointScanner_InvalidSignaturesTest(Class<?> pojo, Class<? extends Annotation> expectedAnnotation)
    {
        this.pojo = pojo;
        this.expectedAnnoClass = expectedAnnotation;
    }

    @Test
    public void testScan_InvalidSignature() throws DeploymentException
    {
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container,pojo);
        AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        try
        {
            scanner.scan();
            Assert.fail("Expected " + InvalidSignatureException.class + " with message that references " + expectedAnnoClass + " annotation");
        }
        catch (InvalidSignatureException e)
        {
            if (LOG.isDebugEnabled())
                LOG.debug("{}:{}",e.getClass(),e.getMessage());
            Assert.assertThat("Message",e.getMessage(),containsString(expectedAnnoClass.getSimpleName()));
        }
    }
}
