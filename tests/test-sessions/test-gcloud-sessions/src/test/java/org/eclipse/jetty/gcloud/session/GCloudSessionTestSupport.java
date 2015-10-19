//
//  ========================================================================
//  Copyright (c) 1995-2015 Mort Bay Consulting Pty. Ltd.
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


package org.eclipse.jetty.gcloud.session;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.eclipse.jetty.util.IO;
import org.eclipse.jetty.util.resource.JarResource;
import org.eclipse.jetty.util.resource.Resource;

import com.google.api.client.util.Strings;
import com.google.gcloud.datastore.Datastore;
import com.google.gcloud.datastore.DatastoreFactory;
import com.google.gcloud.datastore.Entity;
import com.google.gcloud.datastore.GqlQuery;
import com.google.gcloud.datastore.Key;
import com.google.gcloud.datastore.ProjectionEntity;
import com.google.gcloud.datastore.Query;
import com.google.gcloud.datastore.Query.ResultType;
import com.google.gcloud.datastore.QueryResults;
import com.google.gcloud.datastore.StructuredQuery;
import com.google.gcloud.datastore.StructuredQuery.Projection;

/**
 * GCloudSessionTestSupport
 *
 *
 */
public class GCloudSessionTestSupport
{
    
    private static class ProcessOutputReader implements Runnable
    {
        private InputStream _is;
        private String _startupSentinel;
        private BufferedReader _reader;

        public ProcessOutputReader (InputStream is, String startupSentinel)
        throws Exception
        {
            _is = is;
            _startupSentinel = startupSentinel;
            _reader = new BufferedReader(new InputStreamReader(_is));
            if (!Strings.isNullOrEmpty(_startupSentinel))
            {
                String line;
                while ((line = _reader.readLine()) != (null) && !line.contains(_startupSentinel))
                {
                    //System.err.println(line);
                }
            }
        }


        public void run()
        {
            String line;
            try
            {
                while ((line = _reader.readLine()) != (null))
                {
                }
            }
            catch (IOException ignore)
            {
                /* ignore */
            }
            finally
            {
                IO.close(_reader);
            }
        }
    }
    

    public static String DEFAULT_PROJECTID = "jetty9-work";
    public static String DEFAULT_PORT = "8088";
    public static String DEFAULT_HOST = "http://localhost:"+DEFAULT_PORT;
    public static String DEFAULT_GCD_ZIP = "gcd-v1beta2-rev1-2.1.2b.zip";
    public static String DEFAULT_GCD_UNPACKED = "gcd-v1beta2-rev1-2.1.2b";
    public static String DEFAULT_DOWNLOAD_URL = "http://storage.googleapis.com/gcd/tools/";
    
 
    String _projectId;
    String _testServerUrl;
    String _testPort;
    File _datastoreDir;
    File _gcdInstallDir;
    File _gcdUnpackedDir;
    Datastore _ds;
    
    public GCloudSessionTestSupport (File gcdInstallDir)
    {
        _gcdInstallDir = gcdInstallDir;
        if (_gcdInstallDir == null)
            _gcdInstallDir = new File (System.getProperty("java.io.tmpdir"));
        
        _projectId = System.getProperty("DATASTORE_DATASET", System.getenv("DATASTORE_DATASET"));
        if (_projectId == null)
        {
            _projectId = DEFAULT_PROJECTID;
            System.setProperty("DATASTORE_DATASET", _projectId);
        }
        _testServerUrl = System.getProperty("DATASTORE_HOST", System.getenv("DATASTORE_HOST"));
        if (_testServerUrl == null)
        {
            _testServerUrl = DEFAULT_HOST;
            _testPort = DEFAULT_PORT;
            System.setProperty("DATASTORE_HOST", _testServerUrl);
        }
        else
        {
            int i = _testServerUrl.lastIndexOf(':');
            _testPort = _testServerUrl.substring(i+1);
        }
    }
    
    public GCloudSessionTestSupport ()
    {
        this(null);
    }

    public GCloudConfiguration getConfiguration ()
    {
        return new GCloudConfiguration();
    }
    
    
    public void setUp()
    throws Exception
    {
       downloadGCD();
       createDatastore();
       startDatastore();
    }
    
    
    public void downloadGCD()
    throws Exception
    {
        File zipFile = new File (_gcdInstallDir, DEFAULT_GCD_ZIP);       
        _gcdUnpackedDir = new File (_gcdInstallDir, DEFAULT_GCD_UNPACKED);
        File gcdSh = new File (_gcdUnpackedDir, "gcd.sh");
        if (gcdSh.exists())
            return;
        
        
        if (_gcdInstallDir.exists() && !zipFile.exists())
        {
           //download it
            ReadableByteChannel rbc = Channels.newChannel(new URL(DEFAULT_DOWNLOAD_URL+DEFAULT_GCD_ZIP).openStream());
            try (FileOutputStream fos = new FileOutputStream(zipFile)) 
            {
              fos.getChannel().transferFrom(rbc, 0, Long.MAX_VALUE);
            }
        }
        
        if (zipFile.exists())
        {
            //unpack it
            Resource zipResource = JarResource.newJarResource(Resource.newResource(zipFile));
            zipResource.copyTo(_gcdInstallDir);
        }
        
        System.err.println("GCD downloaded and unpacked");
    }
    
    
    
    public void createDatastore ()
    throws Exception
    {
    
        _datastoreDir = Files.createTempDirectory("gcloud-sessions").toFile();
        _datastoreDir.deleteOnExit();
        
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);
        processBuilder.directory(_datastoreDir);
        if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) 
        {
          processBuilder.command("cmd", "/C", new File(_gcdUnpackedDir, "gcd.cmd").getAbsolutePath(), "create", "-p", _projectId, _projectId);
          processBuilder.redirectOutput(new File("NULL:"));
        } 
        else 
        {
          processBuilder.redirectOutput(new File("/tmp/run.out"));
          processBuilder.command("bash", new File(_gcdUnpackedDir, "gcd.sh").getAbsolutePath(), "create", "-p",_projectId, _projectId);
        }

        Process temp = processBuilder.start();
        System.err.println("Create outcome: "+temp.waitFor());
    }
    
    
    public void startDatastore()
    throws Exception
    {
        //start the datastore for the test
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.directory(_datastoreDir);
        processBuilder.redirectErrorStream(true);
        if (System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows")) 
        {
          processBuilder.command("cmd", "/C", new File(_gcdUnpackedDir, "gcd.cmd").getAbsolutePath(), "start", "--testing", "--allow_remote_shutdown","--port="+_testPort, _projectId);
        } 
        else 
        {
          processBuilder.command("bash", new File(_gcdUnpackedDir, "gcd.sh").getAbsolutePath(), "start", "--testing", "--allow_remote_shutdown", "--port="+_testPort, _projectId);
        }
        
        System.err.println("Starting datastore");
        Process temp = processBuilder.start();
        ProcessOutputReader reader = new ProcessOutputReader(temp.getInputStream(), "Dev App Server is now running");
        Thread readerThread = new Thread(reader, "GCD reader");
        readerThread.setDaemon(true);
        readerThread.start();
    }
    
    public void stopDatastore()
    throws Exception
    {
        //Send request to terminate test datastore
        URL url = new URL("http", "localhost", Integer.parseInt(_testPort.trim()), "/_ah/admin/quit");
        HttpURLConnection con = (HttpURLConnection) url.openConnection();
        con.setRequestMethod("POST");
        con.setDoOutput(true);
        con.setDoInput(true);
        OutputStream out = con.getOutputStream();
        out.write("".getBytes());
        out.flush();
        InputStream in = con.getInputStream();
        while (in.read() != -1)
        {
            // consume input
          
        }

        System.err.println("Stop issued");
    }
 
    
    public void clearDatastore()
    {
        org.eclipse.jetty.util.IO.delete(_datastoreDir);
    }
    
    public void tearDown()
    throws Exception
    {
        stopDatastore();
        clearDatastore();
    }
    
    public void ensureDatastore()
    throws Exception
    {
        if (_ds == null)
            _ds = DatastoreFactory.instance().get(getConfiguration().getDatastoreOptions());
    }
    public void listSessions () throws Exception
    {
        ensureDatastore();
        GqlQuery.Builder builder = Query.gqlQueryBuilder(ResultType.ENTITY, "select * from "+GCloudSessionManager.KIND);
       
        Query<Entity> query = builder.build();
    
        QueryResults<Entity> results = _ds.run(query);
        assertNotNull(results);
        System.err.println("SESSIONS::::::::");
        while (results.hasNext())
        {
            
            Entity e = results.next();
            System.err.println(e.getString("clusterId")+" expires at "+e.getLong("expiry"));
        }
        System.err.println("END OF SESSIONS::::::::");
    }
    
    public void assertSessions(int count) throws Exception
    {
        ensureDatastore();
        StructuredQuery<ProjectionEntity> keyOnlyProjectionQuery = Query.projectionEntityQueryBuilder()
                .kind(GCloudSessionManager.KIND)
                .projection(Projection.property("__key__"))
                .limit(100)
                .build();  
        QueryResults<ProjectionEntity> results =   _ds.run(keyOnlyProjectionQuery);
        assertNotNull(results);
        int actual = 0;
        while (results.hasNext())
        { 
            results.next();
            ++actual;
        }       
        assertEquals(count, actual);
    }
    
    public void deleteSessions () throws Exception
    {
       ensureDatastore();
        StructuredQuery<ProjectionEntity> keyOnlyProjectionQuery = Query.projectionEntityQueryBuilder()
                .kind(GCloudSessionManager.KIND)
                .projection(Projection.property("__key__"))
                .limit(100)
                .build();  
        QueryResults<ProjectionEntity> results =   _ds.run(keyOnlyProjectionQuery);
        if (results != null)
        {
            List<Key> keys = new ArrayList<Key>();
            
            while (results.hasNext())
            { 
                ProjectionEntity pe = results.next();
                keys.add(pe.key());
            }
            
            _ds.delete(keys.toArray(new Key[keys.size()]));
        }
        
        assertSessions(0);
    }
}
