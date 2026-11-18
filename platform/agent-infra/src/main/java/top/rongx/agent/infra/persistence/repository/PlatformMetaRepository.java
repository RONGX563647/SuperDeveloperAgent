package top.rongx.agent.infra.persistence.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import top.rongx.agent.infra.persistence.entity.PlatformMetaEntity;

public interface PlatformMetaRepository extends JpaRepository<PlatformMetaEntity, String> {
}
