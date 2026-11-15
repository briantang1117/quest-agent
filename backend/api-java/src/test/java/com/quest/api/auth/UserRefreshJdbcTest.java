package com.quest.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import java.time.OffsetDateTime;
import java.sql.Timestamp;
import java.util.Map;
import static org.assertj.core.api.Assertions.*;

class UserRefreshJdbcTest {
    /** H2默认返回OffsetDateTime，模拟PostgreSQL queryForList的Timestamp，防止只在H2下通过。 */
    static class PgLikeJdbc extends JdbcTemplate {
        PgLikeJdbc(javax.sql.DataSource source){super(source);}
        @Override protected RowMapper<Map<String,Object>> getColumnMapRowMapper(){
            var original=super.getColumnMapRowMapper();
            return (rs,n)->{var values=original.mapRow(rs,n);values.replaceAll((key,value)->value instanceof OffsetDateTime time?Timestamp.from(time.toInstant()):value);return values;};
        }
    }
    @Test void refreshWorksWithPostgresLikeUntypedTimestampsAndKeepsAbsoluteExpiry(){
        var source=new DriverManagerDataSource("jdbc:h2:mem:refresh-jdbc;MODE=PostgreSQL;DB_CLOSE_DELAY=-1","sa","");
        var db=new PgLikeJdbc(source);
        db.execute("CREATE TABLE app_user(id BIGINT PRIMARY KEY,username VARCHAR(64),password_hash VARCHAR(128),display_name VARCHAR(128),account_type VARCHAR(16),status VARCHAR(16),must_change_password BOOLEAN,created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,last_login_at TIMESTAMP WITH TIME ZONE,updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP)");
        db.execute("CREATE TABLE auth_session(id UUID PRIMARY KEY,user_id BIGINT,created_at TIMESTAMP WITH TIME ZONE,expires_at TIMESTAMP WITH TIME ZONE,revoked_at TIMESTAMP WITH TIME ZONE)");
        db.execute("CREATE TABLE auth_refresh(session_id UUID PRIMARY KEY,token_hash VARCHAR(64),expires_at TIMESTAMP WITH TIME ZONE,remember BOOLEAN)");
        String password="Initial-password-123";
        db.update("INSERT INTO app_user(id,username,password_hash,display_name,account_type,status,must_change_password) VALUES (1,'alice',?,'alice','student','active',FALSE)",new BCryptPasswordEncoder().encode(password));
        var beans=new StaticListableBeanFactory();beans.addBean("jdbc",db);beans.addBean("manager",new DataSourceTransactionManager(source));
        var users=new UserService(beans.getBeanProvider(JdbcTemplate.class),beans.getBeanProvider(PlatformTransactionManager.class),"postgresql","refresh-jdbc-test-secret-at-least-32-bytes","quest-api","quest-web");
        var issued=users.loginSession("alice",password,"test",true);
        assertThat(db.queryForMap("SELECT expires_at FROM auth_refresh").get("expires_at")).isInstanceOf(Timestamp.class);
        var refreshed=users.refresh(issued.refresh());
        assertThat(refreshed.refreshExpires()).isEqualTo(issued.refreshExpires());
        assertThat(refreshed.remember()).isTrue();
        assertThat(refreshed.refresh()).isNotEqualTo(issued.refresh());
        assertThat(refreshed.token().user().id()).isEqualTo("1");
        assertThatThrownBy(()->users.refresh(issued.refresh())).isInstanceOf(UserFailure.class);
    }
}
