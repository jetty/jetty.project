package org.eclipse.jetty;

import org.eclipse.jetty.http.security.Password;
import org.eclipse.jetty.security.HashLoginService;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.webapp.WebAppContext;

public class DemoServer
{
    public static void main(String[] args)
        throws Exception
    {
        Server server = new Server(8080);
        
        WebAppContext context = new WebAppContext();
        context.setWar("./target/jetty-test-webapp-7.0.0.incubation0-SNAPSHOT");
        context.setDefaultsDescriptor("../jetty-webapp/src/main/config/etc/webdefault.xml");
        server.setHandler(context);
        
        HashLoginService login = new HashLoginService();
        login.putUser("jetty",new Password("password"),new String[]{"user"});
        login.putUser("admin",new Password("password"),new String[]{"user","admin"});
        server.addBean(login);
        
        server.start();
        server.join();
    }
}
