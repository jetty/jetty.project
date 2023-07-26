<%
    response.setHeader("main-page-key", "main-page-value");
    application.getRequestDispatcher("/included.jsp").include(request,response);
%>
<h2> Hello, this is the top page.
