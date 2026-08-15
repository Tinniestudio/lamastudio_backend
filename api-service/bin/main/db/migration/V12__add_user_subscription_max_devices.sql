-- Snapshot max_devices from the plan onto each subscription row so that
-- changing a plan's maxDevices does not silently affect existing subscribers.

ALTER TABLE user_subscriptions ADD COLUMN IF NOT EXISTS max_devices INT;

-- Backfill existing rows from their associated plan.
UPDATE user_subscriptions us
SET max_devices = sp.max_devices
FROM subscription_plans sp
WHERE us.plan_id = sp.id
  AND us.max_devices IS NULL;

-- Any subscriptions with no plan (or plan has no max_devices) default to 1.
UPDATE user_subscriptions
SET max_devices = 1
WHERE max_devices IS NULL;
