//
//  ========================================================================
//  Copyright (c) 1995-2013 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.policy;

import java.io.PrintStream;
import java.io.PrintWriter;
import java.security.AccessControlException;
import java.security.CodeSource;
import java.security.Permission;
import java.security.PermissionCollection;
import java.security.Permissions;
import java.security.Policy;
import java.security.Principal;
import java.security.ProtectionDomain;
import java.security.cert.Certificate;
import java.security.cert.CertificateException;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.util.log.Logger;
import org.eclipse.jetty.util.security.CertificateValidator;


/**
 * Policy implementation that will load a set of policy files and manage the mapping of permissions and protection domains
 * 
 * Features of JettyPolicy are:
 * 
 * - we are able to follow the startup mechanic that jetty uses with jetty-start using OPTIONS=policy,default to be able to startup a security manager and policy implementation without have to rely on the existing JVM cli options 
 * - support for specifying multiple policy files to source permissions from
 * - support for merging protection domains across multiple policy files for the same codesource
 * - support for directories of policy files, just specify directory and all *.policy files will be loaded.

 * Possible additions are: 
 * - scan policy directory for new policy files being added
 * - jmx reporting
 * - proxying of system security policy where we can proxy access to the system policy should the jvm have been started with one, I had support for this but ripped it
 * out to add in again later 
 * - an xml policy file parser, had originally added this using modello but tore it out since it would have been a
 * nightmare to get its dependencies through IP validation, could do this with jvm xml parser instead sometime 
 * - check performance of the synch'd map I am using for the protection domain mapping
 */
public class JettyPolicy extends Policy
{
    private static final Logger LOG = Log.getLogger(JettyPolicy.class);
    
    private static boolean __DEBUG = false;
    private static boolean __RELOAD = false;

    private boolean _STARTED = false;
    
    private String _policyDirectory;
    
    private final Set<PolicyBlock> _grants = new HashSet<PolicyBlock>();

    /*
     * TODO: make into a proper cache
     */
    private final Map<Object, PermissionCollection> _cache = new ConcurrentHashMap<Object, PermissionCollection>();

    private final static PolicyContext _context = new PolicyContext();

    private CertificateValidator _validator = null;
        
    private PolicyMonitor _policyMonitor = new PolicyMonitor()
    {        
        @Override
        public void onPolicyChange(PolicyBlock grant)
        {
            boolean setGrant = true;     
            
            if ( _validator != null )
            {
                if (grant.getCertificates() != null)
                {
                    for ( Certificate cert : grant.getCertificates() )
                    {
                        try
                        {
                            _validator.validate(_context.getKeystore(), cert);
                        }
                        catch ( CertificateException ce )
                        {
                            setGrant = false;
                        }
                    }                
                }
            }
                  
            if ( setGrant )
            {
                _grants.add( grant );
                _cache.clear();
            }
        }
    };
    
    public JettyPolicy(String policyDirectory, Map<String, String> properties)
    {
        try
        {
            __RELOAD = Boolean.getBoolean("org.eclipse.jetty.policy.RELOAD");
            __DEBUG = Boolean.getBoolean("org.eclipse.jetty.policy.DEBUG");
        }
        catch (AccessControlException ace)
        {
            __RELOAD = false;
            __DEBUG = false;
        }
        
        _policyDirectory = policyDirectory;
        _context.setProperties(properties);
        
        try
        {
            _policyMonitor.setPolicyDirectory(_policyDirectory);
            //_policyMonitor.setReload( __RELOAD );
        }
        catch ( Exception e)
        {
            throw new PolicyException(e);
        }
    }
    
    
    
    @Override
    public void refresh()
    {        
        if ( !_STARTED )
        {
            initialize();
        }
    }

    /**
     * required for the jetty policy to start function, initializes the 
     * policy monitor and blocks for a full cycle of policy grant updates
     */
    public void initialize()
    {
        if ( _STARTED )
        {
            return;         
        }
        
        try
        {
            _policyMonitor.start();
            _policyMonitor.waitForScan();
        }
        catch (Exception e)
        {
            e.printStackTrace();
            throw new PolicyException(e);
        }
        
        _STARTED = true;
    }
    
    @Override
    public PermissionCollection getPermissions(ProtectionDomain domain)
    {

        if (!_STARTED)
        {
            throw new PolicyException("JettyPolicy must be started.");
        }

        synchronized (_cache)
        {
            if (_cache.containsKey(domain))
            {
                return copyOf(_cache.get(domain));
            }

            PermissionCollection perms = new Permissions();

            for (Iterator<PolicyBlock> i = _grants.iterator(); i.hasNext();)
            {
                PolicyBlock policyBlock = i.next();
                ProtectionDomain grantPD = policyBlock.toProtectionDomain();

                if (__DEBUG)
                {
                    debug("----START----");
                    debug("PDCS: " + policyBlock.getCodeSource());
                    debug("CS: " + domain.getCodeSource());

                }

                // 1) if protection domain codesource is null, it is the global permissions (grant {})
                // 2) if protection domain codesource implies target codesource and there are no prinicpals
                if (grantPD.getCodeSource() == null 
                        || 
                        grantPD.getCodeSource().implies(domain.getCodeSource()) 
                        && 
                        grantPD.getPrincipals() == null 
                        || 
                        grantPD.getCodeSource().implies(domain.getCodeSource()) 
                        && 
                        validate(grantPD.getPrincipals(),domain.getPrincipals()))

                {

                    for (Enumeration<Permission> e = policyBlock.getPermissions().elements(); e.hasMoreElements();)
                    {
                        Permission perm = e.nextElement();
                        if (__DEBUG)
                        {
                            debug("D: " + perm);
                        }
                        perms.add(perm);
                    }
                }
                if (__DEBUG)
                {
                    debug("----STOP----");
                }
            }

            _cache.put(domain,perms);

            return copyOf(perms);
        }
    }

    @Override
    public PermissionCollection getPermissions(CodeSource codesource)
    {
        if (!_STARTED)
        {
            throw new PolicyException("JettyPolicy must be started.");
        }

        synchronized (_cache)
        {
            if (_cache.containsKey(codesource))
            {
                return copyOf(_cache.get(codesource));
            }

            PermissionCollection perms = new Permissions();

            for (Iterator<PolicyBlock> i = _grants.iterator(); i.hasNext();)
            {
                PolicyBlock policyBlock = i.next();
                ProtectionDomain grantPD = policyBlock.toProtectionDomain();

                if (grantPD.getCodeSource() == null 
                        || 
                        grantPD.getCodeSource().implies(codesource))
                {
                    if (__DEBUG)
                    {
                        debug("----START----");
                        debug("PDCS: " + grantPD.getCodeSource());
                        debug("CS: " + codesource);
                    }

                    for (Enumeration<Permission> e = policyBlock.getPermissions().elements(); e.hasMoreElements();)
                    {
                        Permission perm = e.nextElement();
                        if (__DEBUG)
                        {
                            debug("D: " + perm);
                        }
                        perms.add(perm);
                    }

                    if (__DEBUG)
                    {
                        debug("----STOP----");
                    }
                }
            }

            _cache.put(codesource,perms);

            return copyOf(perms);
        }
    }

    @Override
    public boolean implies(ProtectionDomain domain, Permission permission)
    {
        if (!_STARTED)
        {
            throw new PolicyException("JettyPolicy must be started.");
        }
        
        PermissionCollection pc = getPermissions(domain);
        
        return (pc == null ? false : pc.implies(permission));
    }
    

    private static boolean validate(Principal[] permCerts, Principal[] classCerts)
    {
        if (classCerts == null)
        {
            return false;
        }

        for (int i = 0; i < permCerts.length; ++i)
        {
            boolean found = false;
            for (int j = 0; j < classCerts.length; ++j)
            {
                if (permCerts[i].equals(classCerts[j]))
                {
                    found = true;
                    break;
                }
            }
            // if we didn't find the permCert in the classCerts then we don't match up
            if (found == false)
            {
                return false;
            }
        }

        return true;
    }


    /**
     * returns the policy context which contains the map of properties that
     * can be referenced in policy files and the keystore for validation
     * 
     * @return the policy context
     */
    public static PolicyContext getContext()
    {
        return _context;
    }
    
   
    
    /**
     * Try and log to normal logging channels and should that not be allowed
     * debug to system.out
     * 
     * @param message
     */
    private void debug( String message )
    {
        try
        {
            LOG.info(message);
        }
        catch ( AccessControlException ace )
        {
            System.out.println( "[DEBUG] " +  message );
        }
        catch ( NoClassDefFoundError ace )
        {
            System.out.println( "[DEBUG] " + message );
            //ace.printStackTrace();
        }
    }
    /**
     * Try and log to normal logging channels and should that not be allowed
     * log to system.out
     * 
     * @param message
     */
    private void log( String message )
    {
        log( message, null );
    }
    
    /**
     * Try and log to normal logging channels and should that not be allowed
     * log to system.out
     * 
     * @param message
     */
    private void log( String message, Throwable t )
    {
        try
        {
            LOG.info(message, t);
        }
        catch ( AccessControlException ace )
        {
            System.out.println( message );
            t.printStackTrace();
        }
        catch ( NoClassDefFoundError ace )
        {
            System.out.println( message );
            t.printStackTrace();
        }
    }
    

    public void dump(PrintStream out)
    {
        PrintWriter write = new PrintWriter(out);
        write.println("JettyPolicy: policy settings dump");

        synchronized (_cache)
        {
            for (Iterator<Object> i = _cache.keySet().iterator(); i.hasNext();)
            {
                Object o = i.next();
                write.println(o.toString());
            }
        }
        write.flush();
    }
    
    private PermissionCollection copyOf(final PermissionCollection in)
    {
        PermissionCollection out  = new Permissions();
        synchronized (in)
        {
            for (Enumeration<Permission> el = in.elements() ; el.hasMoreElements() ;)
            {
                out.add((Permission)el.nextElement());
            }
        }
        return out;
    }

    public CertificateValidator getCertificateValidator()
    {
        return _validator;
    }

    public void setCertificateValidator(CertificateValidator validator)
    {
        if (_STARTED)
        {
            throw new PolicyException("JettyPolicy already started, unable to set validator on running policy");
        }
        
        _validator = validator;
    }
}
