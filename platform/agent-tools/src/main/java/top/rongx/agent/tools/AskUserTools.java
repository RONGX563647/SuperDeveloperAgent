package top.rongx.agent.tools;

import io.agentscope.core.tool.Tool;
import io.agentscope.core.tool.ToolParam;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * 向用户提问并给出可点选的答案（对话里的选项卡片）。
 *
 * <p>与让模型在正文里「列几个选项」不同：这里由运行时把入参变成 {@code ask.requested}
 * 事件，前端渲染成可点击的卡片；用户点选后前端把它作为下一条用户消息发回来，
 * 因此不需要额外的「确认结果」协议。
 *
 * <p>本工具不产生任何副作用（只读），且约束模型在提问后结束本轮回复，
 * 避免「卡还没点、模型已经自己替用户选了」。
 */
@Component
public class AskUserTools {

    @Tool(name = "ask_user",
            description = "Ask the user to pick among 2-5 options.",
            readOnly = true, concurrencySafe = true)
    public String ask(
            @ToolParam(name = "question", description = "要问用户的问题，一句话，包含判断依据")
            String question,
            @ToolParam(name = "options",
                    description = "2-5 个候选答案，简短、互斥；用户点击后会把该文案发回给你")
            List<String> options,
            @ToolParam(name = "multiple", required = false,
                    description = "是否允许多选，默认 false") Boolean multiple,
            @ToolParam(name = "allow_text", required = false,
                    description = "是否允许用户自行输入答案，默认 true") Boolean allowText) {
        if (question == null || question.isBlank()) {
            return "err: question 不能为空";
        }
        int count = options == null ? 0 : options.size();
        if (count < 2) {
            return "err: options 至少需要 2 个候选，当前 " + count + " 个";
        }
        return "已向用户展示选项卡片：" + question
                + "\n现在结束本轮回复（只说明在等用户选择），不要再调用任何工具，"
                + "也不要替用户做选择。";
    }
}
