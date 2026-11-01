ALTER TABLE tb_golden_slot_activity ADD COLUMN has_published TINYINT NOT NULL DEFAULT 0;
UPDATE tb_golden_slot_activity a SET has_published=1
WHERE EXISTS (SELECT 1 FROM tb_golden_slot_reservation r WHERE r.booking_pass_id=a.booking_pass_id);
