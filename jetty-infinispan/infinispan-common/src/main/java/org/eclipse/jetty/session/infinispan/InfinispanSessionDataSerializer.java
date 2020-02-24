package org.eclipse.jetty.session.infinispan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInput;
import java.io.ObjectOutput;

import org.infinispan.commons.marshall.Externalizer;

public class InfinispanSessionDataSerializer implements Externalizer<InfinispanSessionData>
{

    private static final long serialVersionUID = 1L;

    @Override
    public void writeObject(ObjectOutput output, InfinispanSessionData object) throws IOException
    {
        output.writeUTF(object.getId()); //session id
        output.writeUTF(object.getContextPath()); //context path
        output.writeUTF(object.getVhost()); //first vhost
        output.writeLong(object.getAccessed());//accessTime
        output.writeLong(object.getLastAccessed()); //lastAccessTime
        output.writeLong(object.getCreated()); //time created
        output.writeLong(object.getCookieSet());//time cookie was set
        output.writeUTF(object.getLastNode()); //name of last node managing
        output.writeLong(object.getExpiry());
        output.writeLong(object.getMaxInactiveMs());
        object.serializeAttributes();
        output.write(object.getSerializedAttributes());

    }

    @Override
    public InfinispanSessionData readObject(ObjectInput input) throws IOException, ClassNotFoundException
    {
        String id = input.readUTF();
        String contextPath = input.readUTF();
        String vhost = input.readUTF();
        long accessed = input.readLong();
        long lastAccessed = input.readLong();
        long created = input.readLong();
        long cookieSet = input.readLong();
        String lastNode = input.readUTF();
        long expiry = input.readLong();
        long maxInactiveMs = input.readLong();
        
        InfinispanSessionData data = new InfinispanSessionData(id, contextPath, vhost, created, accessed, lastAccessed, maxInactiveMs);
        data.setCookieSet(cookieSet);
        data.setLastNode(lastNode);
        data.setExpiry(expiry);
        
        try (ByteArrayOutputStream os = new ByteArrayOutputStream())
        {
            int bufferSize = 4 * 1024;
            byte[] buffer = new byte[bufferSize];
            int len = -1;
            while ((len = input.read(buffer, 0, bufferSize)) != -1)
            {
                os.write(buffer, 0, len);
            }
            byte[] serializedAttributes = os.toByteArray();
            data.setSerializedAttributes(serializedAttributes);
            data.deserializeAttributes();
        }
        
        return data;
    }
    
}
