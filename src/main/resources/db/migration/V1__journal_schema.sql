-- V1: ledger journal schema. Owned by ledger_owner via Flyway; the runtime
-- role ledger_app never runs migrations (see V2 for its narrow grants).
--
-- Sign convention (PORT.md section 2), used by every view and trigger here:
--   journalSigned(entry) = +amount_minor for DEBIT, -amount_minor for CREDIT.
-- A posting balances when its signed sum is zero. An account balance uses its
-- type's normal side: ASSET/EXPENSE balances are debits - credits, all other
-- types are credits - debits.

-- Reference metadata, not an exchange-rate table. V1 accepts only a code
-- present here and deals only in already-converted minor units. JPY covers
-- the zero-decimal case; the rest are two-decimal currencies.
CREATE TABLE ledger_currency (
    code            varchar(3) PRIMARY KEY,
    minor_unit_digits smallint NOT NULL
        CONSTRAINT ledger_currency_digits_range CHECK (minor_unit_digits BETWEEN 0 AND 6)
);

INSERT INTO ledger_currency (code, minor_unit_digits) VALUES
    ('CAD', 2),
    ('USD', 2),
    ('GBP', 2),
    ('EUR', 2),
    ('JPY', 0);

-- Immutable account. No opening_balance column: opening value is a posting
-- against another account. The (id, currency_code) key lets entries prove
-- through a composite foreign key that account and entry currency agree.
CREATE TABLE ledger_account (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    account_code    varchar(64) NOT NULL UNIQUE,
    name            varchar(200) NOT NULL
        CONSTRAINT ledger_account_name_nonblank CHECK (char_length(name) BETWEEN 1 AND 200),
    currency_code   varchar(3) NOT NULL REFERENCES ledger_currency (code)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    account_type    varchar(16) NOT NULL
        CONSTRAINT ledger_account_type_allowed
        CHECK (account_type IN ('ASSET', 'EXPENSE', 'LIABILITY', 'EQUITY', 'REVENUE')),
    overdraft_policy varchar(5) NOT NULL
        CONSTRAINT ledger_account_overdraft_allowed
        CHECK (overdraft_policy IN ('ALLOW', 'DENY')),
    created_at      timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ledger_account_id_currency UNIQUE (id, currency_code)
);

-- Posting header. There is no mutable DRAFT/POSTED status: a row is invisible
-- outside its transaction until header, all declared entries, and deferred
-- checks commit together. entry_count is immutable and closes the posting: a
-- later append changes the actual count and the commit-time trigger fails.
CREATE TABLE ledger_posting (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    idempotency_key varchar(128) NOT NULL UNIQUE
        CONSTRAINT ledger_posting_key_charset
        CHECK (idempotency_key ~ '^[A-Za-z0-9._:-]{1,128}$'),
    request_fingerprint bytea NOT NULL
        CONSTRAINT ledger_posting_fingerprint_sha256 CHECK (octet_length(request_fingerprint) = 32),
    fingerprint_version smallint NOT NULL DEFAULT 1
        CONSTRAINT ledger_posting_fingerprint_version CHECK (fingerprint_version = 1),
    posting_kind    varchar(8) NOT NULL
        CONSTRAINT ledger_posting_kind_allowed CHECK (posting_kind IN ('STANDARD', 'REVERSAL')),
    reverses_posting_id uuid UNIQUE REFERENCES ledger_posting (id)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    currency_code   varchar(3) NOT NULL REFERENCES ledger_currency (code)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    entry_count     smallint NOT NULL
        CONSTRAINT ledger_posting_entry_count_range CHECK (entry_count BETWEEN 2 AND 100),
    description     varchar(500) NOT NULL
        CONSTRAINT ledger_posting_description_shape
        CHECK (char_length(description) BETWEEN 1 AND 500
               AND description !~ '[\x00-\x1F\x7F]'),
    effective_at    timestamptz NOT NULL,
    recorded_at     timestamptz NOT NULL DEFAULT statement_timestamp(),
    CONSTRAINT ledger_posting_id_currency UNIQUE (id, currency_code),
    CONSTRAINT ledger_posting_reversal_coherence CHECK (
        (posting_kind = 'STANDARD' AND reverses_posting_id IS NULL)
        OR (posting_kind = 'REVERSAL' AND reverses_posting_id IS NOT NULL)
    )
);

-- Entry. The redundant currency_code is intentional: the two composite keys
-- let PostgreSQL prove posting, account, and entry currency agree without
-- trusting Java. No cascade path through financial history, ever.
CREATE TABLE ledger_entry (
    id              uuid PRIMARY KEY DEFAULT uuidv7(),
    posting_id      uuid NOT NULL,
    line_number     smallint NOT NULL
        CONSTRAINT ledger_entry_line_range CHECK (line_number BETWEEN 1 AND 100),
    account_id      uuid NOT NULL,
    currency_code   varchar(3) NOT NULL,
    side            varchar(6) NOT NULL
        CONSTRAINT ledger_entry_side_allowed CHECK (side IN ('DEBIT', 'CREDIT')),
    amount_minor    bigint NOT NULL
        CONSTRAINT ledger_entry_amount_positive CHECK (amount_minor > 0),
    CONSTRAINT ledger_entry_posting_line UNIQUE (posting_id, line_number),
    CONSTRAINT ledger_entry_posting_currency
        FOREIGN KEY (posting_id, currency_code) REFERENCES ledger_posting (id, currency_code)
        ON UPDATE NO ACTION ON DELETE NO ACTION,
    CONSTRAINT ledger_entry_account_currency
        FOREIGN KEY (account_id, currency_code) REFERENCES ledger_account (id, currency_code)
        ON UPDATE NO ACTION ON DELETE NO ACTION
);

-- Balance/history reads filter by account across postings.
CREATE INDEX ledger_entry_account_posting ON ledger_entry (account_id, posting_id);

-- Derived read models. Ordinary views, never materialized in V1: the journal
-- rows remain the single source of truth and every balance is recomputed.
CREATE VIEW v_account_balance AS
SELECT
    a.id AS account_id,
    a.account_code,
    a.account_type,
    a.currency_code,
    COALESCE(SUM(CASE
        WHEN (a.account_type IN ('ASSET', 'EXPENSE') AND e.side = 'DEBIT')
          OR (a.account_type IN ('LIABILITY', 'EQUITY', 'REVENUE') AND e.side = 'CREDIT')
        THEN e.amount_minor
        ELSE -e.amount_minor
    END), 0)::bigint AS balance_minor
FROM ledger_account a
LEFT JOIN ledger_entry e ON e.account_id = a.id
GROUP BY a.id, a.account_code, a.account_type, a.currency_code;

-- Independent conservation audit: every currency sums to zero.
CREATE VIEW v_conservation AS
SELECT
    e.currency_code,
    SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END)::numeric AS signed_sum
FROM ledger_entry e
GROUP BY e.currency_code;

-- Posting integrity diagnostics for the verify/demo audit.
CREATE VIEW v_posting_integrity AS
SELECT
    p.id AS posting_id,
    p.entry_count AS declared_count,
    COUNT(e.id)::smallint AS actual_count,
    COALESCE(SUM(CASE WHEN e.side = 'DEBIT' THEN e.amount_minor ELSE -e.amount_minor END), 0)::numeric AS signed_sum,
    COUNT(DISTINCT e.account_id)::smallint AS distinct_accounts
FROM ledger_posting p
LEFT JOIN ledger_entry e ON e.posting_id = p.id
GROUP BY p.id, p.entry_count;
