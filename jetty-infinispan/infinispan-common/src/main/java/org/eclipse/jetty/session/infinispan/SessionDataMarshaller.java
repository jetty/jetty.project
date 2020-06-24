//
// ========================================================================
// Copyright (c) 1995-2020 Mort Bay Consulting Pty Ltd and others.
//
// This program and the accompanying materials are made available under
// the terms of the Eclipse Public License 2.0 which is available at
// https://www.eclipse.org/legal/epl-2.0
//
// This Source Code may also be made available under the following
// Secondary Licenses when the conditions for such availability set
// forth in the Eclipse Public License, v. 2.0 are satisfied:
// the Apache License v2.0 which is available at
// https://www.apache.org/licenses/LICENSE-2.0
//
// SPDX-License-Identifier: EPL-2.0 OR Apache-2.0
// ========================================================================
//

package org.eclipse.jetty.session.infinispan;

import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.infinispan.commons.marshall.Externalizer;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.MessageMarshaller;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;

/**
 * SessionDataMarshaller
 *
 * A marshaller for converting a SessionData object into protobuf format which
 * gives greater control over serialization/deserialization. We use that extra
 * control to ensure that session attributes can be deserialized using either
 * the container class loader or the webapp classloader, as appropriate.
 */
public class SessionDataMarshaller
        implements MessageMarshaller<InfinispanSessionData>, Externalizer<InfinispanSessionData>
{
    /**
     * The version of the serializer.
     */
    private static final int VERSION = 0;

    private static SerializationContext serializationContext;
    
    private static synchronized void initSerializationContext() throws IOException
    {
        if (serializationContext != null)
        {
            return;
        }
        FileDescriptorSource fds = new FileDescriptorSource();
        fds.addProtoFiles("/session.proto");
        SerializationContext sCtx = ProtobufUtil.newSerializationContext();
        sCtx.registerProtoFiles(fds);
        sCtx.registerMarshaller(new SessionDataMarshaller());
        serializationContext = sCtx;
    }

    @Override
    public Class<? extends InfinispanSessionData> getJavaClass()
    {
        return InfinispanSessionData.class;
    }

    @Override
    public String getTypeName()
    {
        return "org_eclipse_jetty_session_infinispan.InfinispanSessionData";
    }

    @Override
    public InfinispanSessionData readObject(ObjectInput input) throws IOException, ClassNotFoundException
    {
        if (serializationContext == null)
        {
            initSerializationContext();
        }

        // invokes readFrom(ProtoStreamReader)
        InfinispanSessionData data = ProtobufUtil.readFrom(serializationContext, new BoundDelegatingInputStream(input),
                InfinispanSessionData.class);
        if (data != null)
        {
            data.deserializeAttributes();
        }
        return data;
    }

    @Override
    public void writeObject(ObjectOutput output, InfinispanSessionData object) throws IOException
    {
        if (serializationContext == null)
        {
            initSerializationContext();
        }

     // invokes writeTo(ProtoStreamWriter, InfinispanSessionData)
        byte[] data = ProtobufUtil.toByteArray(serializationContext, object);
        int length = data.length;
        output.writeInt(length);
        output.write(data);        
    }

    @Override
    public InfinispanSessionData readFrom(ProtoStreamReader in) throws IOException
    {
        final int version = in.readInt("version");// version of serialized session
        final String id = in.readString("id"); // session id
        final String cpath = in.readString("contextPath"); // context path
        final String vhost = in.readString("vhost"); // first vhost

        final long accessed = in.readLong("accessed");// accessTime
        final long lastAccessed = in.readLong("lastAccessed"); // lastAccessTime
        final long created = in.readLong("created"); // time created
        final long cookieSet = in.readLong("cookieSet");// time cookie was set
        final String lastNode = in.readString("lastNode"); // name of last node
        // managing

        final long expiry = in.readLong("expiry");
        final long maxInactiveMs = in.readLong("maxInactiveMs");

        InfinispanSessionData sd = new InfinispanSessionData(id, cpath, vhost, created, accessed, lastAccessed,
                maxInactiveMs);
        sd.setCookieSet(cookieSet);
        sd.setLastNode(lastNode);
        sd.setExpiry(expiry);

        if (version == 0)
        {
            byte[] attributeArray = in.readBytes("attributes");
            sd.setSerializedAttributes(attributeArray);
            return sd;
        }
        else
            throw new IOException("Unrecognized infinispan session version " + version);
    }

    @Override
    public void writeTo(ProtoStreamWriter out, InfinispanSessionData sdata) throws IOException
    {
        out.writeInt("version", VERSION);
        out.writeString("id", sdata.getId()); // session id
        out.writeString("contextPath", sdata.getContextPath()); // context path
        out.writeString("vhost", sdata.getVhost()); // first vhost

        out.writeLong("accessed", sdata.getAccessed());// accessTime
        out.writeLong("lastAccessed", sdata.getLastAccessed()); // lastAccessTime
        out.writeLong("created", sdata.getCreated()); // time created
        out.writeLong("cookieSet", sdata.getCookieSet());// time cookie was set
        out.writeString("lastNode", sdata.getLastNode()); // name of last node
        // managing

        out.writeLong("expiry", sdata.getExpiry());
        out.writeLong("maxInactiveMs", sdata.getMaxInactiveMs());

        sdata.serializeAttributes();
        out.writeBytes("attributes", sdata.getSerializedAttributes());
    }

}
