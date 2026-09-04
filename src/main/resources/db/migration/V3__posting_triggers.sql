-- V3: posting integrity as a deferred aggregate constraint. A row CHECK sees
-- only its own row, so cross-row rules (totals, counts, line closure,
-- overdraft) live here instead. The triggers are CONSTRAINT TRIGGERs,
-- DEFERRABLE INITIALLY DEFERRED: they observe the final transaction state at
-- COMMIT, never intermediate statements. Firing from both tables closes two
-- holes: the posting trigger catches a zero-entry header, the entry trigger
-- catches a later append to a committed posting. The immutable declared
-- entry_count closes the aggregate: even another balanced pair makes the
-- actual count differ and the commit fails.
--
-- Java still validates first for useful errors; this trigger is the
-- independent last line of defense and is tested through raw JDBC with an
-- explicit commit. All failures raise SQLSTATE 23514 with a stable
-- ledger_posting_* message prefix; tests key off both, never on prose.

CREATE OR REPLACE FUNCTION ledger_check_posting() RETURNS trigger AS $$
DECLARE
    posting        uuid;
    declared_cnt   smallint;
    kind           varchar(8);
    posting_ccy    varchar(3);
    reverses       uuid;
    actual_cnt     integer;
    distinct_lines integer;
    min_line       smallint;
    max_line       smallint;
    distinct_accts integer;
    signed_total   numeric;
    target_kind    varchar(8);
    target_ccy     varchar(3);
    acct           uuid;
    acct_balance   numeric;
BEGIN
    IF TG_TABLE_NAME = 'ledger_posting' THEN
        posting := NEW.id;
    ELSE
        posting := NEW.posting_id;
    END IF;

    SELECT p.entry_count, p.posting_kind, p.currency_code, p.reverses_posting_id
      INTO declared_cnt, kind, posting_ccy, reverses
      FROM ledger_posting p WHERE p.id = posting;
    IF NOT FOUND THEN
        RAISE EXCEPTION 'ledger_posting_missing: posting % does not exist', posting
            USING ERRCODE = '23514';
    END IF;

    SELECT COUNT(*), COUNT(DISTINCT e.line_number),
           MIN(e.line_number), MAX(e.line_number),
           COUNT(DISTINCT e.account_id),
           COALESCE(SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)
      INTO actual_cnt, distinct_lines, min_line, max_line, distinct_accts, signed_total
      FROM ledger_entry e WHERE e.posting_id = posting;

    IF actual_cnt <> declared_cnt THEN
        RAISE EXCEPTION 'ledger_posting_count: posting % declares % entries but has %',
            posting, declared_cnt, actual_cnt USING ERRCODE = '23514';
    END IF;

    IF distinct_lines <> declared_cnt OR min_line <> 1 OR max_line <> declared_cnt THEN
        RAISE EXCEPTION 'ledger_posting_lines: posting % lines are not exactly 1..%', posting, declared_cnt
            USING ERRCODE = '23514';
    END IF;

    IF distinct_accts < 2 THEN
        RAISE EXCEPTION 'ledger_posting_accounts: posting % touches fewer than two accounts', posting
            USING ERRCODE = '23514';
    END IF;

    IF signed_total <> 0 THEN
        RAISE EXCEPTION 'ledger_posting_balance: posting % signed sum is %', posting, signed_total
            USING ERRCODE = '23514';
    END IF;

    -- Overdraft is evaluated against the full current ledger for every DENY
    -- account this posting touches, aggregated over all its lines at once so
    -- line order inside one posting cannot change the result.
    FOR acct IN
        SELECT DISTINCT e.account_id FROM ledger_entry e WHERE e.posting_id = posting
    LOOP
        IF EXISTS (SELECT 1 FROM ledger_account a WHERE a.id = acct AND a.overdraft_policy = 'DENY') THEN
            SELECT COALESCE(SUM(CASE
                       WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND e.side = 'DEBIT')
                         OR (a.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.side = 'CREDIT')
                       THEN e.amount_minor ELSE -e.amount_minor END), 0)
              INTO acct_balance
              FROM ledger_account a JOIN ledger_entry e ON e.account_id = a.id
             WHERE a.id = acct;
            IF acct_balance < 0 THEN
                RAISE EXCEPTION 'ledger_posting_overdraft: posting % leaves DENY account % at %',
                    posting, acct, acct_balance USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;

    -- Reversals: exactly one reversal of an original, and it must be the
    -- line-for-line inverse (same account, amount, currency; opposite side).
    -- Uniqueness of reverses_posting_id permits only one; this check proves
    -- the inverse. The application still applies overdraft policy above, so
    -- an exact reversal can be rejected after later activity overdraws DENY.
    IF kind = 'REVERSAL' THEN
        SELECT p.posting_kind, p.currency_code
          INTO target_kind, target_ccy
          FROM ledger_posting p WHERE p.id = reverses;
        IF NOT FOUND THEN
            RAISE EXCEPTION 'ledger_reversal_missing: posting % reverses absent %', posting, reverses
                USING ERRCODE = '23514';
        END IF;
        IF target_kind <> 'STANDARD' THEN
            RAISE EXCEPTION 'ledger_reversal_kind: posting % reverses non-standard %', posting, reverses
                USING ERRCODE = '23514';
        END IF;
        IF target_ccy <> posting_ccy THEN
            RAISE EXCEPTION 'ledger_reversal_currency: posting % currency % differs from %',
                posting, posting_ccy, target_ccy USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT n.account_id, n.amount_minor, n.currency_code,
                   CASE n.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
              FROM ledger_entry n WHERE n.posting_id = posting
            EXCEPT
            SELECT o.account_id, o.amount_minor, o.currency_code, o.side
              FROM ledger_entry o WHERE o.posting_id = reverses
        ) THEN
            RAISE EXCEPTION 'ledger_reversal_inverse: posting % is not the exact inverse of %',
                posting, reverses USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT o.account_id, o.amount_minor, o.currency_code, o.side
              FROM ledger_entry o WHERE o.posting_id = reverses
            EXCEPT
            SELECT n.account_id, n.amount_minor, n.currency_code,
                   CASE n.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
              FROM ledger_entry n WHERE n.posting_id = posting
        ) THEN
            RAISE EXCEPTION 'ledger_reversal_inverse: posting % is not the exact inverse of %',
                posting, reverses USING ERRCODE = '23514';
        END IF;
    END IF;

    RETURN NULL;
END;
$$ LANGUAGE plpgsql;

CREATE CONSTRAINT TRIGGER ledger_posting_deferred_check
    AFTER INSERT ON ledger_posting
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_posting();

CREATE CONSTRAINT TRIGGER ledger_entry_deferred_check
    AFTER INSERT ON ledger_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION ledger_check_posting();
