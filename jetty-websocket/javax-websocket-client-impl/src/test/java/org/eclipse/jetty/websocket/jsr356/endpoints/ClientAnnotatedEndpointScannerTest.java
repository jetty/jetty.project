//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

import static org.hamcrest.Matchers.*;

import javax.websocket.CloseReason;
import javax.websocket.DeploymentException;

import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenCloseSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenCloseSocket;
import org.junit.Assert;
import org.junit.Test;

public class ClientAnnotatedEndpointScannerTest
{
    private static ClientContainer container = new ClientContainer();

    private void assertHasCallable(String msg, CallableMethod callable, Class<?>... expectedParameters)
    {
        Assert.assertThat(msg,callable,notNullValue());
        int len = expectedParameters.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> expectedParam = expectedParameters[i];
            Class<?> actualParam = callable.getParamTypes()[i];

            Assert.assertTrue("Parameter[" + i + "] - expected:[" + expectedParam + "], actual:[" + actualParam + "]",actualParam.equals(expectedParam));
        }
    }

    @Test
    public void testScan_BasicOpenClose() throws DeploymentException
    {
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container,BasicOpenCloseSocket.class);
        AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(metadata);
        scanner.scan();

        Assert.assertThat("Metadata",metadata,notNullValue());

        assertHasCallable("Metadata.onOpen",metadata.onOpen);
        assertHasCallable("Metadata.onClose",metadata.onClose,CloseReason.class);
    }

    @Test
    public void testScan_BasicSessionOpenClose() throws DeploymentException
    {
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container,BasicOpenCloseSessionSocket.class);
        AnnotatedEndpointScanner scanner = new AnnotatedEndpointScanner(metadata);
        scanner.scan();

        Assert.assertThat("Metadata",metadata,notNullValue());

        assertHasCallable("Metadata.onOpen",metadata.onOpen);
        assertHasCallable("Metadata.onClose",metadata.onClose,CloseReason.class);
    }
}
