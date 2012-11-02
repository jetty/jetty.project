package com.acme;

import java.io.IOException;
import java.io.PrintWriter;

import javax.servlet.ServletException;
import javax.servlet.annotation.HttpConstraint;
import javax.servlet.annotation.ServletSecurity;
import javax.servlet.annotation.WebServlet;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

@WebServlet(urlPatterns="/sec/*")
@ServletSecurity(@HttpConstraint(rolesAllowed="admin"))
public class SecuredServlet extends HttpServlet 
{


    protected void doPost(HttpServletRequest req, HttpServletResponse resp)
    throws ServletException, IOException 
    {
        PrintWriter writer = resp.getWriter();
        writer.println( "<html>");
        writer.println( "<body>");
        writer.println("<h1>@ServletSecurity</h2>");
        writer.println("<pre>");
        writer.println("@ServletSecurity");
        writer.println("public class SecuredServlet");
        writer.println("</pre>");
        writer.println("<br/><b>Result: "+true+"</b>");
        String context = getServletConfig().getServletContext().getContextPath();
        if (!context.endsWith("/"))
            context += "/";
        writer.println("<p><A HREF=\""+context+"logout.jsp\">Logout</A></p>");
        writer.println( "</body>");
        writer.println( "</html>");
        writer.flush();
        writer.close();
    }
}
