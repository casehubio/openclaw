-- Creates native SQL tables that are not JPA entities and therefore
-- not created by Hibernate drop-and-create. Required for QhorusSequenceAllocator
-- which executes MERGE INTO ledger_subject_sequence. Refs qhorus#256.
--
-- Known behavior: rows in this table are NOT reset by drop-and-create.
-- H2 DB_CLOSE_DELAY=-1 keeps the database alive for the JVM lifetime; rows
-- survive Quarkus context restarts. Tests must use fresh random UUIDs per run
-- as subjectIds to avoid cross-context sequence pollution.
CREATE TABLE IF NOT EXISTS ledger_subject_sequence (
    subject_id UUID        PRIMARY KEY,
    next_seq   BIGINT      NOT NULL
);
