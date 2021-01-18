<%@ page import="java.time.format.DateTimeFormatter" %>
<%@ page import="java.time.LocalDate" %>
<%@ page contentType="text/html; charset=UTF-8" %>
<!DOCTYPE html>
<html>
  <body>
    <h2>Hello World on <%= DateTimeFormatter.ofPattern("d MMMM yyyy").format(LocalDate.now()) %>!</h2>
  </body>
</html>
