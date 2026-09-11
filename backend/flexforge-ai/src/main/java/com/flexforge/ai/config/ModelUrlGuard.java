package com.flexforge.ai.config;

import com.flexforge.common.PublicApi;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;

/**
 * 保存路径出站 URL 守卫（P26，docs/13 §3.6-6）：scheme 仅 http/https；host 拒绝
 * localhost、环回、私有（site-local）、链路本地、任意本地、多播与保留段——仅对
 * IP 字面量做静态判定，非字面量主机名不做运行时 DNS（不可解析交探活报错）。
 * 作用于管理员 UI 保存路径（远端可控面）；环境变量配置（运维信任根）不经守卫。
 */
@PublicApi
public final class ModelUrlGuard {

    private ModelUrlGuard() {
    }

    /** 不可作为出站目标时抛 IllegalArgumentException（400 可诊断）。 */
    public static void requireFetchable(String baseUrl) {
        URI uri = requireParsable(baseUrl);
        requireHttpScheme(uri);
        String host = literalHostOf(uri);
        if ("localhost".equalsIgnoreCase(host)) {
            throw new IllegalArgumentException("base-url 不允许指向本机（localhost）");
        }
        if (isIpLiteral(host)) {
            requirePublicLiteral(host);
        }
    }

    private static URI requireParsable(String baseUrl) {
        try {
            return URI.create(baseUrl == null ? "" : baseUrl.strip());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("base-url 非法，无法发起请求");
        }
    }

    private static void requireHttpScheme(URI uri) {
        String scheme = uri.getScheme();
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            throw new IllegalArgumentException("base-url 必须以 http:// 或 https:// 开头");
        }
    }

    /** 去掉 IPv6 方括号后的 host 字面量（null 含"非法 IPv4 形态"——URI 对此类 authority 不产 host）。 */
    private static String literalHostOf(URI uri) {
        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            throw new IllegalArgumentException("base-url 主机名缺失或非法");
        }
        return host.startsWith("[") && host.endsWith("]")
                ? host.substring(1, host.length() - 1) : host;
    }

    private static boolean isIpLiteral(String host) {
        return host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
    }

    /** IP 字面量必须可解析且非环回/私有/链路本地/任意本地/多播/保留段。 */
    private static void requirePublicLiteral(String host) {
        InetAddress address;
        try {
            address = InetAddress.getByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("base-url 主机地址非法: " + host);
        }
        if (address.isLoopbackAddress() || address.isSiteLocalAddress()
                || address.isLinkLocalAddress() || address.isAnyLocalAddress()
                || address.isMulticastAddress() || isReserved(address)) {
            throw new IllegalArgumentException(
                    "base-url 不允许指向环回/私有/保留地址: " + host);
        }
    }

    /** 保留段补充：IPv4 的 0/8 与 240+/4（多播已由 isMulticastAddress 覆盖）；IPv6 唯一本地 fc00::/7。 */
    private static boolean isReserved(InetAddress address) {
        int first = address.getAddress()[0] & 0xff;
        if (address instanceof Inet4Address) {
            return first == 0 || first >= 240;
        }
        if (address instanceof Inet6Address) {
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }
}
