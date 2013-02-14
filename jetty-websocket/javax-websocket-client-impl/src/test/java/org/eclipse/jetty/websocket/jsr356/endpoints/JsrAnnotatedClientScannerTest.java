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

import java.lang.annotation.Annotation;

import javax.websocket.CloseReason;
import javax.websocket.Session;
import javax.websocket.WebSocketOpen;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.websocket.common.events.annotated.CallableMethod;
import org.eclipse.jetty.websocket.common.events.annotated.InvalidSignatureException;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenCloseSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenCloseSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.BasicOpenSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenCloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenIntSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.InvalidOpenSessionIntSocket;
import org.junit.Assert;
import org.junit.Test;

public class JsrAnnotatedClientScannerTest
{
    private static final Logger LOG = Log.getLogger(JsrAnnotatedClientScannerTest.class);

    private void assertHasCallable(String msg, CallableMethod callable, Class<?>... expectedParameters)
    {
        Assert.assertThat(msg,notNullValue());
        int len = expectedParameters.length;
        for (int i = 0; i < len; i++)
        {
            Class<?> expectedParam = expectedParameters[i];
            Class<?> actualParam = callable.getParamTypes()[i];

            Assert.assertTrue("Parameter[" + i + "] - expected:[" + expectedParam + "], actual:[" + actualParam + "]",actualParam.equals(expectedParam));
        }
    }

    private void assertInvalidAnnotationSignature(Class<?> pojo, Class<? extends Annotation> expectedAnnoClass)
    {
        JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(pojo);
        try
        {
            scanner.scan();
            Assert.fail("Expected " + InvalidSignatureException.class + " with message that references " + expectedAnnoClass + " annotation");
        }
        catch (InvalidSignatureException e)
        {
            LOG.debug("{}:{}",e.getClass(),e.getMessage());
            Assert.assertThat("Message",e.getMessage(),containsString(expectedAnnoClass.getSimpleName()));
        }
    }

    @Test
    public void testScan_BasicOpen()
    {
        JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(BasicOpenSocket.class);
        JsrAnnotatedMetadata metadata = scanner.scan();
        Assert.assertThat("Metadata",metadata,notNullValue());
        assertHasCallable("Metadata.onOpen",metadata.onOpen);
    }

    @Test
    public void testScan_BasicOpenClose()
    {
        JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(BasicOpenCloseSocket.class);
        JsrAnnotatedMetadata metadata = scanner.scan();

        Assert.assertThat("Metadata",metadata,notNullValue());

        assertHasCallable("Metadata.onOpen",metadata.onOpen);
        assertHasCallable("Metadata.onClose",metadata.onClose,CloseReason.class);
    }

    @Test
    public void testScan_BasicOpenSession()
    {
        JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(BasicOpenSessionSocket.class);
        JsrAnnotatedMetadata metadata = scanner.scan();
        Assert.assertThat("Metadata",metadata,notNullValue());
        assertHasCallable("Metadata.onOpen",metadata.onOpen,Session.class);
    }

    @Test
    public void testScan_BasicSessionOpenClose()
    {
        JsrAnnotatedClientScanner scanner = new JsrAnnotatedClientScanner(BasicOpenCloseSessionSocket.class);
        JsrAnnotatedMetadata metadata = scanner.scan();

        Assert.assertThat("Metadata",metadata,notNullValue());

        assertHasCallable("Metadata.onOpen",metadata.onOpen);
        assertHasCallable("Metadata.onClose",metadata.onClose,CloseReason.class);
    }

    @Test
    public void testScan_InvalidOpenCloseReason()
    {
        assertInvalidAnnotationSignature(InvalidOpenCloseReasonSocket.class,WebSocketOpen.class);
    }

    @Test
    public void testScan_InvalidOpenInt()
    {
        assertInvalidAnnotationSignature(InvalidOpenIntSocket.class,WebSocketOpen.class);
    }

    @Test
    public void testScan_InvalidOpenSessionInt()
    {
        assertInvalidAnnotationSignature(InvalidOpenSessionIntSocket.class,WebSocketOpen.class);
    }
}
