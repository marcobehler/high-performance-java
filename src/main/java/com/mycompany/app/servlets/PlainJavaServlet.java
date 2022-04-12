package com.mycompany.app.servlets;

import com.mycompany.app.model.TaxRate;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.security.SecureRandom;


public class PlainJavaServlet extends HttpServlet {

    SecureRandom random = new SecureRandom();

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        final TaxRate rate = new TaxRate("German VAT", random.nextDouble());

        resp.setContentType("application/json");
        resp.getWriter().write(rate.toString());
    }
}
