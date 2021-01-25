//
//  ========================================================================
//  Copyright (c) 1995-2021 Mort Bay Consulting Pty Ltd and others.
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

package com.acme;

import javax.mail.Address;
import javax.mail.Message;
import javax.mail.MessagingException;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.URLName;

/**
 * MockTransport
 */
public class MockTransport extends Transport
{
    /**
     *
     */
    public MockTransport(Session session, URLName urlname)
    {
        super(session, urlname);
    }

    /**
     * @see javax.mail.Transport#sendMessage(javax.mail.Message, javax.mail.Address[])
     */
    @Override
    public void sendMessage(Message arg0, Address[] arg1) throws MessagingException
    {
        System.err.println("Sending message");
    }
}
