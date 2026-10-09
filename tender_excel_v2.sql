-- Tender Excel import v2 (TENDER-XLSX-2): the tender / processing fee, kept
-- separate from the EMD, and the bidder tier on eligibility rows (empanelment /
-- EOI tenders: Category A / B / C). Idempotent: safe to run more than once, on
-- dev and prod. Run before restarting the backend (ddl-auto=validate).

DROP PROCEDURE IF EXISTS add_col_if_missing;
DELIMITER //
CREATE PROCEDURE add_col_if_missing(IN tbl VARCHAR(64), IN col VARCHAR(64), IN ddl VARCHAR(255))
BEGIN
  IF NOT EXISTS (SELECT 1 FROM information_schema.columns
                 WHERE table_schema = DATABASE() AND table_name = tbl AND column_name = col) THEN
    SET @s = CONCAT('ALTER TABLE ', tbl, ' ADD COLUMN ', col, ' ', ddl);
    PREPARE q FROM @s; EXECUTE q; DEALLOCATE PREPARE q;
  END IF;
END //
DELIMITER ;

CALL add_col_if_missing('tenders', 'fee_amount',              'DECIMAL(18,2) NULL');
CALL add_col_if_missing('tenders', 'fee_refundable',          'VARCHAR(10) NULL');
CALL add_col_if_missing('tenders', 'fee_beneficiary_name',    'VARCHAR(200) NULL');
CALL add_col_if_missing('tenders', 'fee_beneficiary_bank',    'VARCHAR(200) NULL');
CALL add_col_if_missing('tenders', 'fee_beneficiary_account', 'VARCHAR(60) NULL');
CALL add_col_if_missing('tenders', 'fee_beneficiary_ifsc',    'VARCHAR(20) NULL');

CALL add_col_if_missing('tender_eligibility_criteria', 'tier', 'VARCHAR(60) NULL');

DROP PROCEDURE add_col_if_missing;
