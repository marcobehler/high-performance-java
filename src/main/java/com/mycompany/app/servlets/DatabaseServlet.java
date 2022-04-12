package com.mycompany.app.servlets;

import com.mycompany.app.util.MyProperties;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

public class DatabaseServlet extends HttpServlet {

    private HikariDataSource ds;

    static final String sql = "SELECT JSON_ARRAYAGG(JSON_OBJECT('name', name, 'id', id)) as result from tax_rates";

    @Override
    public void init() {
        ds = createConnectionPool();
    }

    @Override
    protected void doGet(HttpServletRequest req, HttpServletResponse resp) throws IOException {
        resp.setContentType("application/json");

        try (Connection connection = ds.getConnection();
             PreparedStatement statement = connection.prepareStatement(sql)) {
            ResultSet resultSet = statement.executeQuery();

            while (resultSet.next()) {
                resultSet.getBinaryStream("result")
                        .transferTo(resp.getOutputStream());
            }

        } catch (SQLException e) {
            e.printStackTrace();
        }
    }


    private HikariDataSource createConnectionPool() {
        HikariConfig config = new HikariConfig();
        final String hikariSize_ = MyProperties.INSTANCE.getProperty("hikari.size");
        if (hikariSize_ != null) {
            final Integer hikariSize = Integer.valueOf(hikariSize_);

            System.out.println("Setting HikariCP to " + hikariSize + " connections");
            config.setMinimumIdle(hikariSize);
            config.setMaximumPoolSize(hikariSize);
        }
        config.setJdbcUrl(MyProperties.INSTANCE.getProperty("jdbc.url"));
        config.setUsername(MyProperties.INSTANCE.getProperty("jdbc.user"));
        config.setPassword(MyProperties.INSTANCE.getProperty("jdbc.password"));
        config.addDataSourceProperty("cachePrepStmts", "true");
        config.addDataSourceProperty("prepStmtCacheSize", "250");
        config.addDataSourceProperty("prepStmtCacheSqlLimit", "2048");
        config.addDataSourceProperty("useServerPrepStmts","true");
        config.addDataSourceProperty("useLocalSessionState","true");
        config.addDataSourceProperty("rewriteBatchedStatements","true");
        config.addDataSourceProperty("cacheResultSetMetadata","true");
        config.addDataSourceProperty("cacheServerConfiguration","true");
        config.addDataSourceProperty("elideSetAutoCommits","true");
        config.addDataSourceProperty("maintainTimeStats", "false");
        
        return new HikariDataSource(config);
    }

}