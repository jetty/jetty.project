package org.eclipse.jetty.session.infinispan;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.util.Map;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.protostream.MessageMarshaller;

public class SessionDataMarshaller implements MessageMarshaller<SessionData>
{

    @Override
    public Class<? extends SessionData> getJavaClass()
    {
        return SessionData.class;
    }

    @Override
    public String getTypeName()
    {
        return "sessiondata_sample.SessionData";
    }

    @Override
    public SessionData readFrom(ProtoStreamReader in) throws IOException
    {
        String id = in.readString("id"); //session id
        String cpath = in.readString("contextPath"); //context path
        String vhost = in.readString("vhost"); //first vhost

        long accessed = in.readLong("accessed");//accessTime
        long lastAccessed = in.readLong("lastAccessed"); //lastAccessTime
        long created = in.readLong("created"); //time created
        long cookieSet = in.readLong("cookieSet");//time cookie was set
        String lastNode = in.readString("lastNode"); //name of last node managing
  
        long expiry = in.readLong("expiry"); 
        long maxInactiveMs = in.readLong("maxInactiveMs");
        
        
        byte[] attributeArray = in.readBytes("attributes");
        ByteArrayInputStream bin = new ByteArrayInputStream(attributeArray);
        ObjectInputStream oin = new ObjectInputStream(bin);
        
        Map<String, Object> attributes;
        try
        {
            Object o = oin.readObject();
            @SuppressWarnings("unchecked")
            Map<String, Object> a = Map.class.cast(o);
            attributes = a;
        }
        catch(ClassNotFoundException e)
        {
            throw new IOException(e);
        }
        
        SessionData sd = new SessionData(id, cpath, vhost, created, accessed, lastAccessed, maxInactiveMs, attributes);
        sd.setCookieSet(cookieSet);
        sd.setLastNode(lastNode);
        sd.setExpiry(expiry);
        return sd;
        
    }

    @Override
    public void writeTo(ProtoStreamWriter out, SessionData sdata) throws IOException
    {
        out.writeString("id", sdata.getId()); //session id
        out.writeString("contextPath", sdata.getContextPath()); //context path
        out.writeString("vhost", sdata.getVhost()); //first vhost

        out.writeLong("accessed", sdata.getAccessed());//accessTime
        out.writeLong("lastAccessed", sdata.getLastAccessed()); //lastAccessTime
        out.writeLong("created", sdata.getCreated()); //time created
        out.writeLong("cookieSet", sdata.getCookieSet());//time cookie was set
        out.writeString("lastNode", sdata.getLastNode()); //name of last node managing
  
        out.writeLong("expiry", sdata.getExpiry()); 
        out.writeLong("maxInactiveMs", sdata.getMaxInactiveMs());
        
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        ObjectOutputStream oout = new ObjectOutputStream(bout);
        oout.writeObject(sdata.getAllAttributes());
        out.writeBytes("attributes", bout.toByteArray()); 
    }
}
