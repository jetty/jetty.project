//
//  ========================================================================
//  Copyright (c) 1995-2016 Mort Bay Consulting Pty. Ltd.
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

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.util.Properties;

import org.eclipse.jetty.util.security.Password;

import com.google.gcloud.AuthCredentials;
import com.google.gcloud.datastore.DatastoreOptions;



/**
 * GCloudConfiguration
 *
 *
 */
public class GCloudConfiguration
{
    public static final String PROJECT_ID = "projectId";
    public static final String P12 = "p12";
    public static final String PASSWORD = "password";
    public static final String SERVICE_ACCOUNT = "serviceAccount";
    
    private String _projectId;
    private String _p12Filename;
    private File _p12File;
    private String _serviceAccount;
    private String _passwordSet;
    private String _password;
    private AuthCredentials _authCredentials;
    private DatastoreOptions _options;
    
    /**
     * Generate a configuration from a properties file
     * 
     * @param propsFile
     * @return
     * @throws IOException
     */
    public static GCloudConfiguration fromFile(String propsFile)
    throws IOException
    {
        if (propsFile == null)
            throw new IllegalArgumentException ("Null properties file");
        
        File f = new File(propsFile);
        if (!f.exists())
            throw new IllegalArgumentException("No such file "+f.getAbsolutePath());
        Properties props = new Properties();
        try (FileInputStream is=new FileInputStream(f))
        {
            props.load(is);
        }
        
        GCloudConfiguration config = new GCloudConfiguration();
        config.setProjectId(props.getProperty(PROJECT_ID));
        config.setP12File(props.getProperty(P12));
        config.setPassword(props.getProperty(PASSWORD));
        config.setServiceAccount(props.getProperty(SERVICE_ACCOUNT));
        return config;
    }
    
    
    
    public String getProjectId()
    {
        return _projectId;
    }

    public File getP12File()
    {
        return _p12File;
    }

    public String getServiceAccount()
    {
        return _serviceAccount;
    }


    public void setProjectId(String projectId)
    {
        checkForModification();
        _projectId = projectId;
    }

    public void setP12File (String file)
    {
        checkForModification();
        _p12Filename = file;

    }
    
    
    public void setServiceAccount (String serviceAccount)
    {
        checkForModification();
        _serviceAccount = serviceAccount;
    }
    

    public void setPassword (String pwd)
    {
        checkForModification();
        _passwordSet = pwd;

    }


    public DatastoreOptions getDatastoreOptions ()
            throws Exception
    {
        if (_options == null)
        {
            if (_passwordSet == null && _p12Filename == null && _serviceAccount == null)
            {
                //When no values are explicitly presented for auth info, we are either running
                //1. inside GCE environment, in which case all auth info is derived from the environment
                //2. outside the GCE environment, but using a local gce dev server, in which case you
                //   need to set the following 2 environment/system properties
                //          DATASTORE_HOST: eg http://localhost:9999 - this is the host and port of a local development server
                //          DATASTORE_DATASET: eg myProj - this is the name of your project          
                _options = DatastoreOptions.defaultInstance();
            }
            else
            {
                //When running externally to GCE, you need to provide
                //explicit auth info. You can either set the projectId explicitly, or you can set the
                //DATASTORE_DATASET env/system property
                _p12File = new File(_p12Filename);
                Password p = new Password(_passwordSet);
                _password = p.toString();
                _options = DatastoreOptions.builder()
                        .projectId(_projectId)
                        .authCredentials(getAuthCredentials())
                        .build();
            }
        }
        return _options;
    }

    /**
     * @return
     * @throws Exception
     */
    public AuthCredentials getAuthCredentials()
    throws Exception
    {
        if (_authCredentials == null)
        {
            if (_password == null)
                throw new IllegalStateException("No password");

            if (_p12File == null || !_p12File.exists())
                throw new IllegalStateException("No p12 file: "+(_p12File==null?"null":_p12File.getAbsolutePath()));

            if (_serviceAccount == null)
                throw new IllegalStateException("No service account");

            char[] pwdChars = _password.toCharArray();
            KeyStore keystore = KeyStore.getInstance("PKCS12");
            keystore.load(new FileInputStream(getP12File()), pwdChars);
            PrivateKey privateKey = (PrivateKey) keystore.getKey("privatekey", pwdChars);
            _authCredentials = AuthCredentials.createFor(getServiceAccount(), privateKey);
        }
        return _authCredentials;
    }
    
    /**
     * @throws IllegalStateException
     */
    protected void checkForModification () throws IllegalStateException
    {
        if (_authCredentials != null || _options != null)
            throw new IllegalStateException("Cannot modify auth configuration after datastore initialized");     
    }
}
