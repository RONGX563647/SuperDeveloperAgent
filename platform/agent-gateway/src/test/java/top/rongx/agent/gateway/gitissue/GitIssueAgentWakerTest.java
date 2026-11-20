package top.rongx.agent.gateway.gitissue;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import top.rongx.agent.domain.gitissue.GitIssue;
import top.rongx.agent.domain.port.ProjectRepository;
import top.rongx.agent.domain.port.UserRolePort;
import top.rongx.agent.domain.project.Project;
import top.rongx.agent.domain.project.ProjectContext;
import top.rongx.agent.runtime.ChatService;

/** GitIssueAgentWaker 回归：BUG-48（readOnly 角色推导）+ BUG-50（显式指令/有界并发/不自行关单）。 */
class GitIssueAgentWakerTest {

    private static GitIssue issue(String userId) {
        return new GitIssue("task-1", "BUG-1", userId, "proj-1", "GITEE", "o/r", "12",
                "标题", "正文", "OPEN", "BUG", "P1", "", "", "GIT", "SYNCED",
                "", "", 0L, 0L, "");
    }

    private static Project project() {
        return new Project("proj-1", "项目", "https://example.com/o/r.git", "gitee",
                "generic-java", 18000, 18100);
    }

    private static ChatService chat() {
        ChatService chat = mock(ChatService.class);
        when(chat.stream(any(ProjectContext.class), anyString(), anyString(), eq("chat")))
                .thenReturn(Flux.empty());
        return chat;
    }

    private static ProjectRepository repos() {
        ProjectRepository repos = mock(ProjectRepository.class);
        when(repos.findById("proj-1")).thenReturn(Optional.of(project()));
        when(repos.findEnvs("proj-1")).thenReturn(List.of());
        return repos;
    }

    private static GitIssueAgentWaker waker(ChatService chat, UserRolePort roles) {
        return new GitIssueAgentWaker(chat, repos(), roles);
    }

    private static UserRolePort roles(boolean viewerIsReadOnly) {
        UserRolePort roles = mock(UserRolePort.class);
        when(roles.isReadOnly(anyString())).thenReturn(viewerIsReadOnly);
        return roles;
    }

    private static ProjectContext captured(ChatService chat) {
        ArgumentCaptor<ProjectContext> captor = ArgumentCaptor.forClass(ProjectContext.class);
        verify(chat, timeout(5000)).stream(captor.capture(), anyString(), anyString(), eq("chat"));
        return captor.getValue();
    }

    // ------------------------------------------------------------ BUG-48

    @Test
    void viewerIssueWakeIsReadOnly() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("viewer-1"), "/fix the bug");
        ProjectContext ctx = captured(chat);
        assertTrue(ctx.readOnly(), "viewer 触发的唤醒必须只读");
    }

    @Test
    void nonViewerIssueWakeKeepsWritableContext() {
        ChatService chat = chat();
        waker(chat, roles(false)).wakeIfCommand(issue("devops-1"), "/fix the bug");
        ProjectContext ctx = captured(chat);
        assertFalse(ctx.readOnly(), "非 viewer 的显式指令唤醒应保持非只读");
    }

    @Test
    void unknownUserFailsClosedToReadOnly() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("ghost"), "排查：构建失败");
        ProjectContext ctx = captured(chat);
        assertTrue(ctx.readOnly(), "未知角色按只读处理（fail-closed）");
    }

    // ------------------------------------------------------------ BUG-50：显式指令判定

    @Test
    void explicitSlashCommandTriggers() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("u1"), "/fix this please");
        verify(chat, timeout(5000)).stream(any(), anyString(), anyString(), eq("chat"));
    }

    @Test
    void explicitColonLeadingCommandTriggers() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("u1"), "排查：构建失败");
        verify(chat, timeout(5000)).stream(any(), anyString(), anyString(), eq("chat"));
    }

    @Test
    void explicitMentionTriggers() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("u1"), "@agent 请看下这个问题");
        verify(chat, timeout(5000)).stream(any(), anyString(), anyString(), eq("chat"));
    }

    @Test
    void weakWordsDoNotTrigger() {
        ChatService chat = chat();
        GitIssueAgentWaker waker = waker(chat, roles(true));
        waker.wakeIfCommand(issue("u1"), "Please review this PR");
        waker.wakeIfCommand(issue("u1"), "这个方案我需要再分析一下");
        waker.wakeIfCommand(issue("u1"), "看看就行，不用改");
        waker.wakeIfCommand(issue("u1"), "CI is handled, ignore");
        waker.wakeIfCommand(issue("u1"), "fixed typo in README");
        waker.wakeIfCommand(issue("u1"), "帮我处理一下这个");
        waker.wakeIfCommand(issue("u1"), "先看看，回头定位"); // 弱词夹带强词但不在指令位
        verify(chat, never()).stream(any(), anyString(), anyString(), anyString());
    }

    @Test
    void commandWordMidSentenceDoesNotTrigger() {
        ChatService chat = chat();
        waker(chat, roles(true)).wakeIfCommand(issue("u1"), "我会 fix 它的");
        verify(chat, never()).stream(any(), anyString(), anyString(), anyString());
    }

    @Test
    void matchReasonClassification() {
        assertEquals("explicit-mention", GitIssueAgentWaker.matchReason("@agent fix it"));
        assertEquals("explicit-command", GitIssueAgentWaker.matchReason("/fix it"));
        assertEquals("explicit-command", GitIssueAgentWaker.matchReason("debug: why"));
        assertNull(GitIssueAgentWaker.matchReason("please analyze this"));
        assertNull(GitIssueAgentWaker.matchReason("看看这个思路"));
        assertNull(GitIssueAgentWaker.matchReason("I will fix it"));
    }

    // ------------------------------------------------------------ BUG-50：不自行关单

    @Test
    void promptDoesNotAuthorizeSelfClose() {
        ChatService chat = chat();
        AtomicReference<String> prompt = new AtomicReference<>();
        when(chat.stream(any(ProjectContext.class), anyString(), anyString(), eq("chat")))
                .thenAnswer(inv -> {
                    prompt.set(inv.getArgument(2));
                    return Flux.empty();
                });
        waker(chat, roles(true)).wakeIfCommand(issue("u1"), "/fix it");
        verify(chat, timeout(5000)).stream(any(), anyString(), anyString(), eq("chat"));
        String text = prompt.get();
        assertTrue(text != null && !text.contains("close_git_issue"),
                "prompt 不得授权 Agent 自行关闭任务");
    }

    // ------------------------------------------------------------ BUG-50：并发有界

    @Test
    void concurrentWakesAreBounded() throws Exception {
        ChatService chat = mock(ChatService.class);
        CountDownLatch release = new CountDownLatch(1);
        AtomicInteger inside = new AtomicInteger();
        AtomicInteger maxInside = new AtomicInteger();
        when(chat.stream(any(ProjectContext.class), anyString(), anyString(), eq("chat")))
                .thenAnswer(inv -> {
                    int now = inside.incrementAndGet();
                    maxInside.accumulateAndGet(now, Math::max);
                    release.await(10, TimeUnit.SECONDS);
                    inside.decrementAndGet();
                    return Flux.empty();
                });
        GitIssueAgentWaker waker = waker(chat, roles(true));
        // 6 个显式指令评论同时投递
        for (int i = 0; i < 6; i++) {
            waker.wakeIfCommand(issue("u1"), "/fix issue " + i);
        }
        verify(chat, timeout(5000).times(GitIssueAgentWaker.MAX_CONCURRENT_WAKES))
                .stream(any(), anyString(), anyString(), eq("chat"));
        Thread.sleep(500); // 等池内其他任务尝试进入
        release.countDown();
        verify(chat, timeout(10000).atLeast(6)).stream(any(), anyString(), anyString(), eq("chat"));
        assertTrue(maxInside.get() <= GitIssueAgentWaker.MAX_CONCURRENT_WAKES,
                "同时在跑的唤醒不得超过上限，实测 " + maxInside.get());
    }
}
