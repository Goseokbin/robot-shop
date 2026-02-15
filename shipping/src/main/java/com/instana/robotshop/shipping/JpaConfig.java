package com.instana.robotshop.shipping;

import com.zaxxer.hikari.HikariDataSource;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import javax.sql.DataSource;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;

@Configuration
public class JpaConfig {
    private static final Logger logger = LoggerFactory.getLogger(JpaConfig.class);

    @Bean
    public DataSource getDataSource() {
        String dbHost = System.getenv("DB_HOST") == null ? "mysql" : System.getenv("DB_HOST");
        String jdbcUrl = String.format("jdbc:mysql://%s/cities?useSSL=false&autoReconnect=true", dbHost);

        logger.info("jdbc url {}", jdbcUrl);

        HikariDataSource ds = new HikariDataSource();
        ds.setDriverClassName("com.mysql.jdbc.Driver");
        ds.setJdbcUrl(jdbcUrl);
        ds.setUsername("shipping");
        ds.setPassword("secret");
        ds.setMaximumPoolSize(5);
        ds.setConnectionTimeout(5000);

        logger.info("HikariCP pool: maxSize={}, connectionTimeout={}ms", ds.getMaximumPoolSize(), ds.getConnectionTimeout());

        return ds;
    }
}
