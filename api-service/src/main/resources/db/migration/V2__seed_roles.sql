-- V2__seed_roles.sql
-- Seed default application roles

INSERT INTO roles (name) VALUES
    ('ROLE_USER'),
    ('ROLE_ADMIN'),
    ('ROLE_SUPER_ADMIN')
ON CONFLICT (name) DO NOTHING;
