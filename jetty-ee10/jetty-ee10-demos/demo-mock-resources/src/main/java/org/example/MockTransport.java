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

package org.example;

import jakarta.mail.Address;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Session;
import jakarta.mail.Transport;
import jakarta.mail.URLName;

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

    @Override
    public void sendMessage(Message arg0, Address[] arg1) throws MessagingException
    {
        System.err.println("Sending message");
    }
}
