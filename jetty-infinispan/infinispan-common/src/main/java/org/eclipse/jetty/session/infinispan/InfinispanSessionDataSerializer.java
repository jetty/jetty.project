package org.eclipse.jetty.session.infinispan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.infinispan.commons.marshall.Externalizer;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.ProtobufUtil;
import org.infinispan.protostream.SerializationContext;

public class InfinispanSessionDataSerializer implements Externalizer<InfinispanSessionData>
{
    private static final long serialVersionUID = 1L;

    private static final Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    private static SerializationContext serCtx;

    static
    {
        FileDescriptorSource fds = new FileDescriptorSource();
        try
        {
            fds.addProtoFiles("/session.proto");
            serCtx = ProtobufUtil.newSerializationContext();
            serCtx.registerProtoFiles(fds);
            serCtx.registerMarshaller(new SessionDataMarshaller());
        }
        catch (IOException e)
        {
            serCtx = null;
            LOG.warn("SerializationContext cannot be initialized", e);
        }

    }

    @Override
    public void writeObject(ObjectOutput output, InfinispanSessionData object) throws IOException
    {
        if (serCtx != null) 
        {
            byte[] data = ProtobufUtil.toByteArray(serCtx, object);
            output.write(data);
        }
        else
        {
            throw new IOException("SerializationContext is not initialized");
        }
    }

    @Override
    public InfinispanSessionData readObject(ObjectInput input) throws IOException, ClassNotFoundException
    {
        if (serCtx != null)
        {
            ByteArrayOutputStream os = new ByteArrayOutputStream();
            int bufferSize = 4 * 1024;
            byte[] buffer = new byte[bufferSize];
            int len = -1;
            while ((len = input.read(buffer, 0, bufferSize)) != -1)
            {
                os.write(buffer, 0, len);
            }

            InfinispanSessionData data = ProtobufUtil.fromByteArray(serCtx, os.toByteArray(),
                    InfinispanSessionData.class);
            if (data != null)
            {
                data.deserializeAttributes();
            }
            return data;
        }
        else
        {
            throw new IOException("SerializationContext is not initialized");
        }
    }

}
