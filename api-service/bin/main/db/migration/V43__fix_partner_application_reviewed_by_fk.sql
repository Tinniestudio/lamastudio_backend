-- V43__fix_partner_application_reviewed_by_fk.sql
-- partner_applications.reviewed_by was defined as REFERENCES users(id), but the admin
-- principal that reviews an application is a row in `admins`, not `users` (admin auth was
-- isolated into its own table in V3/V8). Every approve/reject call was passing an admins.id
-- into a column FK-constrained to users(id), so it failed with a foreign key violation.
-- Fix it to reference admins(id), matching the existing user_sessions.revoked_by_admin_id
-- pattern from V4.

ALTER TABLE partner_applications
    DROP CONSTRAINT IF EXISTS partner_applications_reviewed_by_fkey;

ALTER TABLE partner_applications
    ADD CONSTRAINT partner_applications_reviewed_by_fkey
    FOREIGN KEY (reviewed_by) REFERENCES admins(id);
