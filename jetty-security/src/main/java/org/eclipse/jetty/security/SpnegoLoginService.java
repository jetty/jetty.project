package org.eclipse.jetty.security;
//========================================================================
//Copyright (c) Webtide LLC
//------------------------------------------------------------------------
//All rights reserved. This program and the accompanying materials
//are made available under the terms of the Eclipse Public License v1.0
//and Apache License v2.0 which accompanies this distribution.
//
//The Eclipse Public License is available at 
//http://www.eclipse.org/legal/epl-v10.html
//
//The Apache License v2.0 is available at
//http://www.opensource.org/licenses/apache2.0.php
//
//You may elect to redistribute this code under either of these licenses. 
//========================================================================

import java.util.Collections;
import java.util.Properties;

import javax.security.auth.Subject;

import org.eclipse.jetty.http.security.B64Code;
import org.eclipse.jetty.server.UserIdentity;
import org.eclipse.jetty.util.component.AbstractLifeCycle;
import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.resource.Resource;
import org.ietf.jgss.GSSContext;
import org.ietf.jgss.GSSCredential;
import org.ietf.jgss.GSSException;
import org.ietf.jgss.GSSManager;
import org.ietf.jgss.GSSName;
import org.ietf.jgss.Oid;

public class SpnegoLoginService extends AbstractLifeCycle implements LoginService
{
    protected IdentityService _identityService;// = new LdapIdentityService();
    protected String _name;
    private String _config;
    
    private String _targetName;

    public SpnegoLoginService()
    {
        
    }
    
    public SpnegoLoginService( String name )
    {
        setName(name);
    }
    
    public SpnegoLoginService( String name, String config )
    {
        setName(name);
        setConfig(config);
    }
    
    public String getName()
    {
        return _name;
    }

    public void setName(String name)
    {
        if (isRunning())
        {
            throw new IllegalStateException("Running");
        }
        
        _name = name;
    }
    
    public String getConfig()
    {
        return _config;
    }
    
    public void setConfig( String config )
    {
        if (isRunning())
        {
            throw new IllegalStateException("Running");
        }
        
        _config = config;
    }
    
    
    
    @Override
    protected void doStart() throws Exception
    {
        Properties properties = new Properties();
        Resource resource = Resource.newResource(_config);
        properties.load(resource.getInputStream());
        
        _targetName = properties.getProperty("targetName");
        
        Log.debug("\n\nTarget Name\n\n" + _targetName);
        
        super.doStart();
    }

    /**
     * username will be null since the credentials will contain all the relevant info
     */
    public UserIdentity login(String username, Object credentials)
    {
        String encodedAuthToken = (String)credentials;
        
        byte[] authToken = B64Code.decode(encodedAuthToken);
        
        GSSManager manager = GSSManager.getInstance();
        try
        {
            Oid krb5Oid = new Oid("1.3.6.1.5.5.2"); // http://java.sun.com/javase/6/docs/technotes/guides/security/jgss/jgss-features.html
            GSSName gssName = manager.createName(_targetName,null);
            GSSCredential serverCreds = manager.createCredential(gssName,GSSCredential.INDEFINITE_LIFETIME,krb5Oid,GSSCredential.ACCEPT_ONLY);
            GSSContext gContext = manager.createContext(serverCreds);

            if (gContext == null)
            {
                Log.debug("SpnegoUserRealm: failed to establish GSSContext");
            }
            else
            {
                while (!gContext.isEstablished())
                {
                    authToken = gContext.acceptSecContext(authToken,0,authToken.length);
                }
                if (gContext.isEstablished())
                {
                    String clientName = gContext.getSrcName().toString();
                    String role = clientName.substring(clientName.indexOf('@') + 1);
                    
                    Log.debug("SpnegoUserRealm: established a security context");
                    Log.debug("Client Principal is: " + gContext.getSrcName());
                    Log.debug("Server Principal is: " + gContext.getTargName());
                    Log.debug("Client Default Role: " + role);

                    SpnegoUserPrincipal user = new SpnegoUserPrincipal(clientName,authToken);

                    Subject subject = new Subject();
                    subject.getPrincipals().add(user);
                    
                    return _identityService.newUserIdentity(subject,user, new String[]{role});
                }
            }

        }
        catch (GSSException gsse)
        {
            Log.warn(gsse);
        }

        return null;
    }

    public boolean validate(UserIdentity user)
    {
        return false;
    }

    public IdentityService getIdentityService()
    {
        return _identityService;
    }

    public void setIdentityService(IdentityService service)
    {
        _identityService = service;
    }

	public void logout(UserIdentity user) {
		// TODO Auto-generated method stub
		
	}

}
