//========================================================================
//Copyright 2004-2008 Mort Bay Consulting Pty. Ltd.
//------------------------------------------------------------------------
//Licensed under the Apache License, Version 2.0 (the "License");
//you may not use this file except in compliance with the License.
//You may obtain a copy of the License at 
//http://www.apache.org/licenses/LICENSE-2.0
//Unless required by applicable law or agreed to in writing, software
//distributed under the License is distributed on an "AS IS" BASIS,
//WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//See the License for the specific language governing permissions and
//limitations under the License.
//========================================================================

package org.eclipse.jetty.example.asyncrest;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.LinkedList;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.eclipse.jetty.util.ajax.JSON;

/**
 * Servlet implementation class SerialRestServlet
 */
public class SerialRestServlet extends AbstractRestServlet
{   
    protected void doGet(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        long start = System.nanoTime();
        

        String[] keywords=request.getParameter(ITEMS_PARAM).split(",");
        Queue<Map<String,String>> results = new LinkedList<Map<String,String>>();
        
        // make all requests serially
        for (String itemName : keywords)
        {
            URL url = new URL(restURL(itemName));
            
            HttpURLConnection connection = (HttpURLConnection)url.openConnection();
            connection.setRequestMethod("GET");
            
            Map query = (Map)JSON.parse(new BufferedReader(new InputStreamReader(connection.getInputStream())));
            Object[] auctions = (Object[]) query.get("Item");
            if (auctions != null)
            {
                for (Object o : auctions)
                    results.add((Map) o);
            }
        }
        

        // Generate the response
        String thumbs=generateThumbs(results);
        
        response.setContentType("text/html");
        PrintWriter out = response.getWriter();
        out.println("<html><head>");
        out.println(STYLE);
        out.println("</head><body><small>");

        long now = System.nanoTime();
        long total=now-start;

        out.print("<b>Blocking: "+request.getParameter(ITEMS_PARAM)+"</b><br/>");
        out.print("Total Time: "+ms(total)+"ms<br/>");
        out.print("Thread held (<span class='red'>red</span>): "+ms(total)+"ms<br/>");
        
        out.println("<img border='0px' src='asyncrest/red.png'   height='20px' width='"+width(total)+"px'>");
        
        out.println("<hr />");
        out.println(thumbs);
        out.println("</small>");
        out.println("</body></html>");
        out.close();
        
        

    }

    /**
     * @see HttpServlet#doPost(HttpServletRequest request, HttpServletResponse
     *      response)
     */
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

}
