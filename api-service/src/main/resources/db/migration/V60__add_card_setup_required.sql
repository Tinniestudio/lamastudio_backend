-- V60__add_card_setup_required.sql
-- Legacy card-setup grandfathering: the card-required-before-access policy only applies to
-- users created after this migration. Every account that already exists is exempt, regardless
-- of its current plan/card state (see docs/superpowers/specs/2026-10-26-legacy-card-setup-grandfathering-design.md).

ALTER TABLE user_profiles ADD COLUMN IF NOT EXISTS card_setup_required BOOLEAN NOT NULL DEFAULT TRUE;

-- Backfill: any user who already has a profile row is exempt.
UPDATE user_profiles SET card_setup_required = FALSE;

-- Backfill: profiles are created lazily (see UserProfileServiceImpl.createEmptyProfile), so many
-- pre-existing users have no user_profiles row at all yet. Insert one for them, exempt too.
INSERT INTO user_profiles (user_id, card_setup_required)
SELECT u.id, FALSE
FROM users u
LEFT JOIN user_profiles p ON p.user_id = u.id
WHERE p.user_id IS NULL;
