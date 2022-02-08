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

package org.eclipse.jetty.hazelcast.session;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectOutputStream;

import com.hazelcast.nio.ObjectDataInput;
import com.hazelcast.nio.ObjectDataOutput;
import com.hazelcast.nio.serialization.StreamSerializer;
import org.eclipse.jetty.server.session.SessionData;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;

/**
 * SessionDataSerializer
 *
 * Handles serialization on behalf of the SessionData object, and
 * ensures that we use jetty's classloading knowledge.
 */
public class SessionDataSerializer implements StreamSerializer<SessionData>
{
    public static final int __TYPEID = 99;

    @Override
    public int getTypeId()
    {
        return __TYPEID;
    }

    @Override
    public void destroy()
    {
    }

    @Override
    public void write(ObjectDataOutput out, SessionData data) throws IOException
    {
        out.writeUTF(data.getId());
        out.writeUTF(data.getContextPath());
        out.writeUTF(data.getVhost());

        out.writeLong(data.getAccessed());
        out.writeLong(data.getLastAccessed());
        out.writeLong(data.getCreated());
        out.writeLong(data.getCookieSet());
        out.writeUTF(data.getLastNode());

        out.writeLong(data.getExpiry());
        out.writeLong(data.getMaxInactiveMs());

        try (ByteArrayOutputStream baos = new ByteArrayOutputStream();
             ObjectOutputStream oos = new ObjectOutputStream(baos))
        {
            SessionData.serializeAttributes(data, oos);
            out.writeByteArray(baos.toByteArray());
        }
    }

    @Override
    public SessionData read(ObjectDataInput in) throws IOException
    {
        final String id = in.readUTF();
        final String contextPath = in.readUTF();
        final String vhost = in.readUTF();

        final long accessed = in.readLong();
        final long lastAccessed = in.readLong();
        final long created = in.readLong();
        final long cookieSet = in.readLong();
        final String lastNode = in.readUTF();
        final long expiry = in.readLong();
        final long maxInactiveMs = in.readLong();

        SessionData sd = new SessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxInactiveMs);

        ByteArrayInputStream bais = new ByteArrayInputStream(in.readByteArray());
        try (ClassLoadingObjectInputStream ois = new ClassLoadingObjectInputStream(bais))
        {
            SessionData.deserializeAttributes(sd, ois);
        }
        catch (ClassNotFoundException e)
        {
            throw new IOException(e);
        }
        sd.setCookieSet(cookieSet);
        sd.setLastNode(lastNode);
        sd.setExpiry(expiry);
        return sd;
    }
}
