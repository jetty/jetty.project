<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.time.LocalDate" %>
<%@ page contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html>
  <head>
    <link rel="stylesheet" href="demo.css"/>
  </head>
<body>

    <div class="topnav">
      <a class="menu" href="http://localhost:8080/">Demo Home</a>
      <a class="menu" href="https://github.com/eclipse/jetty.project/tree/jetty-11.0.x/demos/demo-jsp-webapp">Source</a>
      <a class="menu" href="https://www.eclipse.org/jetty/">Jetty Project Home</a>
      <a class="menu" href="https://www.eclipse.org/jetty/documentation/current/">Documentation</a>
      <a class="menu" href="https://webtide.com">Commercial Support</a>
    </div>

    <div class="content">
      <center>
          <span style="color:red; font-style:italic; font-weight:bold">Demo Web Application Only - Do NOT Deploy in Production</span>
      </center>
      <h1>Eclipse Jetty JSP Demo Webapp</h1>
     <p>
       This is a demo webapp for the <a href="http://www.eclipse.org/jetty/">Eclipse Jetty HTTP Server and Servlet Container</a>. It was added into your <code>$JETTY_BASE/webapps</code> directory.
     </p>

     <h2>JSP Examples on <%= DateTimeFormatter.ofPattern("d MMMM yyyy").format(LocalDate.now()) %></h2>
      <ul>
        <li><a href="dump.jsp">JSP with Embedded Java</a><br/>
        <li><a href="bean1.jsp">JSP with Beans</a><br/>
        <li><a href="tag.jsp">JSP with BodyTag</a><br/>
        <li><a href="tag2.jsp">JSP with SimpleTag</a><br/>
        <li><a href="tagfile.jsp">JSP with Tag File</a><br/>
        <li><a href="expr.jsp?A=1">JSP with Tag Expression</a><br/>
        <li><a href="jstl.jsp">JSTL Expression</a><br/>
        <li><a href="foo/">Mapping to &lt;jsp-file&gt;</a><br/>
      </ul>
    </div>

    <div class="footer">
      <center><a href="https://www.eclipse.org/jetty"><img style="border:0" src="small_powered_by.gif"/></a></center>
    </div>
</body>
</html>
