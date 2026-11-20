package com.zgq.zgqaicodemother.security.ratelimit;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * 客户端 IP 解析测试：转发链、端口、非法字符和逐级回退。
 */
class ClientIpResolverTest {

    @Test
    void usesFirstAddressFromForwardedChain() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7, 70.41.3.18, 150.172.238.178");

        assertEquals("203.0.113.7", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void stripsIpv4AndBracketedIpv6Ports() {
        MockHttpServletRequest ipv4Request = requestWithRemoteAddress("10.0.0.9");
        ipv4Request.addHeader("X-Forwarded-For", "203.0.113.7:51234");

        MockHttpServletRequest ipv6Request = requestWithRemoteAddress("10.0.0.9");
        ipv6Request.addHeader("X-Forwarded-For", "[2001:db8::1]:443");

        assertEquals("203.0.113.7", ClientIpResolver.resolve(ipv4Request, "X-Forwarded-For"));
        assertEquals("2001:db8::1", ClientIpResolver.resolve(ipv6Request, "X-Forwarded-For"));
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedValueContainsIllegalCharacters() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7;DROP TABLE users");

        assertEquals("10.0.0.9", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void fallsBackToRemoteAddressWhenForwardedValueIsTooLong() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Forwarded-For", "1".repeat(65) + ".1.1.1");

        assertEquals("10.0.0.9", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void usesRealIpHeaderWhenForwardedForIsMissing() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Real-IP", "198.51.100.7");

        assertEquals("198.51.100.7", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void usesRemoteAddressWhenProxyHeadersAreMissing() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.8");

        assertEquals("10.0.0.8", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void skipsBlankSegmentsBeforeReadingProxyHeader() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Forwarded-For", " , ,198.51.100.8");

        assertEquals("198.51.100.8", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    @Test
    void honorsConfiguredProxyHeaderFirst() {
        MockHttpServletRequest request = requestWithRemoteAddress("10.0.0.9");
        request.addHeader("X-Forwarded-For", "203.0.113.7");
        request.addHeader("CF-Connecting-IP", "198.51.100.9");

        assertEquals("198.51.100.9", ClientIpResolver.resolve(request, "CF-Connecting-IP"));
    }

    @Test
    void returnsUnknownForMissingRequestOrIllegalRemoteAddress() {
        MockHttpServletRequest request = requestWithRemoteAddress("bad address");

        assertEquals("unknown", ClientIpResolver.resolve(null, "X-Forwarded-For"));
        assertEquals("unknown", ClientIpResolver.resolve(request, "X-Forwarded-For"));
    }

    private static MockHttpServletRequest requestWithRemoteAddress(String remoteAddress) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.setRemoteAddr(remoteAddress);
        return request;
    }
}
