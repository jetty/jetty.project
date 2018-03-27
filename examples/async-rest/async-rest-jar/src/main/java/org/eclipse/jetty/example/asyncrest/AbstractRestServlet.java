//
//  ========================================================================
//  Copyright (c) 1995-2018 Mort Bay Consulting Pty. Ltd.
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

package org.eclipse.jetty.example.asyncrest;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URLEncoder;
import java.util.Map;
import java.util.Queue;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

/**
 * Abstract Servlet implementation class AsyncRESTServlet.
 * Enquires ebay REST service for auctions by key word.
 * May be configured with init parameters: <dl>
 * <dt>appid</dt><dd>The eBay application ID to use</dd>
 * </dl>
 * Each request examines the following request parameters:<dl>
 * <dt>items</dt><dd>The keyword to search for</dd>
 * </dl>
 */
public class AbstractRestServlet extends HttpServlet
{
    protected final static String __DEFAULT_APPID = "Webtide81-adf4-4f0a-ad58-d91e41bbe85";
    protected final static String STYLE = 
        "<style type='text/css'>"+
        "  img.thumb:hover {height:50px}"+
        "  img.thumb {vertical-align:text-top}"+
        "  span.red {color: #ff0000}"+
        "  span.green {color: #00ff00}"+
        "  iframe {border: 0px}"+
        "</style>";

    protected final static String ITEMS_PARAM = "items";
    protected final static String APPID_PARAM = "appid";

    protected String _appid;

    @Override
    public void init(ServletConfig servletConfig) throws ServletException
    {
        if (servletConfig.getInitParameter(APPID_PARAM) == null)
            _appid = __DEFAULT_APPID;
        else
            _appid = servletConfig.getInitParameter(APPID_PARAM);
    }


    public static String sanitize(String s)
    {
        if (s==null)
            return null;
        return s.replace("<","?").replace("&","?").replace("\n","?");
    }
    
    protected String restURL(String item) 
    {
        try
        {
            return ("http://open.api.ebay.com/shopping?MaxEntries=3&appid=" + _appid + 
                    "&version=573&siteid=0&callname=FindItems&responseencoding=JSON&QueryKeywords=" + 
                    URLEncoder.encode(item,"UTF-8"));
        }
        catch(Exception e)
        {
            throw new RuntimeException(e);
        }
    }
    
    protected String generateThumbs(Queue<Map<String,String>> results)
    {
        StringBuilder thumbs = new StringBuilder();
        for (Map<String, String> m : results)
        {
            if (!m.containsKey("GalleryURL"))
                continue;
                
            thumbs.append("<a href=\""+m.get("ViewItemURLForNaturalSearch")+"\">");
            thumbs.append("<img class='thumb' border='1px' height='25px'"+
                        " src='"+m.get("GalleryURL")+"'"+
                        " title='"+m.get("Title")+"'"+
                        "/>");
            thumbs.append("</a>&nbsp;");
        }
        return thumbs.toString();
    }

    protected String ms(long nano)
    {
        BigDecimal dec = new BigDecimal(nano);
        return dec.divide(new BigDecimal(1000000L)).setScale(1,RoundingMode.UP).toString();
    }
    
    protected int width(long nano)
    {
        int w=(int)((nano+999999L)/5000000L);
        if (w==0)
            w=2;
        return w;
    }
    
    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws ServletException, IOException
    {
        doGet(request, response);
    }

}
