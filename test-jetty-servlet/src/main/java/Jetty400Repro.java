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

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.Socket;
import java.net.SocketTimeoutException;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.server.Handler;
import org.eclipse.jetty.server.Server;
import org.eclipse.jetty.server.handler.DefaultHandler;
import org.eclipse.jetty.server.handler.HandlerList;
import org.eclipse.jetty.webapp.WebAppContext;
import org.xml.sax.SAXException;


/**
 * Repro a jetty problem.
 * 
 * @author hughw
 *
 */
public class Jetty400Repro extends HttpServlet{


    private static final long serialVersionUID = 1L;
    private static final int port = 8080;
    private static final String host = "localhost";
    private static final String uri = "/flub/servlet/";
    
    /**
     * Jetty 7.0.1 returns 400 on the second POST, when you send both Connection: Keep-Alive and 
     * Expect: 100-Continue headers in the request. 
     * @param args
     */
    public static void main(String[] args) throws Exception{
        initJetty();
        Thread.sleep(1000);
        
        Socket sock = new Socket(host, port);
        
        sock.setSoTimeout(500);

        String body= "<flibs xmlns='http://www.flub.org/schemas/131'><flib uid='12321'><name>foo flib</name> </flib></flibs>";
        //body= "XXX";  // => 501

        int len = body.getBytes("US-ASCII").length;
        
        String msg = "POST " + uri + " HTTP/1.1\r\n" + 
        		"Content-Type: application/xml\r\n" + 
        		"Host: 10.0.2.2:8080\r\n" + 
        		"Content-Length: " + len + "\r\n" + 
        		"Expect: 100-continue\r\n" + 
        		"Connection: Keep-Alive\r\n" +
        		"\r\n" + 
        		body;
        		
         
        
        sock.getOutputStream().write(msg.getBytes("US-ASCII"));

        String response1 = readResponse(sock);  
        int status1 = Integer.parseInt(response1.substring(9, 12));
        assert 401 == status1;
        
        sock.getOutputStream().write(msg.getBytes("US-ASCII"));
        
        
        String response2 = readResponse(sock);        
        System.out.println(response2.substring(0, 100));
  
    
        int status2 = Integer.parseInt(response2.substring(9, 12));
        System.out.println(status2);
        
        assert 401 == status2;
        


    }

    private static String readResponse(Socket sock) throws IOException {
        byte [] response = new byte [4000];
        int n = 0;
        for (int i=0; i< response.length && response[n] >= 0; i++){
            try {
                response[n++] = (byte)sock.getInputStream().read();
            } catch (SocketTimeoutException e) {
                break;
            }
        }
        String sResult = new String(response);
        return sResult;
    }
    
    private static void initJetty() throws SAXException, IOException, MalformedURLException, Exception {

        Server jetty = new Server(8080);
        

        // configure your web application
        WebAppContext appContext = new WebAppContext();
        appContext.setContextPath("/flub");
        
        appContext.addServlet(Jetty400Repro.class, "/servlet/");
        
        appContext.setResourceBase(".");
        
        
        HandlerList handlers = new HandlerList();
        handlers.setHandlers(new Handler[] { appContext, new DefaultHandler() });
        jetty.setHandler(handlers);

        
        jetty.start();


    }

    @Override
    protected void doPost(HttpServletRequest req, HttpServletResponse resp) throws ServletException, IOException {
        req.getInputStream();
        resp.sendError(401);
    }

}
