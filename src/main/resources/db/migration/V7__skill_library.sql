CREATE TABLE skill (
  id BIGINT NOT NULL AUTO_INCREMENT,
  skill_uuid CHAR(36) NOT NULL,
  owner_user_id BIGINT NOT NULL,
  name VARCHAR(128) NOT NULL,
  description VARCHAR(1000) NOT NULL,
  skill_md LONGTEXT NOT NULL,
  source_type VARCHAR(16) NOT NULL,
  source_url VARCHAR(1000) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_uuid (skill_uuid),
  UNIQUE KEY uk_skill_owner_name (owner_user_id, name),
  CONSTRAINT fk_skill_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE skill_file (
  id BIGINT NOT NULL AUTO_INCREMENT,
  skill_id BIGINT NOT NULL,
  path VARCHAR(512) NOT NULL,
  content LONGBLOB NOT NULL,
  executable BOOLEAN NOT NULL DEFAULT FALSE,
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_file_path (skill_id, path),
  CONSTRAINT fk_skill_file_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE skill_binding (
  id BIGINT NOT NULL AUTO_INCREMENT,
  owner_user_id BIGINT NOT NULL,
  skill_id BIGINT NOT NULL,
  target_type VARCHAR(16) NOT NULL,
  target_uuid VARCHAR(64) NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY (id),
  UNIQUE KEY uk_skill_binding (skill_id, target_type, target_uuid),
  KEY idx_skill_binding_target (owner_user_id, target_type, target_uuid),
  CONSTRAINT fk_skill_binding_owner FOREIGN KEY (owner_user_id) REFERENCES user(id) ON DELETE CASCADE,
  CONSTRAINT fk_skill_binding_skill FOREIGN KEY (skill_id) REFERENCES skill(id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
