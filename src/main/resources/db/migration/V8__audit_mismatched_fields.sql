-- Records every field that failed, not only the first.
--
-- failure_reason_code keeps the primary reason, so existing queries and dashboards keep working.
-- mismatched_fields adds the complete picture as a comma-separated list of request field names
-- (lastName, dateOfBirth, ...), which is what a diagnostics-scoped response reports and what an
-- operator needs when investigating why a batch of checks is failing.
--
-- ALTER TABLE ... ADD COLUMN is portable across H2 and PostgreSQL, so this stays in the shared
-- migration folder rather than the vendor split.

ALTER TABLE verification_audit ADD COLUMN mismatched_fields VARCHAR(255);
