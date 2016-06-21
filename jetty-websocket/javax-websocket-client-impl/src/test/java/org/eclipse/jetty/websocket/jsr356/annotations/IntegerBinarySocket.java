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

package org.eclipse.jetty.websocket.jsr356.annotations;

import java.io.IOException;
import java.util.LinkedList;
import java.util.concurrent.TimeoutException;

import javax.websocket.ClientEndpoint;
import javax.websocket.OnError;
import javax.websocket.OnMessage;
import javax.websocket.Session;

import org.eclipse.jetty.websocket.jsr356.decoders.BinaryStreamIntegerDecoder;

@ClientEndpoint(decoders =
{ BinaryStreamIntegerDecoder.class })
public class IntegerBinarySocket
{
    public LinkedList<Throwable> errors = new LinkedList<>();
    private LinkedList<Integer> received = new LinkedList<>();

    public Integer popReceived(long ms) throws TimeoutException {
    	long togo = ms;
    	synchronized(received) {
    		while (togo > 0 && received.isEmpty()) {
    			long start = System.currentTimeMillis();
    			try {
    				received.wait(togo);
    			}
    			catch (InterruptedException e) {
    				throw new RuntimeException("Wait for reception interrupted: " + e.toString(), e);
    			}
    			long waited = System.currentTimeMillis() - start;
    			togo -= waited;
    		}
    		
    		if (received.isEmpty()) {
    			throw new TimeoutException("Did not receive any integers after " + ms + "ms");
    		}
    		
			return received.pop();
    	}
    }
    
    @OnMessage
    public void onMessage(Integer i) throws IOException
    {
    	synchronized(received) {
    		received.push(i);
    		received.notifyAll();
    	}
    }

    @OnError
    public void onError(Session session, Throwable thr)
    {
    	System.err.println("Error on session " + session.getId() + ": " + thr.toString());
        errors.add(thr);
    }
}
