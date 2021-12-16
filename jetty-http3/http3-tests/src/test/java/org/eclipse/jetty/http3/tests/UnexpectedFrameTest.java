//
// ========================================================================
// Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.http3.tests;

import java.nio.ByteBuffer;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import org.eclipse.jetty.http3.api.Session;
import org.eclipse.jetty.http3.frames.DataFrame;
import org.eclipse.jetty.http3.internal.ErrorCode;
import org.eclipse.jetty.http3.internal.HTTP3Session;
import org.eclipse.jetty.util.Callback;
import org.junit.jupiter.api.Test;

import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class UnexpectedFrameTest extends AbstractClientServerTest
{
    @Test
    public void testDataBeforeHeaders() throws Exception
    {
        CountDownLatch serverLatch = new CountDownLatch(1);
        start(new Session.Server.Listener()
        {
            @Override
            public void onSessionFailure(Session session, long error, String reason)
            {
                assertEquals(ErrorCode.FRAME_UNEXPECTED_ERROR.code(), error);
                serverLatch.countDown();
            }
        });

        CountDownLatch clientLatch = new CountDownLatch(1);
        HTTP3Session session = (HTTP3Session)newSession(new Session.Client.Listener()
            {
                @Override
                public void onSessionFailure(Session session, long error, String reason)
                {
                    assertEquals(ErrorCode.FRAME_UNEXPECTED_ERROR.code(), error);
                    clientLatch.countDown();
                }
            });

        session.writeMessageFrame(0, new DataFrame(ByteBuffer.allocate(128), false), Callback.NOOP);

        assertTrue(serverLatch.await(5, TimeUnit.SECONDS));
        assertTrue(clientLatch.await(5, TimeUnit.SECONDS));

        await().atMost(1, TimeUnit.SECONDS).until(session::isClosed);
    }
}
