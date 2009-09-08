// ========================================================================
// Copyright (c) 2009-2009 Mort Bay Consulting Pty. Ltd.
// ------------------------------------------------------------------------
// All rights reserved. This program and the accompanying materials
// are made available under the terms of the Eclipse Public License v1.0
// and Apache License v2.0 which accompanies this distribution.
// The Eclipse Public License is available at 
// http://www.eclipse.org/legal/epl-v10.html
// The Apache License v2.0 is available at
// http://www.opensource.org/licenses/apache2.0.php
// You may elect to redistribute this code under either of these licenses. 
// ========================================================================
package org.eclipse.jetty.webapp;

import java.io.File;
import java.net.URI;
import java.util.Collection;

import org.eclipse.jetty.util.log.Log;
import org.eclipse.jetty.webapp.verifier.RuleSet;
import org.eclipse.jetty.webapp.verifier.Severity;
import org.eclipse.jetty.webapp.verifier.Violation;
import org.eclipse.jetty.webapp.verifier.WebappVerifier;

/**
 * WebappVerifierConfiguration
 */
public class WebappVerifierConfiguration implements Configuration
{
    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.Configuration#configure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void configure(WebAppContext context) throws Exception
    {

    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.Configuration#deconfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void deconfigure(WebAppContext context) throws Exception
    {

    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.Configuration#postConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void postConfigure(WebAppContext context) throws Exception
    {

    }

    /* ------------------------------------------------------------ */
    /**
     * @see org.eclipse.jetty.webapp.Configuration#preConfigure(org.eclipse.jetty.webapp.WebAppContext)
     */
    public void preConfigure(WebAppContext context) throws Exception
    {
        if(Log.isDebugEnabled())
        {
            Log.debug("Configuring webapp verifier");
        }
        
        URI configurationUri = new File( System.getProperty( "jetty.home" )).toURI().resolve( "etc/default-webapp-verifier.xml" );
                
        RuleSet suite = RuleSet.load( configurationUri.toURL() );
        
        WebappVerifier verifier = suite.createWebappVerifier( new URI( context.getWar() ) );
        
        verifier.visitAll();
        
        Collection<Violation> violations = verifier.getViolations();
        
        boolean haltWebapp = false;
        
        Log.info( " Webapp Verifier Report: " + violations.size()  + " violations" );
        
        for (Violation violation : violations)
        {
            if ( violation.getSeverity() == Severity.ERROR )
            {
                haltWebapp = true;
            }
            
            Log.info( violation.toString() );           
        }
        
        if ( haltWebapp )
        {
            throw new InstantiationException( "Configuration exception: webapp failed webapp verification" );
        }
    }
}
