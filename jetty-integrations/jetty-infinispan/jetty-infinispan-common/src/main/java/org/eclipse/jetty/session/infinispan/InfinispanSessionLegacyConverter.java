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

import java.util.List;
import java.util.Properties;
import java.util.stream.Collectors;

import org.eclipse.jetty.session.SessionData;
import org.infinispan.client.hotrod.RemoteCache;
import org.infinispan.client.hotrod.RemoteCacheManager;
import org.infinispan.client.hotrod.configuration.ConfigurationBuilder;
import org.infinispan.client.hotrod.marshall.ProtoStreamMarshaller;
import org.infinispan.protostream.FileDescriptorSource;
import org.infinispan.protostream.SerializationContext;

/**
 * InfinispanSessionLegacyConverter
 *
 * Converts sessions saved in the old serialization
 * format into the new protobuf-based serialization.
 *
 * Use the -Dverbose=true system property to
 * print out more information about conversion failures.
 */
public class InfinispanSessionLegacyConverter
{
    RemoteCacheManager _protoManager;
    RemoteCache<String, InfinispanSessionData> _protoCache;
    RemoteCacheManager _legacyManager;
    RemoteCache<String, SessionData> _legacyCache;
    boolean _verbose = false;

    public InfinispanSessionLegacyConverter(String cacheName)
        throws Exception
    {
        //legacy serialization
        _legacyManager = new RemoteCacheManager();
        _legacyCache = _legacyManager.getCache(cacheName);

        //new protobuf based
        String host = System.getProperty("host", "127.0.0.1");
        _verbose = Boolean.getBoolean("verbose");

        Properties properties = new Properties();
        ConfigurationBuilder clientBuilder = new ConfigurationBuilder();
        clientBuilder.withProperties(properties).addServer().host(host).marshaller(new ProtoStreamMarshaller());
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
     */
    public void convert()
    {
        long conversions = 0;
        List<String> keys = null;

        //Get all sessions stored in the legacy format
        try
        {
            keys = _legacyCache.keySet().stream().collect(Collectors.toList());
        }
        catch (Exception e)
        {
            System.err.println("Error listing legacy sessions, assuming previously converted. Run again using 'check' argument to verify conversion");
            if (_verbose)
                e.printStackTrace();
            System.exit(1);
        }

        for (String s : keys)
        {
            SessionData data = null;
            try
            {
                data = (SessionData)_legacyCache.get(s);
            }
            catch (Exception e)
            {
                System.err.println("Read of session " + s + " failed. Assuming session already converted and skipping.");
                if (_verbose)
                    e.printStackTrace();
                continue;
            }

            if (data != null)
            {
                try
                {
                    _legacyCache.remove(s);
                }
                catch (Exception e)
                {
                    System.err.println("Remove legacy session failed for " + s + " skipping conversion.");
                    if (_verbose)
                        e.printStackTrace();
                    continue;
                }

                try
                {
                    InfinispanSessionData isd = new InfinispanSessionData(data.getId(), data.getContextPath(), data.getVhost(), data.getCreated(),
                        data.getAccessed(), data.getLastAccessed(), data.getMaxInactiveMs());
                    isd.putAllAttributes(data.getAllAttributes());
                    isd.setExpiry(data.getExpiry());
                    isd.setCookieSet(data.getCookieSet());
                    isd.setLastSaved(data.getLastSaved());
                    isd.setLastNode(data.getLastNode());
                    // now write it out to the protobuf format
                    _protoCache.put(s, isd);
                    System.err.println("Converted " + s);
                    conversions++;
                }
                catch (Exception e)
                {
                    if (_verbose)
                        e.printStackTrace();
                    System.err.println("Conversion failed for " + s + " re-instating legacy session.");
                    try
                    {
                        _legacyCache.put(s, data);
                    }
                    catch (Exception x)
                    {
                        System.err.println("FAILED REINSTATING SESSION " + s + ". ABORTING.");
                        x.printStackTrace();
                        System.exit(1);
                    }
                }
            }
            else
                System.err.println("Unreadable legacy session " + s);
        }

        System.err.println("Total sessions converted: " + conversions);
    }

    /**
     * Retrieve the sessions using protobuf format and print them out to
     * confirm they're ok.
     */
    public void checkConverted()
    {
        List<String> keys = null;
        try
        {
            keys = _protoCache.keySet().stream().collect(Collectors.toList());
        }
        catch (Exception e)
        {
            System.err.println("Unable to read converted sessions, assuming still in legacy format. Run again without 'check' option to convert.");
            e.printStackTrace();
            System.exit(1);
        }

        for (String s : keys)
        {
            InfinispanSessionData converted = _protoCache.get(s);
            if (converted != null)
            {
                System.err.println("OK: " + converted);
                converted.getKeys().stream().forEach((ss) -> System.err.println(ss + ":" + converted.getAttribute(ss)));
            }
            else
                System.err.println("Failed: " + s);
        }

        System.err.println("Total converted sessions: " + keys.size());
    }

    public static final void usage()
    {
        System.err.println("Usage:  InfinispanSessionLegacyConverter [-Dhost=127.0.0.1] [-Dverbose=true] <cache-name> [check]");
    }

    public static final void main(String... args)
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
