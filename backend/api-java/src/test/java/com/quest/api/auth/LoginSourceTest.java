package com.quest.api.auth;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import static org.assertj.core.api.Assertions.*;

class LoginSourceTest {
    @Test void untrustedHeadersCannotSpoofSource() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("192.0.2.1");
        request.addHeader("X-Forwarded-For", "198.51.100.1"); request.addHeader("X-Quest-Client-IP", "198.51.100.2");
        assertThat(new LoginSource("").source(request)).isEqualTo("192.0.2.1");
    }
    @Test void onlyExplicitProxyCanSupplyOneOverwrittenLiteral() {
        var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1");
        var source = new LoginSource("127.0.0.1");
        assertThatThrownBy(() -> source.source(request)).isInstanceOf(IllegalArgumentException.class);
        request.addHeader("X-Quest-Client-IP", "198.51.100.1");
        assertThat(source.source(request)).isEqualTo("198.51.100.1");
        request.addHeader("X-Quest-Client-IP", "198.51.100.2");
        assertThatThrownBy(() -> source.source(request)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void neverResolvesHostnamesOrAcceptsForwardingLists() {
        var source = new LoginSource("127.0.0.1");
        for (String value : new String[]{"localhost", "198.51.100.1, 198.51.100.2", "198.51.100.1:8080", "999.1.1.1"}) {
            var request = new MockHttpServletRequest(); request.setRemoteAddr("127.0.0.1"); request.addHeader("X-Quest-Client-IP", value);
            assertThatThrownBy(() -> source.source(request)).describedAs(value).isInstanceOf(IllegalArgumentException.class);
        }
    }
}
