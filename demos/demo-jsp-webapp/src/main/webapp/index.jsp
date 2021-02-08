<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.time.LocalDate" %>
<%@ page contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html>
<body>
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
  <a href="/">Main Menu</a>
</body>
</html>
