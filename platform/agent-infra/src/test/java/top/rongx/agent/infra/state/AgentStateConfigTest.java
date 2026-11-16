package top.rongx.agent.infra.state;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** 会话状态目录权限：POSIX 为 700，Windows 为「仅属主」ACL。 */
class AgentStateConfigTest {

    @TempDir
    Path tmp;

    @Test
    void tightensDirectoryPermissions() throws Exception {
        Path state = tmp.resolve("state");
        Files.createDirectories(state);
        Path existing = Files.writeString(state.resolve("old.json"), "{}");

        Path dir = AgentStateConfig.stateDirectory(state.toString(), tmp.toString());
        assertEquals(state.toAbsolutePath().normalize(), dir);

        if (FileSystems.getDefault().supportedFileAttributeViews().contains("posix")) {
            assertEquals("rwx------",
                    PosixFilePermissions.toString(Files.getPosixFilePermissions(dir)));
            return;
        }

        AclFileAttributeView view = Files.getFileAttributeView(dir, AclFileAttributeView.class);
        assertNotNull(view, "Windows 应支持 ACL 视图");
        List<AclEntry> acl = view.getAcl();
        assertEquals(1, acl.size(), "目录 DACL 只应保留属主一条");
        assertEquals(view.getOwner(), acl.get(0).principal());

        AclFileAttributeView child = Files.getFileAttributeView(existing, AclFileAttributeView.class);
        assertNotNull(child);
        assertEquals(1, child.getAcl().size(), "已存在的状态文件也应被收紧");
    }
}
