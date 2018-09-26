//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.websocket.tests.server;

import java.io.File;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.eclipse.jetty.toolchain.test.FS;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.websocket.api.StatusCode;
import org.eclipse.jetty.websocket.core.CloseStatus;
import org.eclipse.jetty.websocket.core.Frame;
import org.eclipse.jetty.websocket.core.OpCode;
import org.eclipse.jetty.websocket.tests.Fuzzer;
import org.eclipse.jetty.websocket.tests.LocalServer;
import org.junit.jupiter.api.AfterEach;

import static org.hamcrest.CoreMatchers.is;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.containsString;

public abstract class WebSocketUpgradeFilterTest
{
    private static AtomicInteger uniqTestDirId = new AtomicInteger(0);
    
    protected static File getNewTestDir()
    {
        File testDir = MavenTestingUtils.getTargetTestingDir("WSUF-webxml-" + uniqTestDirId.getAndIncrement());
        FS.ensureDirExists(testDir);
        return testDir;
    }

    private LocalServer server;
    
    @AfterEach
    public void stopServer() throws Exception
    {
        server.stop();
    }

    public void testNormalConfiguration(LocalServer server) throws Exception
    {
        this.server = server;
        this.server.start();

        try (Fuzzer session = server.newNetworkFuzzer("/info/"))
        {
            session.sendFrames(
                    new Frame(OpCode.TEXT).setPayload("hello"),
                    CloseStatus.toFrame(StatusCode.NORMAL.getCode())
            );
            
            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }


    public void testStopStartOfHandler(LocalServer server) throws Exception
    {
        this.server = server;
        this.server.start();

        try (Fuzzer session = server.newNetworkFuzzer("/info/"))
        {
            session.sendFrames(
                    new Frame(OpCode.TEXT).setPayload("hello 1"),
                    CloseStatus.toFrame(StatusCode.NORMAL.getCode())
            );
            
            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
        
        server.getServletContextHandler().stop();
        server.getServletContextHandler().start();
        
        // Make request again (server should have retained websocket configuration)
        
        try (Fuzzer session = server.newNetworkFuzzer("/info/"))
        {
            session.sendFrames(
                    new Frame(OpCode.TEXT).setPayload("hello 2"),
                    CloseStatus.toFrame(StatusCode.NORMAL.getCode())
            );
            
            BlockingQueue<Frame> framesQueue = session.getOutputFrames();
            Frame frame = framesQueue.poll(1, TimeUnit.SECONDS);
            
            assertThat("Frame.opCode", frame.getOpCode(), is(OpCode.TEXT));
            
            // If we can connect and send a text message, we know that the endpoint was
            // added properly, and the response will help us verify the policy configuration too
            assertThat("Frame.text-payload", frame.getPayloadAsUTF8(), containsString("session.maxTextMessageSize=" + (10 * 1024 * 1024)));
        }
    }
}
