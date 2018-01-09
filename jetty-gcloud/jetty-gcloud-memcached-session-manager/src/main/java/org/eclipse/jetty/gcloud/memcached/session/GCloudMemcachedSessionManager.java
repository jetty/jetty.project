//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.gcloud.memcached.session;


import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.util.Map;

import org.eclipse.jetty.gcloud.session.GCloudSessionManager;
import org.eclipse.jetty.util.ClassLoadingObjectInputStream;
import org.eclipse.jetty.util.StringUtil;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;

import com.google.cloud.datastore.Key;

import net.rubyeye.xmemcached.MemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClient;
import net.rubyeye.xmemcached.XMemcachedClientBuilder;
import net.rubyeye.xmemcached.transcoders.SerializingTranscoder;

/**
 * GCloudMemcachedSessionManager
 *
 * Use memcached in front of GCloudDataStore
 *
 */
public class GCloudMemcachedSessionManager extends GCloudSessionManager
{
    private  final static Logger LOG = Log.getLogger("org.eclipse.jetty.server.session");
    
    protected String _host;
    protected String _port;
    protected MemcachedClient _client;
    protected int _expirySec = 0;
    private boolean _heartbeats = true;

    

    /**
     * ContextClassloaderSerializingTranscoder
     *
     * A xmemcached transcoder that will use the thread context classloader to
     * resolve classes during object deserialization: necessary for Servlet Spec
     * classloading order of context classloader first.
     *  
     */
    public class ContextClassloaderSerializingTranscoder extends SerializingTranscoder
    {

        @Override
        protected Object deserialize(byte[] in)
        {

            if (in == null)
                return null;

            Object rv = null;
            try (ByteArrayInputStream bis = new ByteArrayInputStream(in);ObjectInputStream is = new ClassLoadingObjectInputStream(bis);)
            {

                rv = is.readObject();
            }
            catch (IOException e)
            {
                LOG.warn("Caught IOException decoding " + in.length + " bytes of data", e);
            }
            catch (ClassNotFoundException e)
            {
                LOG.warn("Caught CNFE decoding " + in.length + " bytes of data", e);
            }
            
            return rv;

        }
    }

    
    
    /**
     * MemcacheSession
     *
     * Needed to make a constructor public.
     */
    public class MemcacheSession extends GCloudSessionManager.Session
    {

        public MemcacheSession(String sessionId, long created, long accessed, long maxInterval)
        {
            super(sessionId, created, accessed, maxInterval);
        }  
    }
    
    /**
     * Every time a Session is put into the cache one of these objects
     * is created to copy the data out of the in-memory session, and 
     * every time an object is read from the cache one of these objects
     * a fresh Session object is created based on the data held by this
     * object.
     */
    public class SerializableSessionData implements Serializable
    {
        /**
         * 
         */
        private static final long serialVersionUID = -7779120106058533486L;
        String clusterId;
        String contextPath;
        String vhost;
        long accessed;
        long lastAccessed;
        long createTime;
        long cookieSetTime;
        String lastNode;
        long expiry;
        long maxInactive;
        Map<String, Object> attributes;



        public SerializableSessionData()
        {}


        public SerializableSessionData(Session s)
        {
            clusterId = s.getClusterId();
            contextPath = s.getContextPath();
            vhost = s.getVHost();
            accessed = s.getAccessed();
            lastAccessed = s.getLastAccessedTime();
            createTime = s.getCreationTime();
            cookieSetTime = s.getCookieSetTime();
            lastNode = s.getLastNode();
            expiry = s.getExpiry();
            maxInactive = s.getMaxInactiveInterval();
            attributes = s.getAttributeMap();
       }
       
        
        private void writeObject(java.io.ObjectOutputStream out) throws IOException
        { 
            out.writeUTF(clusterId); //session id
            out.writeUTF(contextPath); //context path
            out.writeUTF(vhost); //first vhost

            out.writeLong(accessed);//accessTime
            out.writeLong(lastAccessed); //lastAccessTime
            out.writeLong(createTime); //time created
            out.writeLong(cookieSetTime);//time cookie was set
            out.writeUTF(lastNode); //name of last node managing

            out.writeLong(expiry); 
            out.writeLong(maxInactive);
            out.writeObject(attributes);
        }

        private void readObject(java.io.ObjectInputStream ois) throws IOException, ClassNotFoundException
        {
            clusterId = ois.readUTF();
            contextPath = ois.readUTF();
            vhost = ois.readUTF();
            accessed = ois.readLong();//accessTime
            lastAccessed = ois.readLong(); //lastAccessTime
            createTime = ois.readLong(); //time created
            cookieSetTime = ois.readLong();//time cookie was set
            lastNode = ois.readUTF(); //last managing node
            expiry = ois.readLong(); 
            maxInactive = ois.readLong();
            Object o = ois.readObject();
            attributes = ((Map<String,Object>)o);
        }
    }


    
    
    
    
    /**
     * @return the expiry setting for memcached
     */
    public int getExpirySec()
    {
        return _expirySec;
    }

    /**
     * @param expirySec the time in seconds for an item to remain in memcached
     */
    public void setExpirySec(int expirySec)
    {
        _expirySec = expirySec;
    }
    
    
    /**
     * @param heartbeats if true memcached heartbeats are enabled. Default is true.
     */
    public void setHeartbeats (boolean heartbeats)
    {
        _heartbeats  = heartbeats;
    }

    
    @Override
    public void doStart() throws Exception
    {
        if (StringUtil.isBlank(_host) || StringUtil.isBlank(_port))
            throw new IllegalStateException("Memcached host and/or port not configured");

        LOG.info("Memcached host {} port {}", _host, _port);
        
        XMemcachedClientBuilder builder = new XMemcachedClientBuilder(_host+":"+_port);
        _client = builder.build();
        _client.setEnableHeartBeat(_heartbeats);
        

        _client.setTranscoder(new ContextClassloaderSerializingTranscoder());
        super.doStart();
    }

    @Override
    public void doStop() throws Exception
    {
        super.doStop();
        _client.shutdown();
        _client = null;
    }

    @Override
    protected Session load(Key key) throws Exception
    {
        //first try the memcache cache
        if (LOG.isDebugEnabled()) LOG.debug("Loading key {} from memcached ", key.name());
        Session session =  loadFromMemcached(key.name());
        if (session != null)
            return session;

        //then try gcloudatastore
        return super.load(key);
    }

    /**
     * @param key the key for the memcache item
     * @return the Session inflated from memcache
     * @throws Exception
     */
    protected Session loadFromMemcached(String key) throws Exception
    {
        SerializableSessionData sd = _client.get(key);

        if (sd == null)
            return null;

        Session session = new MemcacheSession (sd.clusterId, sd.createTime, sd.accessed, sd.maxInactive);
        session.setLastNode(sd.lastNode);
        session.setContextPath(sd.contextPath);
        session.setVHost(sd.vhost);
        session.setCookieSetTime(sd.cookieSetTime);
        session.setLastAccessedTime(sd.lastAccessed);
        session.setLastNode(sd.lastNode);
        session.setExpiry(sd.expiry);
        session.addAttributes(sd.attributes);
        return session;
    }


    @Override
    protected void save(Session session) throws Exception
    {
        //save to gcloud and then memcache
        super.save(session);        
        saveToMemcached(session);
    }

    
    
    @Override
    protected void delete (GCloudSessionManager.Session session)
    {  
        Exception memcacheException = null;
        try
        {
            deleteFromMemcached(session);
        }
        catch (Exception e)
        {
            memcacheException = e;
        }
        
        super.delete(session);
        if (memcacheException != null)
            throw new RuntimeException(memcacheException);
    }

    
    protected void deleteFromMemcached(Session session) throws Exception
    {
        Key gcloudKey = makeKey(session, _context);
        _client.delete(gcloudKey.name());
    }

    /**
     * Store the session into memcached
     * @param session the Session to be serialized
     * @throws Exception
     */
    protected void saveToMemcached(Session session) throws Exception
    {
        Key gcloudKey = makeKey(session, _context);
        _client.set(gcloudKey.name(), getExpirySec(), new SerializableSessionData(session));
    }

    /**
     * @return the host address of the memcached server
     */
    public String getHost()
    {
        return _host;
    }

    /**
     * @param host the host address of the memcached server
     */
    public void setHost(String host)
    {
        _host = host;
    }

    /**
     * @return the port of the memcached server
     */
    public String getPort()
    {
        return _port;
    }
    
   

    /**
     * @param port the port of the memcached server
     */
    public void setPort(String port)
    {
        _port = port;
    }
}
