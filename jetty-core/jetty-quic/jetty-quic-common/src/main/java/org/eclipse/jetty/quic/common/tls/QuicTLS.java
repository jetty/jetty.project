//
// ========================================================================
// Copyright (c) 1995 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under the
// terms of the Eclipse Public License v. 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0, or the Apache License, Version 2.0
// which is available at https://www.apache.org/licenses/LICENSE-2.0.
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.quic.common.tls;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.List;

import org.eclipse.jetty.quic.common.EncryptionLevel;
import org.eclipse.jetty.quic.common.internal.Decrypter;
import org.eclipse.jetty.quic.common.internal.Encrypter;
import org.eclipse.jetty.tls.Message;
import org.eclipse.jetty.tls.common.generator.MessageGenerator;
import org.eclipse.jetty.util.Callback;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class QuicTLS implements Encrypter, Decrypter, MessageGenerator.Listener
{
    private static final Logger LOG = LoggerFactory.getLogger(QuicTLS.class);

    private final List<Message.Listener> listeners = new ArrayList<>();
    private final SecureRandom random = new SecureRandom();
    private EncryptionLevel encryptionLevel;

    public void addMessageListener(Message.Listener listener)
    {
        listeners.add(listener);
    }

    public EncryptionLevel getEncryptionLevel()
    {
        return encryptionLevel;
    }

    protected void setEncryptionLevel(EncryptionLevel encryptionLevel)
    {
        this.encryptionLevel = encryptionLevel;
    }

    protected void notifyMessages(List<Message> messages, Callback callback)
    {
        for (Message.Listener listener : listeners)
        {
            try
            {
                listener.onMessages(messages, callback);
            }
            catch (Throwable x)
            {
                LOG.atInfo().setCause(x).log("failure while notifying listener {}", listener);
            }
        }
    }

    public byte[] newRandomBytes(int length)
    {
        byte[] bytes = new byte[length];
        random.nextBytes(bytes);
        return bytes;
    }

}
