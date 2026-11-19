package com.zgq.zgqaicodemother.controller;

import com.zgq.zgqaicodemother.common.BaseResponse;
import com.zgq.zgqaicodemother.common.ResultUtils;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 健康检查接口，用于部署探活和前后端联调。
 */
@RestController
@RequestMapping("/health")
public class HealthController {

    /**
     * 返回固定成功标识，表示应用进程已正常启动。
     */
    @GetMapping("/")
    public BaseResponse<String> healthCheck() {
        return ResultUtils.success( "ok");
    }
}

