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


package org.eclipse.jetty.session.infinispan;

import java.util.ArrayList;
import java.util.List;
import java.util.Properties;

import org.eclipse.jetty.server.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.commons.util.CloseableIteratorSet;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;


/**
 * InfinispanSessionLegacyConverter
 * 
 * Converts sessions saved in the old serialization
 * format into the new protobuf-based serialization.
 *
 */
public class InfinispanSessionLegacyConverter
{
    RemoteCacheManager _protoManager;
    RemoteCache _protoCache;
    RemoteCacheManager _legacyManager;
    RemoteCache _legacyCache;

    public InfinispanSessionLegacyConverter (String cacheName)
    throws Exception
    {
        //legacy serialization
        _legacyManager = new RemoteCacheManager();
        _legacyCache = _legacyManager.getCache(cacheName);
        
        
        //new protobuf based 
        Properties properties = new Properties();
        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties).addServer().host("127.0.0.1").marshaller(new ProtoStreamMarshaller());
        _protoManager = new RemoteCacheManager(clientBuilder.build());
        FileDescriptorSource fds = new FileDescriptorSource();
        fds.addProtoFiles("/session.proto");
        SerializationContext serCtx = ProtoStreamMarshaller.getSerializationContext(_protoManager);
        serCtx.registerProtoFiles(fds);
        serCtx.registerMarshaller(new SessionDataMarshaller());
        _protoCache = _protoManager.getCache(cacheName);
    }
    
    
    
    /**
     * Convert all sessions to protobuf format sessions.
     * 
     * @throws Exception
     */
    public void convert ()
    throws Exception
    {
        List<String> keys = new ArrayList<>();
        //Get all sessions stored in the legacy format
        @SuppressWarnings("unchecked")
        CloseableIteratorSet<String> set = _legacyCache.keySet();
        set.forEach((s)->keys.add(s));
        
        for (String s:keys)
        {
            SessionData data = (SessionData)_legacyCache.get(s);

            if (data != null)
            {
                //now write it out to the protobuf format
                _protoCache.put(s, data);
                //check we can get it back
                SessionData converted = (SessionData)_protoCache.get(s);
                if (converted != null)
                    System.err.println("Converted "+s);
                else
                    System.err.println("Conversion failed for "+s);
            }
            else
                System.err.println("Unreadable legacy "+s);
        }
    }
    
    
    /**
     * Retrieve the sessions using protobuf format and print them out to 
     * confirm they're ok.
     * 
     * @throws Exception
     */
    public void checkConverted ()
    throws Exception
    {
        List<String> keys = new ArrayList<>();
        //Get all sessions stored in the legacy format
        @SuppressWarnings("unchecked")
        CloseableIteratorSet<String> set = _protoCache.keySet();
        set.forEach((s)->keys.add(s));
        for (String s:keys)
        {
            SessionData converted = (SessionData)_protoCache.get(s);
            if (converted != null)
            {
                System.err.println("OK: "+converted);
                converted.getKeys().stream().forEach((ss)->{System.err.println(ss+":"+converted.getAttribute(ss));});
            }
            else
                System.err.println("Failed: "+s);
        }
    }


    public static final void usage ()
    {
        System.err.println("Usage:  InfinispanSessionLegacyConverter <cache-name>");
    }
    
    
    public static final void main (String... args)
    {
        if (args == null || args.length < 1)
        {
            usage();
            System.exit(1);
        }

        try
        {
            InfinispanSessionLegacyConverter converter = new InfinispanSessionLegacyConverter(args[0]);

            if (args.length == 1)
                converter.convert();
            else if (args[1].equals("check"))
                converter.checkConverted();
            else
               usage();

        }
        catch (Exception e)
        {
            System.err.println("Conversion failure");
            e.printStackTrace();
        }
    }
}
