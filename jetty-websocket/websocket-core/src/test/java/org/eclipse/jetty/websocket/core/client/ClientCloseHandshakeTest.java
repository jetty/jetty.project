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

package org.eclipse.jetty.websocket.core.client;

import org.junit.Test;

/**
 * WebSocket Client based CLOSE scenarios as identified by Simone and Joakim
 */
public class ClientCloseHandshakeTest
{
    /**
     * Client Initiated - no data
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     TCP Connect             TCP Accept
     *     WS Handshake Request >
     *                           < WS Handshake Response
     *     OnOpen()                OnOpen()
     *     close(Normal)
     *     send:Close/Normal >
     *                             OnFrame(Close)
     *                             OnClose(normal)
     *                               exit onClose()
     *                           < send:Close/Normal
     *     OnFrame(Close)
     *     OnClose(normal)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_NoData() throws Exception
    {

    }

    /**
     * Client Initiated - no data - server supplied alternate close code
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     TCP Connect             TCP Accept
     *     WS Handshake Request >
     *                           < WS Handshake Response
     *     OnOpen()                OnOpen()
     *     close(Normal)
     *     send:Close/Normal >
     *                             OnFrame(Close)
     *                             OnClose(normal)
     *                               close(Shutdown)
     *                             < send:Close/Shutdown (queue)
     *                                  cb.success() -> disconnect()
     *                               exit onClose()
     *     OnFrame(Close)
     *     OnClose(Shutdown)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClientInitiated_NoData_ChangeClose() throws Exception
    {

    }

    /**
     * Server Initiated - no data
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *                             close(Normal)
     *                           < send(Close/Normal)
     *     OnFrame(Close)
     *     OnClose(normal)
     *       exit onClose()
     *     send:Close/Normal)    >
     *                             OnFrame(Close)
     *     disconnect()            OnClose(normal)
     *                             disconnect()
     * </pre>
     */
    @Test
    public void testServerInitiated_NoData() throws Exception
    {

    }

    /**
     * Client Read IOException
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *                             disconnect - (accidental)
     *                             conn.onError(EOF)
     *                             OnClose(ABNORMAL)
     *                             disconnect()
     *     read -> IOException
     *     conn.onError(IOException)
     *     OnClose(ABNORMAL)
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_Read_IOException()
    {
        // TODO: somehow?
    }

    /**
     * Client Idle Timeout
     * <pre>
     *     Client                  Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ...(some time later)...
     *     onIdleTimeout()
     *     conn.onError(TimeoutException)
     *     send:Close/Shutdown  >
     *                             OnFrame(Close)
     *                             OnClose(Shutdown)
     *                               exit onClose()
     *                           < send(Close/Shutdown)
     *     OnFrame(Close)
     *     OnClose(Shutdown)       disconnect()
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_IdleTimeout() throws Exception
    {

    }

    /**
     * Client ProtocolViolation
     * <pre>
     *     Bad Client              Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *     send:Text(BadFormat)
     *                             close(ProtocolViolation)
     *                             send(Close/ProtocolViolation)
     *     OnFrame(Close)          disconnect()
     *     OnClose(ProtocolViolation)
     *     send(Close/ProtocolViolation) >  FAILS
     *     disconnect()
     * </pre>
     */
    @Test
    public void testClient_ProtocolViolation_Received() throws Exception
    {

    }

    /**
     * Client Exception during Write
     * <pre>
     *     Bad Client              Server
     *     ----------              ----------
     *     Connect                 Accept
     *     Handshake Request >
     *                           < Handshake Response
     *     OnOpen                  OnOpen
     *     ....
     *     send:Text()
     *     write -> IOException
     *     conn.onError(IOException)
     *     OnClose(Shutdown)
     *     disconnect()
     *                             read -> IOException
     *                             conn.onError(IOException)
     *                             OnClose(ABNORMAL)
     *                             disconnect()
     * </pre>
     */
    @Test
    public void testWriteException() throws Exception
    {

    }
}
