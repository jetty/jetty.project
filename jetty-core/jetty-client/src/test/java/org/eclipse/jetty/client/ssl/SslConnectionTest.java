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

package org.eclipse.jetty.client.ssl;

import java.io.File;
import java.nio.ByteBuffer;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.SSLHandshakeException;

import org.eclipse.jetty.io.AbstractConnection;
import org.eclipse.jetty.io.ArrayByteBufferPool;
import org.eclipse.jetty.io.ByteArrayEndPoint;
import org.eclipse.jetty.io.ByteBufferPool;
import org.eclipse.jetty.io.EndPoint;
import org.eclipse.jetty.io.ssl.SslConnection;
import org.eclipse.jetty.toolchain.test.MavenTestingUtils;
import org.eclipse.jetty.util.BufferUtil;
import org.eclipse.jetty.util.ssl.SslContextFactory;
import org.eclipse.jetty.util.thread.QueuedThreadPool;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertThrows;

public class SslConnectionTest
{
    @Test
    public void testSslConnectionClosedBeforeFill() throws Exception
    {
        File keyStore = MavenTestingUtils.getTestResourceFile("keystore.p12");
        SslContextFactory sslContextFactory = new SslContextFactory.Server();
        sslContextFactory.setKeyStorePath(keyStore.getAbsolutePath());
        sslContextFactory.setKeyStorePassword("storepwd");
        sslContextFactory.start();

        ByteBufferPool bufferPool = new ArrayByteBufferPool();
        QueuedThreadPool threadPool = new QueuedThreadPool();
        threadPool.start();
        ByteArrayEndPoint endPoint = new ByteArrayEndPoint();
        SSLEngine sslEngine = sslContextFactory.newSSLEngine();
        sslEngine.setUseClientMode(false);
        SslConnection sslConnection = new SslConnection(bufferPool, threadPool, endPoint, sslEngine);
        EndPoint sslEndPoint = sslConnection.getSslEndPoint();
        sslEndPoint.setConnection(new AbstractConnection(sslEndPoint, threadPool)
        {
            @Override
            public void onFillable()
            {
            }
        });

        // There are no bytes in the endPoint, so we fill zero.
        // However, this will trigger state changes in SSLEngine
        // that will later cause it to throw ISE("Internal error").
        sslEndPoint.fill(BufferUtil.EMPTY_BUFFER);

        // Close the connection before filling.
        sslEndPoint.shutdownOutput();

        // Put some bytes in the endPoint to trigger
        // the required state changes in SSLEngine.
        byte[] bytes = new byte[]{0x16, 0x03, 0x03, 0x00, 0x00};
        endPoint.addInput(ByteBuffer.wrap(bytes));

        // This attempt to read, if not guarded, throws ISE("Internal error").
        // We want SSLHandshakeException to be thrown instead, because it is
        // handled better (it is an IOException) by the Connection code that
        // reads from the EndPoint.
        assertThrows(SSLHandshakeException.class, () -> sslEndPoint.fill(BufferUtil.EMPTY_BUFFER));
    }
}
