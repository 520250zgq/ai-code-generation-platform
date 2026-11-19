package com.zgq.zgqaicodemother.ai;

import com.zgq.zgqaicodemother.model.enums.CodeGenTypeEnum;
import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;

/**
 * AI 代码生成类型路由服务。
 *
 * <p>路由只做一次轻量分类，因此直接让 LangChain4j 把模型输出解析成
 * {@link CodeGenTypeEnum}，无需再手写 JSON 解析。方法返回枚举类型时，框架会向模型追加
 * “只能返回枚举值”的格式约束，并在响应后完成结构化解析。</p>
 *
 * <p>该服务不绑定对话记忆：一次应用创建对应一次独立的路由判断，不同用户之间不应共享上下文。</p>
 */
public interface AiCodeGenTypeRoutingService {

    /**
     * 根据用户需求选择最合适的代码生成类型。
     *
     * @param userPrompt 用户输入的应用描述
     * @return 模型判断出的生成类型；模型返回无法解析的内容时由框架抛出解析异常
     */
    @SystemMessage(fromResource = "prompt/codegen-routing-system-prompt.txt")
    CodeGenTypeEnum routeCodeGenType(@UserMessage String userPrompt);
}
