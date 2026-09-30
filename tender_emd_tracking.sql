-- EMD / bid security tracking on tenders. Idempotent: safe to run more than once,
-- on dev and prod. tenders.emd_amount (what the tender demands) already exists.

DROP PROCEDURE IF EXISTS add_col_if_missing;
DELIMITER //
CREATE PROCEDURE add_col_if_missing(IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = 'tenders' AND column_name = col) THEN
    SET @s = CONCAT('ALTER TABLE tenders ADD COLUMN ', col, ' ', ddl);
    PREPARE q FROM @s; EXECUTE q; DEALLOCATE PREPARE q;
  END IF;
END //
DELIMITER ;

CALL add_col_if_missing('emd_status',              'VARCHAR(40) NULL');
CALL add_col_if_missing('emd_paid_amount',         'DECIMAL(18,2) NULL');
CALL add_col_if_missing('emd_paid_date',           'DATE NULL');
CALL add_col_if_missing('emd_payment_mode',        'VARCHAR(60) NULL');
CALL add_col_if_missing('emd_reference',           'VARCHAR(120) NULL');
CALL add_col_if_missing('emd_paid_from_account',   'VARCHAR(200) NULL');
CALL add_col_if_missing('emd_beneficiary_name',    'VARCHAR(200) NULL');
CALL add_col_if_missing('emd_beneficiary_bank',    'VARCHAR(200) NULL');
CALL add_col_if_missing('emd_beneficiary_account', 'VARCHAR(60) NULL');
CALL add_col_if_missing('emd_beneficiary_ifsc',    'VARCHAR(20) NULL');
CALL add_col_if_missing('emd_valid_till',          'DATE NULL');
CALL add_col_if_missing('emd_refund_amount',       'DECIMAL(18,2) NULL');
CALL add_col_if_missing('emd_refund_date',         'DATE NULL');
CALL add_col_if_missing('emd_refund_reference',    'VARCHAR(120) NULL');
CALL add_col_if_missing('emd_refund_account',      'VARCHAR(200) NULL');
CALL add_col_if_missing('emd_notes',               'TEXT NULL');

DROP PROCEDURE add_col_if_missing;
