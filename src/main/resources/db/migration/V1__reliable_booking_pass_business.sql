-- Existing schemas are baselined at version 0. Ordinary cancelled orders remain reorderable.
ALTER TABLE tb_booking_pass_order
  ADD COLUMN pay_value_snapshot BIGINT NULL,
  ADD COLUMN title_snapshot VARCHAR(100) NULL,
  ADD COLUMN rules_snapshot VARCHAR(1000) NULL,
  ADD COLUMN booking_pass_type_snapshot INT NULL,
  ADD INDEX idx_order_owner_time (user_id, create_time, id),
  ADD INDEX idx_order_booking_pass_owner (booking_pass_id, user_id),
  ADD INDEX idx_order_status_time (status, create_time);
UPDATE tb_booking_pass_order o JOIN tb_booking_pass v ON v.id=o.booking_pass_id
SET o.pay_value_snapshot=v.pay_value, o.title_snapshot=v.title,
    o.rules_snapshot=v.rules, o.booking_pass_type_snapshot=v.type;

CREATE TABLE tb_golden_slot_reservation (
  order_id BIGINT NOT NULL PRIMARY KEY,
  booking_pass_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  state VARCHAR(24) NOT NULL DEFAULT 'PROCESSING',
  active_user BIGINT GENERATED ALWAYS AS (CASE WHEN state='FAILED' THEN NULL ELSE user_id END) STORED,
  attempts INT NOT NULL DEFAULT 0,
  reason VARCHAR(500) NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  updated_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3) ON UPDATE CURRENT_TIMESTAMP(3),
  UNIQUE KEY uq_goldenSlot_booking_pass_user (booking_pass_id,active_user),
  KEY idx_reservation_state (state,updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO tb_golden_slot_reservation(order_id,booking_pass_id,user_id,state)
SELECT o.id,o.booking_pass_id,o.user_id,'SUCCESS' FROM tb_booking_pass_order o
JOIN tb_booking_pass v ON v.id=o.booking_pass_id WHERE v.type=1 ORDER BY o.create_time,o.id;

CREATE TABLE tb_golden_slot_activity (
  booking_pass_id BIGINT PRIMARY KEY,
  total_stock BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT INTO tb_golden_slot_activity(booking_pass_id,total_stock)
SELECT sv.booking_pass_id,sv.stock+(SELECT COUNT(*) FROM tb_booking_pass_order o WHERE o.booking_pass_id=sv.booking_pass_id)
FROM tb_golden_slot_booking_pass sv;

CREATE TABLE tb_golden_slot_dead_letter (
  message_id VARCHAR(64) PRIMARY KEY,
  order_id BIGINT NULL,
  payload TEXT NOT NULL,
  reason VARCHAR(500) NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tb_business_outbox (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  kind VARCHAR(32) NOT NULL,
  aggregate_id BIGINT NOT NULL,
  user_id BIGINT NULL,
  cursor_id BIGINT NOT NULL DEFAULT 0,
  state VARCHAR(16) NOT NULL DEFAULT 'PENDING',
  attempts INT NOT NULL DEFAULT 0,
  next_attempt_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  KEY idx_outbox_due (state,next_attempt_at,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE tb_follow_edge (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  follow_user_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
  UNIQUE KEY uq_follow_edge (user_id,follow_user_id),
  KEY idx_follow_fanout (follow_user_id,id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
INSERT IGNORE INTO tb_follow_edge(user_id,follow_user_id,created_at)
SELECT user_id,follow_user_id,MIN(create_time) FROM tb_follow GROUP BY user_id,follow_user_id;
CREATE TABLE tb_post_like (
  post_id BIGINT NOT NULL,
  user_id BIGINT NOT NULL,
  created_at TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
  PRIMARY KEY(post_id,user_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
-- Historical demo like counters have no durable user attribution; keep them as baseline.
CREATE TABLE tb_media_owner (
  path VARCHAR(255) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
CREATE TABLE tb_operation_audit (
  id BIGINT AUTO_INCREMENT PRIMARY KEY,
  user_id BIGINT NULL,
  method VARCHAR(8) NOT NULL,
  path VARCHAR(255) NOT NULL,
  http_status INT NOT NULL,
  created_at TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
ALTER TABLE tb_venue ADD INDEX idx_sport_type_score (type_id,score,avg_price,id),
  ADD INDEX idx_sport_type_price (type_id,avg_price,score,id),
  ADD INDEX idx_sport_type_coords (type_id,y,x);
ALTER TABLE tb_booking_pass ADD INDEX idx_booking_pass_venue_status (venue_id,status,type,id);
ALTER TABLE tb_post ADD INDEX idx_post_venue_time (venue_id,create_time,id),
  ADD INDEX idx_post_hot (liked,id), ADD INDEX idx_post_author_time (user_id,create_time,id);
CREATE TABLE tb_ordinary_order_slot (
  user_id BIGINT NOT NULL,
  booking_pass_id BIGINT NOT NULL,
  PRIMARY KEY(user_id,booking_pass_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
