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

package org.eclipse.jetty.cdi.websocket.cdiapp;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.logging.Level;

import javax.enterprise.context.SessionScoped;
import javax.inject.Inject;
import javax.servlet.ServletContext;
import javax.servlet.http.HttpSession;
import javax.websocket.CloseReason;
import javax.websocket.OnClose;
import javax.websocket.OnMessage;
import javax.websocket.OnOpen;
import javax.websocket.Session;
import javax.websocket.server.ServerEndpoint;

@ServerEndpoint("/cdi-info")
public class InfoSocket
{
    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(InfoSocket.class.getName());

    @SessionScoped
    @Inject
    private HttpSession httpSession;

    @Inject
    private ServletContext servletContext;
    
    @Inject
    private DataMaker dataMaker;

    private Session session;
    
    @OnOpen
    public void onOpen(Session session)
    {
        LOG.log(Level.INFO,"onOpen(): {0}",session);
        this.session = session;
    }

    @OnClose
    public void onClose(CloseReason close)
    {
        LOG.log(Level.INFO,"onClose(): {}",close);
        this.session = null;
    }

    @OnMessage
    public String onMessage(String msg)
    {
        StringWriter str = new StringWriter();
        PrintWriter out = new PrintWriter(str);
        
        String args[] = msg.split("\\|");

        switch (args[0])
        {
            case "info":
                out.printf("websocketSession is %s%n",asPresent(session));
                out.printf("httpSession is %s%n",asPresent(httpSession));
                out.printf("servletContext is %s%n",asPresent(servletContext));
                break;
            case "data":
                dataMaker.processMessage(args[1]);
                break;
        }

        return str.toString();
    }

    private String asPresent(Object obj)
    {
        return obj == null ? "NULL" : "PRESENT";
    }
}
