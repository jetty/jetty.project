<%
  application.getRequestDispatcher("/included.jsp").include(request,response);
  response.setHeader("main-page-key", "main-page-value");
%>
<h2> Hello, this is the top page.
