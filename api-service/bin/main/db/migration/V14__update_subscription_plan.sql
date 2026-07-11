UPDATE subscription_plans
SET
    currency = 'CAD',
    updated_at = NOW()
WHERE name = 'FREE';

UPDATE subscription_plans
SET
    currency = 'CAD',
    updated_at = NOW()
WHERE name = 'SILVER';

UPDATE subscription_plans
SET
    currency = 'CAD',
    updated_at = NOW()
WHERE name = 'GOLD';