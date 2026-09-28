-- Ledger journal schema, applied by Flyway as the ledger_owner role

CREATE TABLE ledger_currency (
    code varchar(3) PRIMARY KEY,
    decimal_places smallint NOT NULL,
    CONSTRAINT ledger_currency_decimal_places_range CHECK (decimal_places BETWEEN 0 AND 6)
);

INSERT INTO ledger_currency (code, decimal_places) VALUES
    ('CAD', 2),
    ('USD', 2),
    ('GBP', 2), 
    ('EUR', 2), -- 10.00 is 1000 minor units (decimal_places is 2)
    ('JPY', 0); -- 1000¥ is 1000 minor units (decimal_places is 0)

-- The (id, currency_code) key lets entries prove currency agreement.
CREATE TABLE ledger_account ( -- immutable
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    name varchar(200) NOT NULL,
    account_code varchar(64) NOT NULL UNIQUE,
    currency_code varchar(3) NOT NULL REFERENCES ledger_currency (code) ON UPDATE NO ACTION ON DELETE NO ACTION,
    account_type varchar(16) NOT NULL,
    overdraft_policy varchar(5) NOT NULL,
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ledger_account_name_nonblank CHECK (char_length(name) BETWEEN 1 AND 200),
    CONSTRAINT ledger_account_overdraft_allowed CHECK (overdraft_policy IN ('ALLOW', 'DENY')),
    CONSTRAINT ledger_account_id_currency UNIQUE (id, currency_code),
    CONSTRAINT ledger_account_type_allowed CHECK (account_type IN ('ASSET', 'EXPENSE', 'LIABILITY', 'EQUITY', 'REVENUE'))
);

-- Posting header: no DRAFT/POSTED status; entry_count closes the posting.
CREATE TABLE ledger_posting (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    idempotency_key varchar(128) NOT NULL UNIQUE,
    request_fingerprint bytea NOT NULL,
    fingerprint_version smallint NOT NULL DEFAULT 1,
    posting_kind varchar(8) NOT NULL,
    reverses_posting_id uuid UNIQUE REFERENCES ledger_posting (id) ON UPDATE NO ACTION ON DELETE NO ACTION,
    currency_code varchar(3) NOT NULL REFERENCES ledger_currency (code) ON UPDATE NO ACTION ON DELETE NO ACTION,
    entry_count smallint NOT NULL,
    description varchar(500) NOT NULL,
    effective_at timestamptz NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ledger_posting_key_charset CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    CONSTRAINT ledger_posting_fingerprint_sha256 CHECK (octet_length(request_fingerprint) = 32),
    CONSTRAINT ledger_posting_fingerprint_version CHECK (fingerprint_version = 1),
    CONSTRAINT ledger_posting_kind_allowed CHECK (posting_kind IN ('STANDARD', 'REVERSAL')),
    CONSTRAINT ledger_posting_entry_count_range CHECK (entry_count BETWEEN 2 AND 100),
    CONSTRAINT ledger_posting_description_shape CHECK (char_length(description) BETWEEN 1 AND 500
        AND description !~ '[\x00-\x1F\x7F]'),
    CONSTRAINT ledger_posting_id_currency UNIQUE (id, currency_code),
    CONSTRAINT ledger_posting_reversal_coherence CHECK (
        (posting_kind = 'STANDARD' AND reverses_posting_id IS NULL)
        OR (posting_kind = 'REVERSAL' AND reverses_posting_id IS NOT NULL)
    )
);

-- Entry: redundant currency_code proves posting/account/entry currency agree. No cascades through history.
CREATE TABLE ledger_entry (
    id uuid PRIMARY KEY DEFAULT uuidv7(),
    posting_id uuid NOT NULL,
    entry_number smallint NOT NULL,
    account_id uuid NOT NULL,
    currency_code varchar(3) NOT NULL,
    side varchar(6) NOT NULL,
    amount_minor_units bigint NOT NULL,
    CONSTRAINT ledger_entry_entry_range CHECK (entry_number BETWEEN 1 AND 100),
    CONSTRAINT ledger_entry_side_allowed CHECK (side IN ('DEBIT', 'CREDIT')),
    CONSTRAINT ledger_entry_amount_positive CHECK (amount_minor_units > 0),
    CONSTRAINT ledger_entry_posting_entry UNIQUE (posting_id, entry_number),
    CONSTRAINT ledger_entry_posting_currency FOREIGN KEY (posting_id, currency_code) REFERENCES ledger_posting (id, currency_code) ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ledger_entry_account_currency FOREIGN KEY (account_id, currency_code) REFERENCES ledger_account (id, currency_code) ON UPDATE NO ACTION ON DELETE NO ACTION
);

CREATE INDEX ledger_entry_account_posting ON ledger_entry (account_id, posting_id);

-- Signed sums: DEBIT is +amount, CREDIT is -amount. ASSET/EXPENSE balance = debits - credits, all others = credits - debits.
CREATE VIEW v_account_balance AS
SELECT
    a.id AS account_id,
    a.account_code,
    a.account_type,
    a.currency_code,
    COALESCE(SUM(CASE
        WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND e.side = 'DEBIT')
          OR (a.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.side = 'CREDIT')
        THEN e.amount_minor_units
        ELSE -e.amount_minor_units
    END), 0)::bigint AS balance_minor_units
FROM ledger_account a
LEFT JOIN ledger_entry e ON e.account_id = a.id
GROUP BY a.id, a.account_code, a.account_type, a.currency_code;

CREATE VIEW v_conservation AS
SELECT
    e.currency_code,
    SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor_units ELSE -e.amount_minor_units END)::numeric AS signed_sum
FROM ledger_entry e
GROUP BY e.currency_code;

CREATE VIEW v_posting_integrity AS
SELECT
    p.id AS posting_id,
    p.entry_count AS declared_count,
    COUNT(e.id)::smallint AS actual_count,
    COALESCE(SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor_units ELSE -e.amount_minor_units END), 0)::numeric AS signed_sum,
    COUNT(DISTINCT e.account_id)::smallint AS distinct_accounts
FROM ledger_posting p
LEFT JOIN ledger_entry e ON e.posting_id = p.id
GROUP BY p.id, p.entry_count;

-- Deferred to COMMIT: checks counts, 1..entry_number closure, two accounts, zero signed sum, DENY overdraft, exact reversal inverse. Failures raise 23514 with a ledger_posting_* prefix.
CREATE OR REPLACE FUNCTION ledger_check_posting() RETURNS trigger AS $$
DECLARE
    posting        uuid;
    declared_cnt   smallint;
    kind           varchar(8);
    posting_ccy    varchar(3);
    reverses       uuid;
    actual_cnt     integer;
    distinct_entries integer;
    min_entry       smallint;
    max_entry       smallint;
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

    SELECT COUNT(*), COUNT(DISTINCT e.entry_number),
           MIN(e.entry_number), MAX(e.entry_number),
           COUNT(DISTINCT e.account_id),
           COALESCE(SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor_units ELSE -e.amount_minor_units END), 0)
      INTO actual_cnt, distinct_entries, min_entry, max_entry, distinct_accts, signed_total
      FROM ledger_entry e WHERE e.posting_id = posting;

    IF actual_cnt <> declared_cnt THEN
        RAISE EXCEPTION 'ledger_posting_count: posting % declares % entries but has %',
            posting, declared_cnt, actual_cnt USING ERRCODE = '23514';
    END IF;

    IF distinct_entries <> declared_cnt OR min_entry <> 1 OR max_entry <> declared_cnt THEN
        RAISE EXCEPTION 'ledger_posting_entries: posting % entries are not exactly 1..%', posting, declared_cnt
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

    FOR acct IN
        SELECT DISTINCT e.account_id FROM ledger_entry e WHERE e.posting_id = posting
    LOOP
        IF EXISTS (SELECT 1 FROM ledger_account a WHERE a.id = acct AND a.overdraft_policy = 'DENY') THEN
            SELECT COALESCE(SUM(CASE
                       WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND e.side = 'DEBIT')
                         OR (a.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.side = 'CREDIT')
                       THEN e.amount_minor_units ELSE -e.amount_minor_units END), 0)
              INTO acct_balance
              FROM ledger_account a JOIN ledger_entry e ON e.account_id = a.id
             WHERE a.id = acct;
            IF acct_balance < 0 THEN
                RAISE EXCEPTION 'ledger_posting_overdraft: posting % leaves DENY account % at %',
                    posting, acct, acct_balance USING ERRCODE = '23514';
            END IF;
        END IF;
    END LOOP;

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
            SELECT n.account_id, n.amount_minor_units, n.currency_code,
                   CASE n.side WHEN 'DEBIT' THEN 'CREDIT' ELSE 'DEBIT' END
              FROM ledger_entry n WHERE n.posting_id = posting
            EXCEPT
            SELECT o.account_id, o.amount_minor_units, o.currency_code, o.side
              FROM ledger_entry o WHERE o.posting_id = reverses
        ) THEN
            RAISE EXCEPTION 'ledger_reversal_inverse: posting % is not the exact inverse of %',
                posting, reverses USING ERRCODE = '23514';
        END IF;
        IF EXISTS (
            SELECT o.account_id, o.amount_minor_units, o.currency_code, o.side
              FROM ledger_entry o WHERE o.posting_id = reverses
            EXCEPT
            SELECT n.account_id, n.amount_minor_units, n.currency_code,
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

-- ledger_app least-privilege: selects plus narrow inserts only; immutable-row triggers backstop the grants.
REVOKE ALL ON TABLE ledger_currency, ledger_account, ledger_posting, ledger_entry
    FROM PUBLIC, ledger_app;

GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT ON TABLE
    ledger_currency, ledger_account, ledger_posting, ledger_entry,
    v_account_balance, v_conservation, v_posting_integrity,
    flyway_schema_history
    TO ledger_app;

GRANT INSERT (account_code, name, currency_code, account_type, overdraft_policy)
    ON TABLE ledger_account TO ledger_app;
GRANT INSERT (idempotency_key, request_fingerprint, posting_kind,
              reverses_posting_id, currency_code, entry_count,
              description, effective_at)
    ON TABLE ledger_posting TO ledger_app;
GRANT INSERT (posting_id, entry_number, account_id, currency_code, side, amount_minor_units)
    ON TABLE ledger_entry TO ledger_app;

CREATE OR REPLACE FUNCTION ledger_forbid_mutation() RETURNS trigger AS $$
BEGIN
    RAISE EXCEPTION 'ledger immutable: % on % is forbidden', TG_OP, TG_TABLE_NAME
        USING ERRCODE = '25001';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER ledger_account_immutable
    BEFORE UPDATE OR DELETE ON ledger_account
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER ledger_posting_immutable
    BEFORE UPDATE OR DELETE ON ledger_posting
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
CREATE TRIGGER ledger_entry_immutable
    BEFORE UPDATE OR DELETE ON ledger_entry
    FOR EACH ROW EXECUTE FUNCTION ledger_forbid_mutation();
