package com.zgq.zgqaicodemother.controller;

import com.zgq.zgqaicodemother.security.ratelimit.RateLimit;

/**
 * 限流切面测试专用的控制器桩。
 *
 * <p>切面切入点限定在 controller 包下，AspectJProxyFactory 只有代理该包中的类才会应用
 * 真实切入点，因此测试桩必须放在 controller 包，而不是测试类所在的 security 包。
 * 该类只存在于 test 源码目录，不会被生产组件扫描加载。</p>
 */
public class RateLimitTestController {

    /**
     * 没有限流注解的方法，用于验证切面提前放行且不访问限流器。
     */
    public String noAnnotation() {
        return "ok";
    }

    /**
     * 单条默认规则：同时按用户和 IP 限流。
     */
    @RateLimit(key = "aspect-single", permits = 1, windowSeconds = 60)
    public String singleDimension() {
        return "ok";
    }

    /**
     * 只按用户维度限流，用于验证切换 IP 不能绕过用户额度。
     */
    @RateLimit(key = "aspect-user", permits = 1, windowSeconds = 60, byIp = false)
    public String userOnly() {
        return "ok";
    }

    /**
     * 只按 IP 维度限流，用于验证不会读取登录用户。
     */
    @RateLimit(key = "aspect-ip", permits = 1, windowSeconds = 60, byUser = false)
    public String ipOnly() {
        return "ok";
    }

    /**
     * 重复注解：第一条额度较宽，第二条额度为 1，并带自定义提示语。
     */
    @RateLimit(key = "aspect-repeat-first", permits = 10, windowSeconds = 60)
    @RateLimit(key = "aspect-repeat-second", permits = 1, windowSeconds = 60,
            message = "第二个规则触发")
    public String repeatedRules() {
        return "ok";
    }
}
