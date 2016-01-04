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

import static org.hamcrest.Matchers.is;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import javax.websocket.ClientEndpoint;
import javax.websocket.ClientEndpointConfig;

import org.eclipse.jetty.toolchain.test.EventQueue;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.api.WebSocketPolicy;
import org.eclipse.jetty.websocket.common.CloseInfo;
import org.eclipse.jetty.websocket.common.events.EventDriver;
import org.eclipse.jetty.websocket.jsr356.ClientContainer;
import org.eclipse.jetty.websocket.jsr356.annotations.AnnotatedEndpointScanner;
import org.eclipse.jetty.websocket.jsr356.annotations.JsrEvents;
import org.eclipse.jetty.websocket.jsr356.client.AnnotatedClientEndpointMetadata;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseEndpointConfigSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSessionReasonSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSessionSocket;
import org.eclipse.jetty.websocket.jsr356.endpoints.samples.close.CloseSocket;
import org.junit.Assert;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.junit.runners.Parameterized;
import org.junit.runners.Parameterized.Parameters;

@RunWith(Parameterized.class)
public class OnCloseTest
{
    private static class Case
    {
        public static Case add(List<Case[]> data, Class<?> closeClass)
        {
            Case tcase = new Case();
            tcase.closeClass = closeClass;
            data.add(new Case[]
            { tcase });
            return tcase;
        }

        Class<?> closeClass;
        String expectedCloseEvent;

        public Case expect(String expectedEvent)
        {
            this.expectedCloseEvent = expectedEvent;
            return this;
        }
    }

    private static ClientContainer container = new ClientContainer();

    @Parameters
    public static Collection<Case[]> data() throws Exception
    {
        List<Case[]> data = new ArrayList<>();

        Case.add(data,CloseSocket.class).expect("onClose()");
        Case.add(data,CloseReasonSocket.class).expect("onClose(CloseReason)");
        Case.add(data,CloseSessionSocket.class).expect("onClose(Session)");
        Case.add(data,CloseReasonSessionSocket.class).expect("onClose(CloseReason,Session)");
        Case.add(data,CloseSessionReasonSocket.class).expect("onClose(Session,CloseReason)");
        Case.add(data,CloseEndpointConfigSocket.class).expect("onClose(EndpointConfig)");

        return data;
    }

    private final Case testcase;

    public OnCloseTest(Case testcase)
    {
        this.testcase = testcase;
        System.err.printf("Testing @OnClose for %s%n",testcase.closeClass.getName());
    }

    @Test
    public void testOnCloseCall() throws Exception
    {
        // Scan annotations
        AnnotatedClientEndpointMetadata metadata = new AnnotatedClientEndpointMetadata(container,testcase.closeClass);
        AnnotatedEndpointScanner<ClientEndpoint, ClientEndpointConfig> scanner = new AnnotatedEndpointScanner<>(metadata);
        scanner.scan();

        // Build up EventDriver
        WebSocketPolicy policy = WebSocketPolicy.newClientPolicy();
        ClientEndpointConfig config = metadata.getConfig();
        TrackingSocket endpoint = (TrackingSocket)testcase.closeClass.newInstance();
        EndpointInstance ei = new EndpointInstance(endpoint,config,metadata);
        JsrEvents<ClientEndpoint, ClientEndpointConfig> jsrevents = new JsrEvents<>(metadata);

        EventDriver driver = new JsrAnnotatedEventDriver(policy,ei,jsrevents);

        // Execute onClose call
        driver.onClose(new CloseInfo(StatusCode.NORMAL,"normal"));

        // Test captured event
        EventQueue<String> events = endpoint.eventQueue;
        Assert.assertThat("Number of Events Captured",events.size(),is(1));
        String closeEvent = events.poll();
        Assert.assertThat("Close Event",closeEvent,is(testcase.expectedCloseEvent));
    }
}
