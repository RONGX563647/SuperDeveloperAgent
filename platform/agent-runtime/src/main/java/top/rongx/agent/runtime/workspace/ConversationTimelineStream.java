package top.rongx.agent.runtime.workspace;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import top.rongx.agent.domain.conversation.ConversationRepository;
import top.rongx.agent.domain.conversation.ConversationTimelineRepository;
import top.rongx.agent.domain.conversation.TimelineEntry;
import top.rongx.agent.domain.error.ErrorCode;
import top.rongx.agent.domain.error.RunException;

/**
 * 会话时间线订阅：断线续传的 SSE 数据源。
 *
 * <p>游标就是时间线的 {@code seq}：客户端带上最后一条已收到的 seq 重连，服务端先补齐
 * 断线期间的新记录，再持续增量推送。数据源是落盘的 {@code conversation_timeline}，
 * 因此换端、刷新页面、进程重启都不会丢事件；内存事件总线只负责实时打字效果。
 *
 * <p>会话进入终态且没有新记录时主动结束流，避免浏览器为已结束的会话保留长连接。
 */
@Service
public class ConversationTimelineStream {

    private static final Logger log = LoggerFactory.getLogger(ConversationTimelineStream.class);
    private static final int MAX_BATCH = 200;

    private final ConversationRepository conversations;
    private final ConversationTimelineRepository timelines;
    private final long pollMillis;

    public ConversationTimelineStream(ConversationRepository conversations,
                                      ConversationTimelineRepository timelines,
                                      @Value("${agent.workspace.timeline.poll-ms:500}") long pollMillis) {
        this.conversations = conversations;
        this.timelines = timelines;
        this.pollMillis = Math.max(100, pollMillis);
    }

    /**
     * 订阅某个会话的时间线。
     *
     * @param afterSeq 客户端最后收到的 seq；0 表示从头开始
     * @throws RunException 会话不存在或无权访问
     */
    public Flux<TimelineEntry> stream(String conversationId, String ownerUserId, long afterSeq) {
        conversations.findByOwner(conversationId, ownerUserId)
                .orElseThrow(() -> new RunException(ErrorCode.RUN_INVALID_INPUT,
                        "会话不存在或无权访问：" + conversationId));
        AtomicLong cursor = new AtomicLong(Math.max(0, afterSeq));
        return Flux.interval(Duration.ZERO, Duration.ofMillis(pollMillis),
                        Schedulers.boundedElastic())
                .map(ignored -> advance(conversationId, cursor))
                .takeUntil(Tick::done)
                .concatMapIterable(Tick::entries);
    }

    private Tick advance(String conversationId, AtomicLong cursor) {
        List<TimelineEntry> entries;
        try {
            entries = timelines.entries(conversationId, cursor.get(), MAX_BATCH);
        } catch (RuntimeException e) {
            // 读库失败时结束订阅，让客户端按游标重连重试，避免半死连接一直转圈
            log.warn("时间线增量读取失败 conversation={}：{}", conversationId, e.getMessage());
            return new Tick(List.of(), true);
        }
        if (!entries.isEmpty()) {
            cursor.set(entries.get(entries.size() - 1).seq());
            return new Tick(entries, false);
        }
        boolean terminal = conversations.find(conversationId)
                .map(conversation -> conversation.status().terminal())
                .orElse(true);
        return new Tick(List.of(), terminal);
    }

    private record Tick(List<TimelineEntry> entries, boolean done) {
    }
}
