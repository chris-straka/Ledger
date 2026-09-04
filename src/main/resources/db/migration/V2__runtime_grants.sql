-- V2: least-privilege runtime role. Runs as ledger_owner. The proven threat
-- boundary is the runtime identity ledger_app: structural balance, currency,
-- closure, and row immutability survive arbitrary runtime-role SQL, while
-- concurrent overdraft safety additionally assumes the SERIALIZABLE posting
-- protocol (see V3). A database owner can deliberately alter these
-- protections; that is outside the boundary and stated, not hidden.

-- Strip every path through history first, then grant back only what the
-- runtime needs. Revoking from ledger_app is belt-and-braces: it was never
-- granted these, and the revoke keeps it true if defaults ever change.
REVOKE ALL ON TABLE ledger_currency, ledger_account, ledger_posting, ledger_entry
    FROM PUBLIC, ledger_app;

-- Connectivity and read surface.
GRANT USAGE ON SCHEMA public TO ledger_app;
GRANT SELECT ON TABLE
    ledger_currency, ledger_account, ledger_posting, ledger_entry,
    v_account_balance, v_conservation, v_posting_integrity,
    flyway_schema_history
    TO ledger_app;

-- Narrow inserts. Generated columns are deliberately excluded so the runtime
-- role cannot supply IDs or timestamps: id defaults to uuidv7() and
-- created_at/recorded_at default to statement_timestamp().
GRANT INSERT (account_code, name, currency_code, account_type, overdraft_policy)
    ON TABLE ledger_account TO ledger_app;
GRANT INSERT (idempotency_key, request_fingerprint, posting_kind,
              reverses_posting_id, currency_code, entry_count,
              description, effective_at)
    ON TABLE ledger_posting TO ledger_app;
GRANT INSERT (posting_id, line_number, account_id, currency_code, side, amount_minor)
    ON TABLE ledger_entry TO ledger_app;

-- Defense in depth behind the grants: immutable-row triggers refuse
-- UPDATE/DELETE even if a grant is ever mis-issued. The required role test
-- proves the grants; these triggers are the backstop, not the mechanism.
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
