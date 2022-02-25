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

package org.eclipse.jetty.session.infinispan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;
import java.util.Map;

import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.infinispan.commons.marshall.SerializeWith;

/**
 * InfinispanSessionData
 *
 * Specialization of SessionData to hold the attributes as a serialized byte
 * array. This is necessary because to deserialize the attributes correctly, we
 * need to know which classloader to use, which is normally provided as the
 * thread context classloader. However, infinispan marshalling uses a thread
 * pool and thus these threads have no knowledge of the correct classloader to
 * use.
 */
public class InfinispanSessionData extends SessionData
{
    protected byte[] _serializedAttributes;

    public InfinispanSessionData(String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs)
    {
        super(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs);
    }

    public InfinispanSessionData(String id, String cpath, String vhost, long created, long accessed, long lastAccessed, long maxInactiveMs,
                                 Map<String, Object> attributes)
    {
        super(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs, attributes);
    }

    public byte[] getSerializedAttributes()
    {
        return _serializedAttributes;
    }

    public void setSerializedAttributes(byte[] serializedAttributes)
    {
        _serializedAttributes = serializedAttributes;
    }

    public void deserializeAttributes() throws ClassNotFoundException, IOException
    {
        if (_serializedAttributes == null)
            return;

        try (ByteArrayInputStream bais = new ByteArrayInputStream(_serializedAttributes);
             ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(bais))
        {
            SessionData.deserializeAttributes(this, ois);
            _serializedAttributes = null;
        }
    }

    public void serializeAttributes() throws IOException
    {
        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos))
        {
            SessionData.serializeAttributes(this, oos);
            _serializedAttributes = baos.toByteArray();
        }
    }
}
