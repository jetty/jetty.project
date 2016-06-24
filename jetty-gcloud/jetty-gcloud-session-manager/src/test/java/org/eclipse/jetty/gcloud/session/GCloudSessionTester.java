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




import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.AllowSymLinkAliasChecker;
import org.eclipse.jetty.server.session.DefaultSessionIdManager;
import org.eclipse.jetty.server.session.DefaultSessionCache;
import org.eclipse.jetty.server.session.SessionHandler;
import org.eclipse.jetty.webapp.WebAppContext;

public class GCloudSessionTester
{
    public static void main( String[] args ) throws Exception
    {
        if (args.length < 4)
            System.err.println("Usage: GCloudSessionTester projectid p12file password serviceaccount");
        
        System.setProperty("org.eclipse.jetty.server.session.LEVEL", "DEBUG");
        
        Server server = new Server(8080);
        HashLoginService loginService = new HashLoginService();
        loginService.setName( "Test Realm" );
        loginService.setConfig( "../../jetty-distribution/target/distribution/demo-base/resources/realm.properties" );
        server.addBean( loginService );


        DefaultSessionIdManager idmgr = new DefaultSessionIdManager(server);
        idmgr.setWorkerName("w1");
        server.setSessionIdManager(idmgr);

 
        WebAppContext webapp = new WebAppContext();
        webapp.setContextPath("/");
        webapp.setWar("../../jetty-distribution/target/distribution/demo-base/webapps/test.war");
        webapp.addAliasCheck(new AllowSymLinkAliasChecker());
        GCloudSessionDataStore ds = new GCloudSessionDataStore();

        DefaultSessionCache ss = new DefaultSessionCache(webapp.getSessionHandler());
        webapp.getSessionHandler().setSessionCache(ss);
        ss.setSessionDataStore(ds);
        webapp.getSessionHandler().setSessionIdManager(idmgr);

        // A WebAppContext is a ContextHandler as well so it needs to be set to
        // the server so it is aware of where to send the appropriate requests.
        server.setHandler(webapp);

        // Start things up! 
        server.start();

    
        server.join();
    }
}
