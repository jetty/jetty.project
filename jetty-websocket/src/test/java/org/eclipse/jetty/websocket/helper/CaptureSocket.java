/*******************************************************************************
 * Copyright (c) 2011 Intalio, Inc.
 * ======================================================================
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * and Apache License v2.0 which accompanies this distribution.
 *
 *   The Eclipse Public License is available at
 *   http://www.eclipse.org/legal/epl-v10.html
 *
 *   The Apache License v2.0 is available at
 *   http://www.opensource.org/licenses/apache2.0.php
 *
 * You may elect to redistribute this code under either of these licenses.
 *******************************************************************************/
package org.eclipse.jetty.websocket.helper;

import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.websocket.WebSocket;

public class CaptureSocket implements WebSocket, WebSocket.OnTextMessage
{
    private Connection conn;
    public List<String> messages;

    public CaptureSocket()
    {
        messages = new ArrayList<String>();
    }

    public boolean isConnected()
    {
        if (conn == null)
        {
            return false;
        }
        return conn.isOpen();
    }

    public void onMessage(String data)
    {
        // System.out.printf("Received Message \"%s\" [size %d]%n", data, data.length());
        messages.add(data);
    }

    public void onOpen(Connection connection)
    {
        this.conn = connection;
    }

    public void onClose(int closeCode, String message)
    {
        this.conn = null;
    }
}