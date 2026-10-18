package com.multiagent.discuss.db.repo;

import com.multiagent.discuss.db.entity.UserEntity;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface UserRepository extends JpaRepository<UserEntity, Long> {

    /** 不区分大小写地按账号查找（用户输入时容易混大小写） */
    Optional<UserEntity> findByLoginIdIgnoreCase(String loginId);

    /** 按 token 查找当前登录用户（filter 用） */
    Optional<UserEntity> findByAuthToken(String authToken);

    boolean existsByLoginIdIgnoreCase(String loginId);
}