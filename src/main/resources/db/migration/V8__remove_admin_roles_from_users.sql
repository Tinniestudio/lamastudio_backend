-- V8__remove_admin_roles_from_users.sql
-- Remove admin roles from the user roles table now that admin auth is isolated in its own entity.
-- Also seeds ROLE_PARTNER which was not in the original V2 seed.

-- Remove admin roles from any existing user_roles assignments
DELETE FROM user_roles
WHERE role_id IN (
    SELECT id FROM roles WHERE name IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN')
);

-- Remove admin role definitions from the shared roles table
DELETE FROM roles
WHERE name IN ('ROLE_ADMIN', 'ROLE_SUPER_ADMIN');

-- Add ROLE_PARTNER (user-facing partner role, not seeded in V2)
INSERT INTO roles (name) VALUES ('ROLE_PARTNER')
ON CONFLICT (name) DO NOTHING;
